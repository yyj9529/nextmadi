package com.phraselog.coach.repository;

import com.phraselog.coach.dto.CoachResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** JdbcTemplate-backed read of the seed coach catalog (#52). */
public class JdbcCoachRepository implements CoachRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcCoachRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public List<CoachResponse> findAll() {
    return jdbcTemplate.query(
        """
        SELECT id, slug, display_name, persona_summary, tts_voice_id
          FROM coach_profiles
         ORDER BY slug
        """,
        coachRowMapper());
  }

  @Override
  public Optional<CoachResponse> findById(UUID coachId) {
    try {
      CoachResponse coach =
          jdbcTemplate.queryForObject(
              """
              SELECT id, slug, display_name, persona_summary, tts_voice_id
                FROM coach_profiles
               WHERE id = ?
              """,
              coachRowMapper(),
              coachId);
      return Optional.of(coach);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private static RowMapper<CoachResponse> coachRowMapper() {
    return (rs, rowNum) ->
        new CoachResponse(
            rs.getObject("id", UUID.class),
            rs.getString("slug"),
            rs.getString("display_name"),
            rs.getString("persona_summary"),
            rs.getString("tts_voice_id"));
  }
}
