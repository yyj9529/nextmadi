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

  /**
   * Sets {@code scheduled_deletion_at = now() + graceDays} for an active user (#24).
   *
   * <p>Calling it again restarts the window rather than failing — the caller confirmed deletion
   * either way, and a second confirmation should not shorten the grace period. Returns false if the
   * user has no active row.
   */
  boolean scheduleDeletion(UUID userId, int graceDays);

  /**
   * Clears {@code scheduled_deletion_at} for an active user (#24). A no-op when nothing was
   * scheduled. Returns false if the user has no active row.
   */
  boolean cancelDeletion(UUID userId);
}
