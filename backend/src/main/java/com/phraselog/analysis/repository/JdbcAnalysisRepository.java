package com.phraselog.analysis.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.analysis.dto.AnalysisRequestRow;
import com.phraselog.analysis.dto.NewAnalysis;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcTemplate-based {@link AnalysisRepository}. {@code output_json} is bound/read as JSONB text
 * and {@code ip_address} as {@code inet}; ownership reads are scoped by user_id or session_token so
 * a not-owned row is indistinguishable from a missing one (the controller maps both to 404).
 */
public class JdbcAnalysisRepository implements AnalysisRepository {

  private static final String INSERT_SQL =
      """
      INSERT INTO analysis_requests
        (id, user_id, session_token, ip_address, input_text, output_json,
         prompt_version, ai_request_log_id, idempotency_key, created_at)
      VALUES (?, ?, ?, ?::inet, ?, ?::jsonb, ?, ?, ?, ?)
      """;

  private static final String SELECT_COLUMNS =
      "id, user_id, session_token, input_text, output_json, prompt_version, "
          + "ai_request_log_id, created_at";

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public JdbcAnalysisRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public AnalysisRequestRow insert(NewAnalysis analysis) {
    UUID id = UUID.randomUUID();
    OffsetDateTime createdAt = OffsetDateTime.now();
    String outputJsonText = serialize(analysis.outputJson());

    jdbcTemplate.update(
        connection -> {
          PreparedStatement ps = connection.prepareStatement(INSERT_SQL);
          setUuid(ps, 1, id);
          setUuid(ps, 2, analysis.userId());
          setNullableString(ps, 3, analysis.sessionToken());
          setNullableString(ps, 4, analysis.ipAddress());
          ps.setString(5, analysis.inputText());
          ps.setString(6, outputJsonText);
          ps.setString(7, analysis.promptVersion());
          setUuid(ps, 8, analysis.aiRequestLogId());
          setUuid(ps, 9, analysis.idempotencyKey());
          ps.setObject(10, createdAt);
          return ps;
        });

    return new AnalysisRequestRow(
        id,
        analysis.userId(),
        analysis.sessionToken(),
        analysis.inputText(),
        analysis.outputJson(),
        analysis.promptVersion(),
        analysis.aiRequestLogId(),
        createdAt);
  }

  @Override
  public Optional<AnalysisRequestRow> findByIdForOwner(UUID id, InternalAuthPrincipal principal) {
    if (principal.isAuthenticatedUser()) {
      return queryOne(
          "SELECT " + SELECT_COLUMNS + " FROM analysis_requests WHERE id = ? AND user_id = ?",
          id,
          UUID.fromString(principal.userId()));
    }
    return queryOne(
        "SELECT "
            + SELECT_COLUMNS
            + " FROM analysis_requests WHERE id = ? AND user_id IS NULL AND session_token = ?",
        id,
        principal.sessionToken());
  }

  @Override
  public Optional<AnalysisRequestRow> claimAnonymousAnalysis(
      UUID id, String sessionToken, UUID userId) {
    // 단일 원자적 UPDATE: 익명(user_id IS NULL)이고 session_token이 일치할 때만 소유권을 이전한다.
    // RETURNING으로 갱신된 행을 그대로 읽어, 조건 불일치(0행)는 빈 결과 → 호출자가 404로 매핑한다.
    return queryOne(
        "UPDATE analysis_requests SET user_id = ?, session_token = NULL"
            + " WHERE id = ? AND user_id IS NULL AND session_token = ?"
            + " RETURNING "
            + SELECT_COLUMNS,
        userId,
        id,
        sessionToken);
  }

  @Override
  public Optional<AnalysisRequestRow> findByCallerAndKey(
      InternalAuthPrincipal principal, UUID idempotencyKey) {
    if (principal.isAuthenticatedUser()) {
      return queryOne(
          "SELECT "
              + SELECT_COLUMNS
              + " FROM analysis_requests WHERE user_id = ? AND idempotency_key = ?",
          UUID.fromString(principal.userId()),
          idempotencyKey);
    }
    return queryOne(
        "SELECT "
            + SELECT_COLUMNS
            + " FROM analysis_requests"
            + " WHERE user_id IS NULL AND session_token = ? AND idempotency_key = ?",
        principal.sessionToken(),
        idempotencyKey);
  }

  @Override
  public Optional<UUID> findLogIdByCorrelation(UUID correlationId) {
    try {
      UUID logId =
          jdbcTemplate.queryForObject(
              // A correlation now holds one row per attempt (ADR-011), so the final attempt must
              // be selected explicitly — created_at alone cannot separate attempts written in the
              // same millisecond.
              "SELECT id FROM ai_request_logs WHERE request_correlation_id = ?"
                  + " AND is_final_attempt ORDER BY created_at DESC LIMIT 1",
              UUID.class,
              correlationId);
      return Optional.ofNullable(logId);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private Optional<AnalysisRequestRow> queryOne(String sql, Object... args) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject(sql, rowMapper(), args));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private RowMapper<AnalysisRequestRow> rowMapper() {
    return (ResultSet rs, int rowNum) ->
        new AnalysisRequestRow(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("session_token"),
            rs.getString("input_text"),
            parse(rs.getString("output_json")),
            rs.getString("prompt_version"),
            rs.getObject("ai_request_log_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class));
  }

  private JsonNode parse(String json) throws SQLException {
    try {
      return objectMapper.readTree(json);
    } catch (IOException e) {
      throw new SQLException("Failed to parse analysis_requests.output_json", e);
    }
  }

  private String serialize(JsonNode node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new DataIntegrityViolationException("Failed to serialize analysis output_json", e);
    }
  }

  private static void setUuid(PreparedStatement ps, int index, UUID value) throws SQLException {
    if (value != null) {
      ps.setObject(index, value, Types.OTHER);
    } else {
      ps.setNull(index, Types.OTHER);
    }
  }

  private static void setNullableString(PreparedStatement ps, int index, String value)
      throws SQLException {
    if (value != null) {
      ps.setString(index, value);
    } else {
      ps.setNull(index, Types.VARCHAR);
    }
  }
}
