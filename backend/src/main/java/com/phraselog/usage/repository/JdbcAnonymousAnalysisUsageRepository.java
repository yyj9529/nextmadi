package com.phraselog.usage.repository;

import java.sql.Date;
import java.time.LocalDate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcAnonymousAnalysisUsageRepository implements AnonymousAnalysisUsageRepository {

  private static final String RESERVE_SQL =
      """
      WITH reserved AS (
        INSERT INTO anonymous_analysis_usage (ip_address, usage_date, count, updated_at)
        VALUES (?::inet, ?, 1, now())
        ON CONFLICT (ip_address, usage_date)
        DO UPDATE SET count = anonymous_analysis_usage.count + 1,
                      updated_at = now()
        WHERE anonymous_analysis_usage.count < ?
        RETURNING count
      )
      SELECT count(*) FROM reserved
      """;

  private static final String RELEASE_SQL =
      """
      UPDATE anonymous_analysis_usage
      SET count = GREATEST(count - 1, 0),
          updated_at = now()
      WHERE ip_address = ?::inet
        AND usage_date = ?
        AND count > 0
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcAnonymousAnalysisUsageRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public boolean tryReserve(String ipAddress, LocalDate usageDate, int limit) {
    Integer reserved =
        jdbcTemplate.queryForObject(
            RESERVE_SQL, Integer.class, ipAddress, Date.valueOf(usageDate), limit);
    return reserved != null && reserved == 1;
  }

  @Override
  public void release(String ipAddress, LocalDate usageDate) {
    jdbcTemplate.update(RELEASE_SQL, ipAddress, Date.valueOf(usageDate));
  }

  int currentCount(String ipAddress, LocalDate usageDate) {
    try {
      Integer count =
          jdbcTemplate.queryForObject(
              """
              SELECT count
              FROM anonymous_analysis_usage
              WHERE ip_address = ?::inet
                AND usage_date = ?
              """,
              Integer.class,
              ipAddress,
              Date.valueOf(usageDate));
      return count == null ? 0 : count;
    } catch (EmptyResultDataAccessException e) {
      return 0;
    }
  }
}
