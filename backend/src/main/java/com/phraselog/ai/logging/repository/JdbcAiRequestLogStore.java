package com.phraselog.ai.logging.repository;

import com.phraselog.ai.logging.dto.AiRequestLogEntry;
import java.sql.PreparedStatement;
import java.sql.Types;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcTemplate-based {@link AiRequestLogStore}. Inserts one row into {@code ai_request_logs};
 * {@code id} and {@code created_at} fall to their column defaults.
 *
 * <p>Nullable columns and the {@code uuid} columns are bound with explicit JDBC types so PostgreSQL
 * does not have to infer the type of a {@code null} parameter.
 */
public class JdbcAiRequestLogStore implements AiRequestLogStore {

  private static final String INSERT_SQL =
      """
      INSERT INTO ai_request_logs
        (user_id, feature_name, model_name, prompt_version,
         input_tokens, output_tokens, latency_ms, estimated_cost_usd,
         status, error_code, request_correlation_id,
         attempt_group_id, attempt_number, is_final_attempt)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcAiRequestLogStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void save(AiRequestLogEntry entry) {
    jdbcTemplate.update(
        connection -> {
          PreparedStatement ps = connection.prepareStatement(INSERT_SQL);
          setUuid(ps, 1, entry.userId());
          ps.setString(2, entry.feature().wireName());
          ps.setString(3, entry.modelName());
          setNullableString(ps, 4, entry.promptVersion());
          setNullableInt(ps, 5, entry.inputTokens());
          setNullableInt(ps, 6, entry.outputTokens());
          ps.setLong(7, entry.latencyMs());
          if (entry.estimatedCostUsd() != null) {
            ps.setBigDecimal(8, entry.estimatedCostUsd());
          } else {
            ps.setNull(8, Types.NUMERIC);
          }
          ps.setString(9, entry.status().wireName());
          setNullableString(
              ps, 10, entry.errorCode() == null ? null : entry.errorCode().wireName());
          setUuid(ps, 11, entry.requestCorrelationId());
          setUuid(ps, 12, entry.attemptGroupId());
          ps.setInt(13, entry.attemptNumber());
          ps.setBoolean(14, entry.isFinalAttempt());
          return ps;
        });
  }

  private static void setUuid(PreparedStatement ps, int index, java.util.UUID value)
      throws java.sql.SQLException {
    if (value != null) {
      ps.setObject(index, value, Types.OTHER);
    } else {
      ps.setNull(index, Types.OTHER);
    }
  }

  private static void setNullableString(PreparedStatement ps, int index, String value)
      throws java.sql.SQLException {
    if (value != null) {
      ps.setString(index, value);
    } else {
      ps.setNull(index, Types.VARCHAR);
    }
  }

  private static void setNullableInt(PreparedStatement ps, int index, Integer value)
      throws java.sql.SQLException {
    if (value != null) {
      ps.setInt(index, value);
    } else {
      ps.setNull(index, Types.INTEGER);
    }
  }
}
