package com.phraselog.usage.repository;

import java.sql.Date;
import java.time.LocalDate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link JdbcAnonymousAnalysisUsageRepository} 와 같은 예약/해제 SQL 을 쓰되 대상 테이블만 다르다. 두 번째 인스턴스이므로 공통화하지
 * 않았다 — 세 번째가 생기면 테이블명을 주입받는 하나로 합칠 것.
 */
public class JdbcAnonymousTranscriptionUsageRepository
    implements AnonymousTranscriptionUsageRepository {

  // count < ? 조건이 UPDATE 에 붙어 있어, 한도에 도달하면 아무 행도 반환되지 않는다.
  // 원자적이라 동시 요청이 한도를 넘겨 통과하지 못한다.
  private static final String RESERVE_SQL =
      """
      WITH reserved AS (
        INSERT INTO anonymous_transcription_usage (ip_address, usage_date, count, updated_at)
        VALUES (?::inet, ?, 1, now())
        ON CONFLICT (ip_address, usage_date)
        DO UPDATE SET count = anonymous_transcription_usage.count + 1,
                      updated_at = now()
        WHERE anonymous_transcription_usage.count < ?
        RETURNING count
      )
      SELECT count(*) FROM reserved
      """;

  private static final String RELEASE_SQL =
      """
      UPDATE anonymous_transcription_usage
      SET count = GREATEST(count - 1, 0),
          updated_at = now()
      WHERE ip_address = ?::inet
        AND usage_date = ?
        AND count > 0
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcAnonymousTranscriptionUsageRepository(JdbcTemplate jdbcTemplate) {
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
              FROM anonymous_transcription_usage
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
