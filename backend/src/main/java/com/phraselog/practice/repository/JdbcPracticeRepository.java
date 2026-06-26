package com.phraselog.practice.repository;

import com.phraselog.practice.dto.NewPracticeSession;
import com.phraselog.practice.dto.PracticeSessionRow;
import com.phraselog.practice.dto.PracticeSessionWithTurns;
import com.phraselog.practice.dto.PracticeTurnRow;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
      "id, user_id, expression_id, coach_id, status, planned_turns, started_at, ended_at";

  private final JdbcTemplate jdbcTemplate;

  public JdbcPracticeRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
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

  private static RowMapper<PracticeSessionRow> sessionRowMapper() {
    return (ResultSet rs, int rowNum) ->
        new PracticeSessionRow(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("expression_id", UUID.class),
            rs.getObject("coach_id", UUID.class),
            rs.getString("status"),
            rs.getInt("planned_turns"),
            rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("ended_at", OffsetDateTime.class));
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
}
