package com.phraselog.user.repository;

import com.phraselog.user.dto.UserResponse;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for the current-user profile (#52). */
public interface UserRepository {

  /** Active (non-deleted) user by id. */
  Optional<UserResponse> findById(UUID userId);

  /** True when a coach with this id exists; used to validate {@code selected_coach_id}. */
  boolean coachExists(UUID coachId);

  /**
   * Applies a partial profile update and returns the refreshed row.
   *
   * <p>{@code null} {@code displayName}/{@code selectedCoachId} columns are left untouched; {@code
   * setOnboardedTrue} sets {@code is_onboarded = true} when requested (never false). Returns empty
   * if the user does not exist.
   */
  Optional<UserResponse> update(
      UUID userId, String displayName, UUID selectedCoachId, boolean setOnboardedTrue);
}
