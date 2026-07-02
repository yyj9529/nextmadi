package com.phraselog.practice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.practice.dto.PracticeResultContext;
import com.phraselog.practice.dto.PracticeSessionWithTurns;
import java.util.Optional;
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

@Testcontainers(disabledWithoutDocker = true)
class JdbcPracticeRepositoryTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("phraselog_test")
          .withUsername("phraselog")
          .withPassword("phraselog");

  private static DataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private JdbcPracticeRepository repository;
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
    jdbcTemplate.update("DELETE FROM practice_turn_requests");
    jdbcTemplate.update("DELETE FROM practice_turns");
    jdbcTemplate.update("DELETE FROM practice_sessions");
    jdbcTemplate.update("DELETE FROM review_attempts");
    jdbcTemplate.update("DELETE FROM review_cards");
    jdbcTemplate.update("DELETE FROM expression_variants");
    jdbcTemplate.update("DELETE FROM expressions");
    jdbcTemplate.update("DELETE FROM analysis_requests");
    jdbcTemplate.update("DELETE FROM users");
    repository = new JdbcPracticeRepository(jdbcTemplate, MAPPER);
    coachId =
        jdbcTemplate.queryForObject("SELECT id FROM coach_profiles WHERE slug = 'mia'", UUID.class);
  }

  @Test
  void resultJsonPersistsAndIsReadBackOnSessionGet() throws Exception {
    UUID userId = insertUser("owner@example.com");
    UUID expressionId = insertExpression(userId);
    UUID sessionId = insertSession(userId, expressionId, "completed", null);
    insertOpeningTurn(sessionId);
    JsonNode resultJson = resultJson("great work");

    JsonNode stored = repository.saveResultJsonIfAbsent(sessionId, userId, resultJson);
    Optional<PracticeSessionWithTurns> found = repository.findByIdForOwner(sessionId, userId);

    assertThat(stored).isEqualTo(resultJson);
    assertThat(found).isPresent();
    assertThat(found.get().session().resultJson()).isEqualTo(resultJson);
    assertThat(found.get().turns()).hasSize(1);
  }

  @Test
  void saveResultJsonIfAbsentReturnsExistingCacheWithoutOverwriting() throws Exception {
    UUID userId = insertUser("owner@example.com");
    UUID expressionId = insertExpression(userId);
    JsonNode existing = resultJson("cached");
    UUID sessionId = insertSession(userId, expressionId, "completed", existing);

    JsonNode stored = repository.saveResultJsonIfAbsent(sessionId, userId, resultJson("new"));

    assertThat(stored).isEqualTo(existing);
    JsonNode current =
        repository.findByIdForOwner(sessionId, userId).orElseThrow().session().resultJson();
    assertThat(current).isEqualTo(existing);
  }

  @Test
  void findResultContextScopesByOwnerAndIncludesSourceExpressionCoachAndTurns() throws Exception {
    UUID userId = insertUser("owner@example.com");
    UUID otherUserId = insertUser("other@example.com");
    UUID expressionId = insertExpression(userId);
    UUID sessionId = insertSession(userId, expressionId, "completed", resultJson("done"));
    insertOpeningTurn(sessionId);

    Optional<PracticeResultContext> context = repository.findResultContext(sessionId, userId);
    Optional<PracticeResultContext> otherUser =
        repository.findResultContext(sessionId, otherUserId);

    assertThat(context).isPresent();
    assertThat(context.get().originalSituation()).isEqualTo("doctor appointment");
    assertThat(context.get().selectedExpression()).isEqualTo("Could you repeat that?");
    assertThat(context.get().coachName()).isEqualTo("Mia");
    assertThat(context.get().turns()).hasSize(1);
    assertThat(otherUser).isEmpty();
  }

  private UUID insertUser(String email) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, email, is_onboarded) VALUES (?, ?, true)", id, email);
    return id;
  }

  private UUID insertExpression(UUID userId) {
    UUID analysisId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO analysis_requests (id, user_id, input_text, output_json, prompt_version)
        VALUES (?, ?, 'situation', '{}'::jsonb, 's07-v1')
        """,
        analysisId,
        userId);
    UUID expressionId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO expressions
          (id, user_id, source_type, analysis_request_id, original_situation)
        VALUES (?, ?, 'analysis', ?, 'doctor appointment')
        """,
        expressionId,
        userId,
        analysisId);
    UUID variantId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO expression_variants
          (id, expression_id, variant_order, tone_label, english_text)
        VALUES (?, ?, 1, 'polite', 'Could you repeat that?')
        """,
        variantId,
        expressionId);
    jdbcTemplate.update(
        "UPDATE expressions SET selected_variant_id = ? WHERE id = ?", variantId, expressionId);
    return expressionId;
  }

  private UUID insertSession(UUID userId, UUID expressionId, String status, JsonNode resultJson)
      throws Exception {
    UUID sessionId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO practice_sessions
          (id, user_id, expression_id, coach_id, status, planned_turns, result_json)
        VALUES (?, ?, ?, ?, ?, 3, ?::jsonb)
        """,
        sessionId,
        userId,
        expressionId,
        coachId,
        status,
        resultJson == null ? null : MAPPER.writeValueAsString(resultJson));
    return sessionId;
  }

  private void insertOpeningTurn(UUID sessionId) {
    jdbcTemplate.update(
        """
        INSERT INTO practice_turns
          (id, session_id, turn_number, speaker, text_content, feedback_shown)
        VALUES (?, ?, 1, 'coach', 'Let us practice.', false)
        """,
        UUID.randomUUID(),
        sessionId);
  }

  private static JsonNode resultJson(String encouragement) throws Exception {
    return MAPPER.readTree(
        """
        {
          "recommended_expressions": [
            {"english":"Could you repeat that?","tone_label":"polite","ipa":"/a/","korean_pronunciation":"could","pronunciation_tip":"short could","cultural_tip":"clarification"},
            {"english":"I want to make sure I understood.","tone_label":"careful","ipa":"/b/","korean_pronunciation":"want","pronunciation_tip":"link words","cultural_tip":"careful check"},
            {"english":"Can I say that back to you?","tone_label":"confirming","ipa":"/c/","korean_pronunciation":"can","pronunciation_tip":"light can","cultural_tip":"paraphrase"}
          ],
          "awkward_pairs": [],
          "pronunciation_focus_words": [],
          "coach_encouragement": "%s"
        }
        """
            .formatted(encouragement));
  }
}
