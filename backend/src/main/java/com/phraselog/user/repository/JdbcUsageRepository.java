package com.phraselog.user.repository;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JdbcTemplate-backed daily-usage counters (#52). */
public class JdbcUsageRepository implements UsageRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcUsageRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public int countRoleplaySessions(
      UUID userId, OffsetDateTime fromInclusive, OffsetDateTime toExclusive) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*)
              FROM practice_sessions
             WHERE user_id = ?
               AND status <> 'abandoned'
               AND started_at >= ?
               AND started_at < ?
            """,
            Integer.class,
            userId,
            fromInclusive,
            toExclusive);
    return count == null ? 0 : count;
  }

  @Override
  public int countAnalysisRequests(
      UUID userId, OffsetDateTime fromInclusive, OffsetDateTime toExclusive) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*)
              FROM analysis_requests
             WHERE user_id = ?
               AND created_at >= ?
               AND created_at < ?
            """,
            Integer.class,
            userId,
            fromInclusive,
            toExclusive);
    return count == null ? 0 : count;
  }
}
