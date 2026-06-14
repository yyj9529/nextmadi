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
}
