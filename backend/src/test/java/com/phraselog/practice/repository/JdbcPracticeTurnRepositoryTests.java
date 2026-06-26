package com.phraselog.practice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.practice.dto.SubmitPracticeTurnResponse;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcPracticeTurnRepositoryTests {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("phraselog_test")
          .withUsername("phraselog")
          .withPassword("phraselog");

  private static DataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private JdbcPracticeTurnRepository repository;
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
    repository =
        new JdbcPracticeTurnRepository(
            jdbcTemplate,
            new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)),
            new ObjectMapper());
    coachId =
        jdbcTemplate.queryForObject("SELECT id FROM coach_profiles WHERE slug = 'mia'", UUID.class);
  }

  @Test
  void appendTurnPairUsesNextTurnNumbersAndReplaysCompletedRequest() {
    UUID userId = insertUser("owner@example.com");
    UUID expressionId = insertExpression(userId);
    UUID sessionId = insertSession(userId, expressionId, 3);
    insertOpeningTurn(sessionId);
    UUID idempotencyKey = UUID.randomUUID();
    PracticeTurnRequestRow request =
        repository.reserveRequest(sessionId, idempotencyKey, UUID.randomUUID());

    SubmitPracticeTurnResponse response =
        repository.appendTurnPair(
            request,
            new AppendTurnPairCommand(
                "Could you repeat that?", null, "Sure, I can repeat it.", null, null, false, null));

    assertThat(response.turnConsumed()).isTrue();
    assertThat(response.userTurn().turnNumber()).isEqualTo(2);
    assertThat(response.coachTurn().turnNumber()).isEqualTo(3);
    assertThat(response.sessionStatus()).isEqualTo("active");

    Optional<SubmitPracticeTurnResponse> replay = repository.findReplay(sessionId, idempotencyKey);
    assertThat(replay).isPresent();
    assertThat(replay.get().userTurn().id()).isEqualTo(response.userTurn().id());
    assertThat(replay.get().coachTurn().id()).isEqualTo(response.coachTurn().id());
  }

  @Test
  void finalConsumedTurnMarksSessionCompleted() {
    UUID userId = insertUser("owner@example.com");
    UUID expressionId = insertExpression(userId);
    UUID sessionId = insertSession(userId, expressionId, 3);
    insertOpeningTurn(sessionId);
    insertPriorConsumedTurnPair(sessionId, 2);
    insertPriorConsumedTurnPair(sessionId, 4);
    PracticeTurnRequestRow request =
        repository.reserveRequest(sessionId, UUID.randomUUID(), UUID.randomUUID());

    SubmitPracticeTurnResponse response =
        repository.appendTurnPair(
            request,
            new AppendTurnPairCommand(
                "Thank you.", null, "You're welcome.", null, null, false, null));

    assertThat(response.sessionStatus()).isEqualTo("completed");
    String storedStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM practice_sessions WHERE id = ?", String.class, sessionId);
    assertThat(storedStatus).isEqualTo("completed");
  }

  @Test
  void findSessionForUserScopesByOwner() {
    UUID ownerId = insertUser("owner@example.com");
    UUID otherUserId = insertUser("other@example.com");
    UUID expressionId = insertExpression(ownerId);
    UUID sessionId = insertSession(ownerId, expressionId, 3);
    insertOpeningTurn(sessionId);

    assertThat(repository.findSessionForUser(sessionId, ownerId)).isPresent();
    assertThat(repository.findSessionForUser(sessionId, otherUserId)).isEmpty();
  }

  @Test
  void reserveRequestStoresCorrelationIdAndRejectsDuplicateIdempotencyKey() {
    UUID userId = insertUser("owner@example.com");
    UUID expressionId = insertExpression(userId);
    UUID sessionId = insertSession(userId, expressionId, 3);
    UUID idempotencyKey = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();

    repository.reserveRequest(sessionId, idempotencyKey, correlationId);

    UUID storedCorrelationId =
        jdbcTemplate.queryForObject(
            """
            SELECT request_correlation_id
            FROM practice_turn_requests
            WHERE session_id = ? AND idempotency_key = ?
            """,
            UUID.class,
            sessionId,
            idempotencyKey);
    assertThat(storedCorrelationId).isEqualTo(correlationId);
    assertThatThrownBy(
            () -> repository.reserveRequest(sessionId, idempotencyKey, UUID.randomUUID()))
        .isInstanceOf(DuplicateKeyException.class);
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
          (id, expression_id, variant_order, english_text)
        VALUES (?, ?, 1, 'Could you repeat that?')
        """,
        variantId,
        expressionId);
    jdbcTemplate.update(
        "UPDATE expressions SET selected_variant_id = ? WHERE id = ?", variantId, expressionId);
    return expressionId;
  }

  private UUID insertSession(UUID userId, UUID expressionId, int plannedTurns) {
    UUID sessionId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO practice_sessions
          (id, user_id, expression_id, coach_id, status, planned_turns)
        VALUES (?, ?, ?, ?, 'active', ?)
        """,
        sessionId,
        userId,
        expressionId,
        coachId,
        plannedTurns);
    return sessionId;
  }

  private void insertOpeningTurn(UUID sessionId) {
    jdbcTemplate.update(
        """
        INSERT INTO practice_turns
          (id, session_id, turn_number, speaker, text_content)
        VALUES (?, ?, 1, 'coach', 'Let us practice.')
        """,
        UUID.randomUUID(),
        sessionId);
  }

  private void insertPriorConsumedTurnPair(UUID sessionId, int userTurnNumber) {
    jdbcTemplate.update(
        """
        INSERT INTO practice_turns
          (id, session_id, turn_number, speaker, text_content)
        VALUES (?, ?, ?, 'user', 'Could you repeat that?')
        """,
        UUID.randomUUID(),
        sessionId,
        userTurnNumber);
    jdbcTemplate.update(
        """
        INSERT INTO practice_turns
          (id, session_id, turn_number, speaker, text_content)
        VALUES (?, ?, ?, 'coach', 'Sure, tell me more.')
        """,
        UUID.randomUUID(),
        sessionId,
        userTurnNumber + 1);
  }
}
