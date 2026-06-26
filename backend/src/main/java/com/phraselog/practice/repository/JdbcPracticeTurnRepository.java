package com.phraselog.practice.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.practice.dto.PracticeTurnResponse;
import com.phraselog.practice.dto.SubmitPracticeTurnResponse;
import com.phraselog.practice.dto.TurnFeedbackResponse;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/** JdbcTemplate implementation for the S12 turn state machine (#60). */
public class JdbcPracticeTurnRepository implements PracticeTurnRepository {

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final ObjectMapper objectMapper;

  public JdbcPracticeTurnRepository(
      JdbcTemplate jdbcTemplate,
      TransactionTemplate transactionTemplate,
      ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<PracticeSessionContext> findSessionForUser(UUID sessionId, UUID userId) {
    try {
      PracticeSessionContextBase base =
          jdbcTemplate.queryForObject(
              """
              SELECT ps.id, ps.user_id, ps.planned_turns, ps.status,
                     e.original_situation,
                     ev.english_text AS selected_expression,
                     cp.display_name AS coach_name,
                     cp.persona_summary AS coach_persona,
                     cp.tts_voice_id
              FROM practice_sessions ps
              LEFT JOIN expressions e ON ps.expression_id = e.id
              LEFT JOIN expression_variants ev ON e.selected_variant_id = ev.id
              JOIN coach_profiles cp ON ps.coach_id = cp.id
              WHERE ps.id = ? AND ps.user_id = ?
              """,
              baseMapper(),
              sessionId,
              userId);
      int consumedUserTurns =
          jdbcTemplate.queryForObject(
              "SELECT count(*) FROM practice_turns WHERE session_id = ? AND speaker = 'user'",
              Integer.class,
              sessionId);
      return Optional.of(
          new PracticeSessionContext(
              base.sessionId(),
              base.userId(),
              base.plannedTurns(),
              consumedUserTurns,
              base.status(),
              base.originalSituation(),
              base.selectedExpression(),
              base.coachName(),
              base.coachPersona(),
              base.ttsVoiceId(),
              listTurns(sessionId)));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<SubmitPracticeTurnResponse> findReplay(UUID sessionId, UUID idempotencyKey) {
    try {
      TurnRequestReplay replay =
          jdbcTemplate.queryForObject(
              """
              SELECT ptr.status, ptr.retry_prompt, ptr.user_turn_id, ptr.coach_turn_id, ps.status AS session_status
              FROM practice_turn_requests ptr
              JOIN practice_sessions ps ON ptr.session_id = ps.id
              WHERE ptr.session_id = ? AND ptr.idempotency_key = ?
              """,
              (rs, rowNum) ->
                  new TurnRequestReplay(
                      rs.getString("status"),
                      rs.getString("retry_prompt"),
                      rs.getObject("user_turn_id", UUID.class),
                      rs.getObject("coach_turn_id", UUID.class),
                      rs.getString("session_status")),
              sessionId,
              idempotencyKey);
      if ("no_turn".equals(replay.status())) {
        return Optional.of(SubmitPracticeTurnResponse.notConsumed(replay.retryPrompt()));
      }
      if (!"completed".equals(replay.status())) {
        return Optional.empty();
      }
      PracticeTurnResponse userTurn = findTurn(replay.userTurnId()).orElseThrow();
      PracticeTurnResponse coachTurn = findTurn(replay.coachTurnId()).orElseThrow();
      return Optional.of(
          SubmitPracticeTurnResponse.consumed(
              userTurn, coachTurn, feedbackForTurn(replay.userTurnId()), replay.sessionStatus()));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public PracticeTurnRequestRow reserveRequest(
      UUID sessionId, UUID idempotencyKey, UUID requestCorrelationId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO practice_turn_requests
          (id, session_id, idempotency_key, request_correlation_id, status, created_at, updated_at)
        VALUES (?, ?, ?, ?, 'processing', now(), now())
        """,
        id,
        sessionId,
        idempotencyKey,
        requestCorrelationId);
    return new PracticeTurnRequestRow(id, requestCorrelationId);
  }

  @Override
  public SubmitPracticeTurnResponse appendTurnPair(
      PracticeTurnRequestRow request, AppendTurnPairCommand command) {
    return transactionTemplate.execute(
        ignored -> {
          UUID sessionId = sessionIdForRequest(request.id());
          SessionLock lock =
              jdbcTemplate.queryForObject(
                  "SELECT planned_turns, status FROM practice_sessions WHERE id = ? FOR UPDATE",
                  (rs, rowNum) ->
                      new SessionLock(rs.getInt("planned_turns"), rs.getString("status")),
                  sessionId);
          if (!"active".equals(lock.status())) {
            throw new DataIntegrityViolationException("Practice session is not active.");
          }

          int nextTurn =
              jdbcTemplate.queryForObject(
                  "SELECT COALESCE(MAX(turn_number), 0) + 1 FROM practice_turns WHERE session_id = ?",
                  Integer.class,
                  sessionId);
          OffsetDateTime now = OffsetDateTime.now();
          UUID userTurnId = UUID.randomUUID();
          UUID coachTurnId = UUID.randomUUID();
          insertTurn(
              userTurnId,
              sessionId,
              nextTurn,
              "user",
              command.userText(),
              null,
              command.sttConfidence(),
              command.feedbackShown(),
              command.feedbackContent(),
              now);
          insertTurn(
              coachTurnId,
              sessionId,
              nextTurn + 1,
              "coach",
              command.coachText(),
              command.ttsAudioCacheId(),
              null,
              false,
              null,
              now.plusNanos(1_000_000));

          int consumedUserTurns =
              jdbcTemplate.queryForObject(
                  "SELECT count(*) FROM practice_turns WHERE session_id = ? AND speaker = 'user'",
                  Integer.class,
                  sessionId);
          String sessionStatus = consumedUserTurns >= lock.plannedTurns() ? "completed" : "active";
          if ("completed".equals(sessionStatus)) {
            jdbcTemplate.update(
                "UPDATE practice_sessions SET status = 'completed', ended_at = now() WHERE id = ?",
                sessionId);
          }
          jdbcTemplate.update(
              """
              UPDATE practice_turn_requests
              SET status = 'completed', user_turn_id = ?, coach_turn_id = ?, updated_at = now()
              WHERE id = ?
              """,
              userTurnId,
              coachTurnId,
              request.id());

          PracticeTurnResponse userTurn =
              new PracticeTurnResponse(
                  userTurnId,
                  nextTurn,
                  "user",
                  command.userText(),
                  null,
                  command.sttConfidence(),
                  command.feedbackShown(),
                  now);
          PracticeTurnResponse coachTurn =
              new PracticeTurnResponse(
                  coachTurnId,
                  nextTurn + 1,
                  "coach",
                  command.coachText(),
                  command.coachAudioUrl(),
                  null,
                  false,
                  now.plusNanos(1_000_000));
          return SubmitPracticeTurnResponse.consumed(
              userTurn, coachTurn, toFeedback(command.feedbackContent()), sessionStatus);
        });
  }

  @Override
  public SubmitPracticeTurnResponse completeWithoutTurn(
      PracticeTurnRequestRow request, String retryPrompt) {
    jdbcTemplate.update(
        """
        UPDATE practice_turn_requests
        SET status = 'no_turn', retry_prompt = ?, updated_at = now()
        WHERE id = ?
        """,
        retryPrompt,
        request.id());
    return SubmitPracticeTurnResponse.notConsumed(retryPrompt);
  }

  @Override
  public void markFailed(UUID requestId) {
    jdbcTemplate.update(
        "UPDATE practice_turn_requests SET status = 'failed', updated_at = now() WHERE id = ?",
        requestId);
  }

  private UUID sessionIdForRequest(UUID requestId) {
    return jdbcTemplate.queryForObject(
        "SELECT session_id FROM practice_turn_requests WHERE id = ?", UUID.class, requestId);
  }

  private void insertTurn(
      UUID id,
      UUID sessionId,
      int turnNumber,
      String speaker,
      String textContent,
      UUID ttsAudioCacheId,
      java.math.BigDecimal sttConfidence,
      boolean feedbackShown,
      JsonNode feedbackContent,
      OffsetDateTime createdAt) {
    String feedbackJson = feedbackContent == null ? null : serialize(feedbackContent);
    jdbcTemplate.update(
        connection -> {
          PreparedStatement ps =
              connection.prepareStatement(
                  """
                  INSERT INTO practice_turns
                    (id, session_id, turn_number, speaker, text_content, tts_audio_cache_id,
                     stt_confidence, feedback_shown, feedback_content, created_at)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                  """);
          ps.setObject(1, id, Types.OTHER);
          ps.setObject(2, sessionId, Types.OTHER);
          ps.setInt(3, turnNumber);
          ps.setString(4, speaker);
          ps.setString(5, textContent);
          setUuid(ps, 6, ttsAudioCacheId);
          ps.setBigDecimal(7, sttConfidence);
          ps.setBoolean(8, feedbackShown);
          ps.setString(9, feedbackJson);
          ps.setObject(10, createdAt);
          return ps;
        });
  }

  private List<PracticeTurnResponse> listTurns(UUID sessionId) {
    return jdbcTemplate.query(
        """
        SELECT id, turn_number, speaker, text_content, stt_confidence, feedback_shown, created_at
        FROM practice_turns
        WHERE session_id = ?
        ORDER BY turn_number ASC
        """,
        turnMapper(),
        sessionId);
  }

  private Optional<PracticeTurnResponse> findTurn(UUID turnId) {
    if (turnId == null) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(
          jdbcTemplate.queryForObject(
              """
              SELECT id, turn_number, speaker, text_content, stt_confidence, feedback_shown, created_at
              FROM practice_turns
              WHERE id = ?
              """,
              turnMapper(),
              turnId));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private TurnFeedbackResponse feedbackForTurn(UUID turnId) {
    if (turnId == null) {
      return null;
    }
    try {
      String json =
          jdbcTemplate.queryForObject(
              "SELECT feedback_content FROM practice_turns WHERE id = ?", String.class, turnId);
      return json == null ? null : toFeedback(parse(json));
    } catch (EmptyResultDataAccessException e) {
      return null;
    }
  }

  private RowMapper<PracticeTurnResponse> turnMapper() {
    return (rs, rowNum) ->
        new PracticeTurnResponse(
            rs.getObject("id", UUID.class),
            rs.getInt("turn_number"),
            rs.getString("speaker"),
            rs.getString("text_content"),
            null,
            rs.getBigDecimal("stt_confidence"),
            rs.getBoolean("feedback_shown"),
            rs.getObject("created_at", OffsetDateTime.class));
  }

  private RowMapper<PracticeSessionContextBase> baseMapper() {
    return (rs, rowNum) ->
        new PracticeSessionContextBase(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getInt("planned_turns"),
            rs.getString("status"),
            rs.getString("original_situation"),
            rs.getString("selected_expression"),
            rs.getString("coach_name"),
            rs.getString("coach_persona"),
            rs.getString("tts_voice_id"));
  }

  private TurnFeedbackResponse toFeedback(JsonNode json) {
    if (json == null || !json.path("show_feedback").asBoolean(false)) {
      return null;
    }
    return new TurnFeedbackResponse(
        true,
        json.hasNonNull("natural_alternative") ? json.get("natural_alternative").asText() : null,
        json.hasNonNull("korean_comment") ? json.get("korean_comment").asText() : null);
  }

  private JsonNode parse(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (IOException e) {
      throw new DataIntegrityViolationException(
          "Failed to parse practice_turns.feedback_content", e);
    }
  }

  private String serialize(JsonNode node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new DataIntegrityViolationException("Failed to serialize feedback_content", e);
    }
  }

  private static void setUuid(PreparedStatement ps, int index, UUID value) throws SQLException {
    if (value != null) {
      ps.setObject(index, value, Types.OTHER);
    } else {
      ps.setNull(index, Types.OTHER);
    }
  }

  private record PracticeSessionContextBase(
      UUID sessionId,
      UUID userId,
      int plannedTurns,
      String status,
      String originalSituation,
      String selectedExpression,
      String coachName,
      String coachPersona,
      String ttsVoiceId) {}

  private record SessionLock(int plannedTurns, String status) {}

  private record TurnRequestReplay(
      String status, String retryPrompt, UUID userTurnId, UUID coachTurnId, String sessionStatus) {}
}
