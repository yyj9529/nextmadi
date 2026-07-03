package com.phraselog.practice.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.practice.dto.NewPracticeSession;
import com.phraselog.practice.dto.PracticeResultContext;
import com.phraselog.practice.dto.PracticeResultTurn;
import com.phraselog.practice.dto.PracticeSessionRow;
import com.phraselog.practice.dto.PracticeSessionWithTurns;
import com.phraselog.practice.dto.PracticeTurnRow;
import java.io.IOException;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * JdbcTemplate-backed {@link PracticeRepository} (#59). Session and turn inserts run in one
 * transaction; ownership reads are scoped by {@code user_id} so a not-owned session is
 * indistinguishable from a missing one (the service maps both to 404).
 */
public class JdbcPracticeRepository implements PracticeRepository {

  private static final String SESSION_COLUMNS =
      "id, user_id, expression_id, coach_id, status, planned_turns, started_at, ended_at,"
          + " result_json";

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public JdbcPracticeRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public PracticeSessionWithTurns insertSessionWithOpeningTurn(NewPracticeSession session) {
    UUID sessionId = UUID.randomUUID();
    OffsetDateTime startedAt = OffsetDateTime.now();

    jdbcTemplate.update(
        """
        INSERT INTO practice_sessions
          (id, user_id, expression_id, coach_id, status, planned_turns, started_at, idempotency_key)
        VALUES (?, ?, ?, ?, 'active', ?, ?, ?)
        """,
        sessionId,
        session.userId(),
        session.expressionId(),
        session.coachId(),
        session.plannedTurns(),
        startedAt,
        session.idempotencyKey());

    UUID turnId = UUID.randomUUID();
    OffsetDateTime turnCreatedAt = OffsetDateTime.now();
    jdbcTemplate.update(
        """
        INSERT INTO practice_turns
          (id, session_id, turn_number, speaker, text_content, feedback_shown, created_at)
        VALUES (?, ?, 1, 'coach', ?, false, ?)
        """,
        turnId,
        sessionId,
        session.openingText(),
        turnCreatedAt);

    PracticeSessionRow sessionRow =
        new PracticeSessionRow(
            sessionId,
            session.userId(),
            session.expressionId(),
            session.coachId(),
            "active",
            session.plannedTurns(),
            startedAt,
            null,
            null);
    PracticeTurnRow turnRow =
        new PracticeTurnRow(
            turnId, sessionId, 1, "coach", session.openingText(), null, null, false, turnCreatedAt);
    return new PracticeSessionWithTurns(sessionRow, List.of(turnRow));
  }

  @Override
  public Optional<PracticeSessionWithTurns> findByUserAndKey(UUID userId, UUID idempotencyKey) {
    return findSession(
            "SELECT "
                + SESSION_COLUMNS
                + " FROM practice_sessions WHERE user_id = ? AND idempotency_key = ?",
            userId,
            idempotencyKey)
        .map(this::withTurns);
  }

  @Override
  public Optional<PracticeSessionWithTurns> findByIdForOwner(UUID sessionId, UUID userId) {
    return findSession(
            "SELECT " + SESSION_COLUMNS + " FROM practice_sessions WHERE id = ? AND user_id = ?",
            sessionId,
            userId)
        .map(this::withTurns);
  }

  @Override
  public Optional<PracticeResultContext> findResultContext(UUID sessionId, UUID userId) {
    try {
      PracticeResultContextBase base =
          jdbcTemplate.queryForObject(
              """
              SELECT ps.id, ps.user_id, ps.expression_id, ps.coach_id, ps.status,
                     ps.planned_turns, ps.result_json,
                     e.original_situation,
                     ev.english_text AS selected_expression,
                     cp.display_name AS coach_name,
                     cp.persona_summary AS coach_persona
                FROM practice_sessions ps
                LEFT JOIN expressions e ON ps.expression_id = e.id
                LEFT JOIN expression_variants ev ON e.selected_variant_id = ev.id
                JOIN coach_profiles cp ON ps.coach_id = cp.id
               WHERE ps.id = ? AND ps.user_id = ?
              """,
              resultContextBaseMapper(),
              sessionId,
              userId);
      return Optional.of(
          new PracticeResultContext(
              base.sessionId(),
              base.userId(),
              base.expressionId(),
              base.coachId(),
              base.status(),
              base.plannedTurns(),
              base.resultJson(),
              base.originalSituation(),
              base.selectedExpression(),
              base.coachName(),
              base.coachPersona(),
              resultTurns(sessionId)));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public JsonNode saveResultJsonIfAbsent(UUID sessionId, UUID userId, JsonNode resultJson) {
    String serialized = serialize(resultJson);
    try {
      String stored =
          jdbcTemplate.queryForObject(
              """
              UPDATE practice_sessions
                 SET result_json = ?::jsonb
               WHERE id = ? AND user_id = ? AND result_json IS NULL
               RETURNING result_json
              """,
              String.class,
              serialized,
              sessionId,
              userId);
      return parseJson(stored);
    } catch (EmptyResultDataAccessException raceOrMissing) {
      return findResultContext(sessionId, userId)
          .map(PracticeResultContext::resultJson)
          .orElseThrow(
              () ->
                  new DataIntegrityViolationException(
                      "Practice session disappeared while saving result_json"));
    }
  }

  private Optional<PracticeSessionRow> findSession(String sql, Object... args) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject(sql, sessionRowMapper(), args));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private PracticeSessionWithTurns withTurns(PracticeSessionRow session) {
    List<PracticeTurnRow> turns =
        jdbcTemplate.query(
            """
            SELECT id, session_id, turn_number, speaker, text_content, tts_audio_cache_id,
                   stt_confidence, feedback_shown, created_at
              FROM practice_turns
             WHERE session_id = ?
             ORDER BY turn_number
            """,
            turnRowMapper(),
            session.id());
    return new PracticeSessionWithTurns(session, turns);
  }

  private RowMapper<PracticeSessionRow> sessionRowMapper() {
    return (ResultSet rs, int rowNum) ->
        new PracticeSessionRow(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("expression_id", UUID.class),
            rs.getObject("coach_id", UUID.class),
            rs.getString("status"),
            rs.getInt("planned_turns"),
            rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("ended_at", OffsetDateTime.class),
            parseJson(rs.getString("result_json")));
  }

  private static RowMapper<PracticeTurnRow> turnRowMapper() {
    return (ResultSet rs, int rowNum) ->
        new PracticeTurnRow(
            rs.getObject("id", UUID.class),
            rs.getObject("session_id", UUID.class),
            rs.getInt("turn_number"),
            rs.getString("speaker"),
            rs.getString("text_content"),
            rs.getObject("tts_audio_cache_id", UUID.class),
            rs.getBigDecimal("stt_confidence"),
            rs.getBoolean("feedback_shown"),
            rs.getObject("created_at", OffsetDateTime.class));
  }

  private List<PracticeResultTurn> resultTurns(UUID sessionId) {
    return jdbcTemplate.query(
        """
        SELECT turn_number, speaker, text_content, stt_confidence, feedback_shown, feedback_content
          FROM practice_turns
         WHERE session_id = ?
         ORDER BY turn_number
        """,
        (rs, rowNum) ->
            new PracticeResultTurn(
                rs.getInt("turn_number"),
                rs.getString("speaker"),
                rs.getString("text_content"),
                rs.getBigDecimal("stt_confidence"),
                rs.getBoolean("feedback_shown"),
                parseJson(rs.getString("feedback_content"))),
        sessionId);
  }

  private RowMapper<PracticeResultContextBase> resultContextBaseMapper() {
    return (ResultSet rs, int rowNum) ->
        new PracticeResultContextBase(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("expression_id", UUID.class),
            rs.getObject("coach_id", UUID.class),
            rs.getString("status"),
            rs.getInt("planned_turns"),
            parseJson(rs.getString("result_json")),
            rs.getString("original_situation"),
            rs.getString("selected_expression"),
            rs.getString("coach_name"),
            rs.getString("coach_persona"));
  }

  private JsonNode parseJson(String json) {
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readTree(json);
    } catch (IOException e) {
      throw new DataIntegrityViolationException("Failed to parse practice JSONB", e);
    }
  }

  private String serialize(JsonNode node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new DataIntegrityViolationException("Failed to serialize roleplay result_json", e);
    }
  }

  private record PracticeResultContextBase(
      UUID sessionId,
      UUID userId,
      UUID expressionId,
      UUID coachId,
      String status,
      int plannedTurns,
      JsonNode resultJson,
      String originalSituation,
      String selectedExpression,
      String coachName,
      String coachPersona) {}
}
