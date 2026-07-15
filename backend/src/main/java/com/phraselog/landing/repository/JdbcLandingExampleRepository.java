package com.phraselog.landing.repository;

import com.phraselog.landing.dto.LandingExampleResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** JdbcTemplate-backed random read of the active landing example pool (#32). */
public class JdbcLandingExampleRepository implements LandingExampleRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcLandingExampleRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public List<LandingExampleResponse> findRandomActive(int limit) {
    // v1: 활성 풀 전체에서 무작위 표본. 회전(CTR 기반)은 v1.1+로 유보(s01, PRD 4.3).
    return jdbcTemplate.query(
        """
        SELECT id, korean_text
          FROM landing_examples
         WHERE is_active = true
         ORDER BY random()
         LIMIT ?
        """,
        landingExampleRowMapper(),
        limit);
  }

  private static RowMapper<LandingExampleResponse> landingExampleRowMapper() {
    return (rs, rowNum) ->
        new LandingExampleResponse(rs.getObject("id", UUID.class), rs.getString("korean_text"));
  }
}
