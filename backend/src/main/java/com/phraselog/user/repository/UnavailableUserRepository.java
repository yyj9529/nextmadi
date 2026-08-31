package com.phraselog.user.repository;

import com.phraselog.user.dto.UserResponse;
import java.util.Optional;
import java.util.UUID;

/** Fallback wired in the no-DB scaffold context; the user profile requires a DataSource. */
public final class UnavailableUserRepository implements UserRepository {

  private static IllegalStateException unavailable() {
    return new IllegalStateException("UserRepository requires a DataSource; none is configured");
  }

  @Override
  public Optional<UserResponse> findById(UUID userId) {
    throw unavailable();
  }

  @Override
  public boolean coachExists(UUID coachId) {
    throw unavailable();
  }

  @Override
  public Optional<UserResponse> update(
      UUID userId, String displayName, UUID selectedCoachId, boolean setOnboardedTrue) {
    throw unavailable();
  }

  @Override
  public boolean scheduleDeletion(UUID userId, int graceDays) {
    throw unavailable();
  }

  @Override
  public boolean cancelDeletion(UUID userId) {
    throw unavailable();
  }
}
