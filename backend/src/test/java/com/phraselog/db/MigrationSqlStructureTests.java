package com.phraselog.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class MigrationSqlStructureTests {

  @Test
  void initialMigrationContainsRequiredTablesConstraintsAndIndexes() throws Exception {
    String sql =
        new String(
            new ClassPathResource("db/migration/V001__init_schema.sql")
                .getInputStream()
                .readAllBytes(),
            StandardCharsets.UTF_8);

    assertThat(sql)
        .contains(
            "CREATE TABLE users",
            "CREATE TABLE coach_profiles",
            "CREATE TABLE landing_examples",
            "CREATE TABLE anonymous_analysis_usage",
            "CREATE TABLE tts_audio_cache",
            "CREATE TABLE ai_request_logs",
            "CREATE TABLE user_auth_identities",
            "CREATE TABLE analysis_requests",
            "CREATE TABLE practice_sessions",
            "CREATE TABLE expressions",
            "CREATE TABLE expression_variants",
            "CREATE TABLE review_cards",
            "CREATE TABLE review_attempts",
            "CREATE TABLE practice_turns");

    assertThat(sql)
        .contains(
            "CONSTRAINT chk_analysis_input_text_length",
            "CONSTRAINT chk_expressions_source",
            "CONSTRAINT chk_expressions_source_type",
            "CONSTRAINT chk_expression_variants_order",
            "CONSTRAINT chk_practice_sessions_planned_turns",
            "CONSTRAINT chk_practice_sessions_status",
            "CONSTRAINT chk_practice_turns_speaker",
            "CONSTRAINT uq_expression_variants_order",
            "CONSTRAINT uq_review_cards_user_expression",
            "CONSTRAINT uq_practice_turns_session_turn",
            "CONSTRAINT uq_tts_audio_cache_key",
            "CONSTRAINT pk_anonymous_analysis_usage",
            "ADD CONSTRAINT fk_expressions_selected_variant",
            "ADD CONSTRAINT fk_users_selected_coach",
            "ADD CONSTRAINT fk_practice_sessions_expression");

    assertThat(sql)
        .contains(
            "CREATE INDEX idx_review_due",
            "CREATE INDEX idx_expressions_situation_search",
            "CREATE INDEX idx_variants_search",
            "CREATE UNIQUE INDEX idx_users_email_active",
            "CREATE INDEX idx_tts_cache_lookup",
            "CREATE INDEX idx_logs_correlation",
            "USING gin(to_tsvector('simple', original_situation))",
            "USING gin(to_tsvector('simple', english_text))");

    assertThat(sql)
        .contains(
            "scheduled_deletion_at TIMESTAMPTZ",
            "deleted_at TIMESTAMPTZ",
            "removed_from_queue_at TIMESTAMPTZ");
  }

  @Test
  void practiceTurnRequestsMigrationContainsIdempotencyAndReplayFields() throws Exception {
    String sql =
        new String(
            new ClassPathResource("db/migration/V005__practice_turn_requests.sql")
                .getInputStream()
                .readAllBytes(),
            StandardCharsets.UTF_8);

    assertThat(sql)
        .contains(
            "CREATE TABLE practice_turn_requests",
            "session_id UUID NOT NULL REFERENCES practice_sessions(id) ON DELETE CASCADE",
            "idempotency_key UUID NOT NULL",
            "request_correlation_id UUID NOT NULL",
            "user_turn_id UUID REFERENCES practice_turns(id) ON DELETE SET NULL",
            "coach_turn_id UUID REFERENCES practice_turns(id) ON DELETE SET NULL",
            "CONSTRAINT uq_practice_turn_requests_session_key UNIQUE (session_id, idempotency_key)",
            "CONSTRAINT chk_practice_turn_requests_status",
            "CREATE INDEX idx_practice_turn_requests_correlation");
  }

  @Test
  void roleplayResultSaveMigrationContainsIdempotencyAndDuplicateGuards() throws Exception {
    String sql =
        new String(
            new ClassPathResource("db/migration/V006__roleplay_result_expression_idempotency.sql")
                .getInputStream()
                .readAllBytes(),
            StandardCharsets.UTF_8);

    assertThat(sql)
        .contains(
            "ADD COLUMN roleplay_result_index INTEGER",
            "ADD COLUMN roleplay_save_idempotency_key UUID",
            "CONSTRAINT chk_expressions_roleplay_result_index",
            "CREATE UNIQUE INDEX uq_expressions_roleplay_save_idem",
            "CREATE UNIQUE INDEX uq_expressions_roleplay_result_index_active",
            "source_type = 'roleplay_result'",
            "deleted_at IS NULL");
  }

  @Test
  void aiRequestLogAttemptMigrationAddsColumnsBackfillsGroupAndIndexes() throws Exception {
    String sql =
        new String(
            new ClassPathResource("db/migration/V008__ai_request_logs_attempt.sql")
                .getInputStream()
                .readAllBytes(),
            StandardCharsets.UTF_8);

    assertThat(sql)
        .contains(
            "ADD COLUMN attempt_group_id UUID",
            "ADD COLUMN attempt_number SMALLINT NOT NULL DEFAULT 1",
            "ADD COLUMN is_final_attempt BOOLEAN NOT NULL DEFAULT true",
            // Existing rows were one row per call, so each becomes its own single-attempt group.
            "UPDATE ai_request_logs SET attempt_group_id = id",
            "ALTER COLUMN attempt_group_id SET NOT NULL",
            "CONSTRAINT chk_ai_request_logs_attempt_number CHECK (attempt_number >= 1)",
            "CREATE INDEX idx_logs_attempt_group");
  }

  @Test
  void seedLandingExamplesMigrationInsertsActiveRowsIdempotently() throws Exception {
    String sql =
        new String(
            new ClassPathResource("db/migration/V007__seed_landing_examples.sql")
                .getInputStream()
                .readAllBytes(),
            StandardCharsets.UTF_8);

    assertThat(sql)
        .contains(
            "INSERT INTO landing_examples (korean_text)",
            // per-row idempotency guard: no unique constraint on korean_text
            "WHERE NOT EXISTS (",
            "FROM landing_examples le WHERE le.korean_text = v.korean_text");
  }
}
