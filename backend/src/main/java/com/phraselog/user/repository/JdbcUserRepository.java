package com.phraselog.user.repository;

import com.phraselog.user.dto.UserResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

/** JdbcTemplate-backed persistence for the current-user profile (#52). */
public class JdbcUserRepository implements UserRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcUserRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<UserResponse> findById(UUID userId) {
    try {
      UserResponse user =
          jdbcTemplate.queryForObject(
              """
              SELECT id, email, display_name, selected_coach_id, is_onboarded,
                     created_at, scheduled_deletion_at
                FROM users
               WHERE id = ? AND deleted_at IS NULL
              """,
              userRowMapper(),
              userId);
      return Optional.of(user);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public boolean coachExists(UUID coachId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM coach_profiles WHERE id = ?", Integer.class, coachId);
    return count != null && count > 0;
  }

  @Override
  @Transactional
  public Optional<UserResponse> update(
      UUID userId, String displayName, UUID selectedCoachId, boolean setOnboardedTrue) {
    List<String> assignments = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    if (displayName != null) {
      assignments.add("display_name = ?");
      args.add(displayName);
    }
    if (selectedCoachId != null) {
      assignments.add("selected_coach_id = ?");
      args.add(selectedCoachId);
    }
    if (setOnboardedTrue) {
      assignments.add("is_onboarded = true");
    }

    if (assignments.isEmpty()) {
      // No-op patch: return the current row unchanged.
      return findById(userId);
    }

    args.add(userId);
    int updated =
        jdbcTemplate.update(
            "UPDATE users SET "
                + String.join(", ", assignments)
                + " WHERE id = ? AND deleted_at IS NULL",
            args.toArray());
    if (updated == 0) {
      return Optional.empty();
    }
    return findById(userId);
  }

  @Override
  public boolean scheduleDeletion(UUID userId, int graceDays) {
    // make_interval keeps the grace period a bound parameter. Concatenating it into an
    // INTERVAL literal would put a caller-supplied value into SQL text for no benefit.
    int updated =
        jdbcTemplate.update(
            """
            UPDATE users
               SET scheduled_deletion_at = now() + make_interval(days => ?)
             WHERE id = ? AND deleted_at IS NULL
            """,
            graceDays,
            userId);
    return updated > 0;
  }

  @Override
  public boolean cancelDeletion(UUID userId) {
    int updated =
        jdbcTemplate.update(
            "UPDATE users SET scheduled_deletion_at = NULL WHERE id = ? AND deleted_at IS NULL",
            userId);
    return updated > 0;
  }

  private static RowMapper<UserResponse> userRowMapper() {
    return (rs, rowNum) ->
        new UserResponse(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("display_name"),
            rs.getObject("selected_coach_id", UUID.class),
            rs.getBoolean("is_onboarded"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("scheduled_deletion_at", OffsetDateTime.class));
  }
}
