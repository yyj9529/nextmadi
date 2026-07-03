package com.phraselog.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationTests {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("phraselog_test")
          .withUsername("phraselog")
          .withPassword("phraselog");

  @Test
  void cleanPostgresDatabaseMigratesToFullV1Schema() throws Exception {
    DataSource dataSource =
        DataSourceBuilder.create()
            .url(postgres.getJdbcUrl())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

    try (Connection connection = dataSource.getConnection()) {
      assertThat(tables(connection))
          .contains(
              "users",
              "coach_profiles",
              "landing_examples",
              "anonymous_analysis_usage",
              "tts_audio_cache",
              "ai_request_logs",
              "user_auth_identities",
              "analysis_requests",
              "practice_sessions",
              "expressions",
              "expression_variants",
              "review_cards",
              "review_attempts",
              "practice_turns");

      assertThat(constraints(connection))
          .contains(
              "chk_analysis_input_text_length",
              "chk_expressions_source",
              "chk_expressions_source_type",
              "chk_expression_variants_order",
              "chk_practice_sessions_planned_turns",
              "chk_practice_sessions_status",
              "chk_practice_turns_speaker",
              "fk_expressions_selected_variant",
              "fk_users_selected_coach",
              "fk_practice_sessions_expression",
              "uq_expression_variants_order",
              "uq_review_cards_user_expression",
              "uq_practice_turns_session_turn",
              "uq_tts_audio_cache_key",
              "chk_expressions_roleplay_result_index",
              "pk_anonymous_analysis_usage");

      assertThat(indexes(connection))
          .contains(
              "idx_review_due",
              "idx_expressions_situation_search",
              "idx_variants_search",
              "idx_users_email_active",
              "idx_tts_cache_lookup",
              "idx_logs_correlation",
              "uq_expressions_roleplay_save_idem",
              "uq_expressions_roleplay_result_index_active");

      assertThat(columns(connection, "users")).contains("deleted_at", "scheduled_deletion_at");
      assertThat(columns(connection, "expressions"))
          .contains("deleted_at", "roleplay_result_index", "roleplay_save_idempotency_key");
      assertThat(columns(connection, "review_cards")).contains("removed_from_queue_at");
    }
  }

  private static Set<String> tables(Connection connection) throws Exception {
    return queryNames(
        connection,
        """
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = 'public'
        """,
        "table_name");
  }

  private static Set<String> constraints(Connection connection) throws Exception {
    return queryNames(
        connection,
        """
        SELECT constraint_name
        FROM information_schema.table_constraints
        WHERE constraint_schema = 'public'
        """,
        "constraint_name");
  }

  private static Set<String> indexes(Connection connection) throws Exception {
    return queryNames(
        connection,
        """
        SELECT indexname
        FROM pg_indexes
        WHERE schemaname = 'public'
        """,
        "indexname");
  }

  private static Set<String> columns(Connection connection, String tableName) throws Exception {
    return queryNames(
        connection,
        """
        SELECT column_name
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = ?
        """,
        "column_name",
        tableName);
  }

  private static Set<String> queryNames(Connection connection, String sql, String columnName)
      throws Exception {
    return queryNames(connection, sql, columnName, null);
  }

  private static Set<String> queryNames(
      Connection connection, String sql, String columnName, String parameter) throws Exception {
    try (var statement =
        parameter == null ? connection.prepareStatement(sql) : connection.prepareStatement(sql)) {
      if (parameter != null) {
        statement.setString(1, parameter);
      }
      Set<String> names = new TreeSet<>();
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          names.add(resultSet.getString(columnName));
        }
      }
      return names;
    }
  }
}
