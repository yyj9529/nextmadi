package com.phraselog.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the {@code GET /usage/today} day-boundary aggregation against a real Postgres (#52).
 * Skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcUsageRepositoryTests {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("phraselog_test")
          .withUsername("phraselog")
          .withPassword("phraselog");

  // "Today" under test = 2026-06-21 UTC; half-open range [from, to).
  private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-06-21T00:00:00Z");
  private static final OffsetDateTime TO = OffsetDateTime.parse("2026-06-22T00:00:00Z");

  private static DataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private JdbcUsageRepository repository;
  private UUID coachId;

  @BeforeAll
  static void migrate() {
    dataSource =
        DataSourceBuilder.create()
            .url(postgres.getJdbcUrl())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.update("DELETE FROM practice_sessions");
    jdbcTemplate.update("DELETE FROM analysis_requests");
    jdbcTemplate.update("DELETE FROM users");
    repository = new JdbcUsageRepository(jdbcTemplate);
    // coach_profiles is seeded by V003; reuse Mia for FK satisfaction.
    coachId =
        jdbcTemplate.queryForObject("SELECT id FROM coach_profiles WHERE slug = 'mia'", UUID.class);
  }

  @Test
  void roleplayCountIncludesOnlyTodaysNonAbandonedRowsForTheUser() {
    UUID userId = insertUser("owner@example.com");
    UUID otherUser = insertUser("other@example.com");

    insertSession(userId, OffsetDateTime.parse("2026-06-20T23:59:59Z"), "completed"); // yesterday
    insertSession(userId, FROM, "active"); // exact start — included
    insertSession(userId, OffsetDateTime.parse("2026-06-21T12:00:00Z"), "completed");
    insertSession(userId, OffsetDateTime.parse("2026-06-21T23:59:59Z"), "active");
    insertSession(userId, TO, "active"); // exact next-day start — excluded
    insertSession(userId, OffsetDateTime.parse("2026-06-21T10:00:00Z"), "abandoned"); // excluded
    insertSession(otherUser, OffsetDateTime.parse("2026-06-21T12:00:00Z"), "active"); // other user

    assertThat(repository.countRoleplaySessions(userId, FROM, TO)).isEqualTo(3);
  }

  @Test
  void analysisCountIncludesOnlyTodaysRowsForTheUser() {
    UUID userId = insertUser("owner@example.com");
    UUID otherUser = insertUser("other@example.com");

    insertAnalysis(userId, OffsetDateTime.parse("2026-06-20T23:59:59Z")); // yesterday
    insertAnalysis(userId, FROM); // exact start — included
    insertAnalysis(userId, OffsetDateTime.parse("2026-06-21T12:00:00Z"));
    insertAnalysis(userId, OffsetDateTime.parse("2026-06-21T23:59:59Z"));
    insertAnalysis(userId, TO); // next-day start — excluded
    insertAnalysis(otherUser, OffsetDateTime.parse("2026-06-21T12:00:00Z")); // other user

    assertThat(repository.countAnalysisRequests(userId, FROM, TO)).isEqualTo(3);
  }

  @Test
  void countsAreZeroWhenNoRowsToday() {
    UUID userId = insertUser("owner@example.com");
    insertSession(userId, OffsetDateTime.parse("2026-06-20T12:00:00Z"), "completed");
    insertAnalysis(userId, OffsetDateTime.parse("2026-06-20T12:00:00Z"));

    assertThat(repository.countRoleplaySessions(userId, FROM, TO)).isZero();
    assertThat(repository.countAnalysisRequests(userId, FROM, TO)).isZero();
  }

  private UUID insertUser(String email) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, email, is_onboarded, created_at) VALUES (?, ?, true, now())",
        id,
        email);
    return id;
  }

  private void insertSession(UUID userId, OffsetDateTime startedAt, String status) {
    jdbcTemplate.update(
        """
        INSERT INTO practice_sessions
          (id, user_id, coach_id, status, planned_turns, started_at)
        VALUES (?, ?, ?, ?, 3, ?)
        """,
        UUID.randomUUID(),
        userId,
        coachId,
        status,
        startedAt);
  }

  private void insertAnalysis(UUID userId, OffsetDateTime createdAt) {
    jdbcTemplate.update(
        """
        INSERT INTO analysis_requests
          (id, user_id, input_text, output_json, prompt_version, created_at)
        VALUES (?, ?, '상황', '{}'::jsonb, 's07-v1', ?)
        """,
        UUID.randomUUID(),
        userId,
        createdAt);
  }
}
