package com.phraselog.coach.repository;

import com.phraselog.coach.dto.CoachResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Fallback wired in the no-DB scaffold context; the coach catalog requires a DataSource. */
public final class UnavailableCoachRepository implements CoachRepository {

  private static IllegalStateException unavailable() {
    return new IllegalStateException("CoachRepository requires a DataSource; none is configured");
  }

  @Override
  public List<CoachResponse> findAll() {
    throw unavailable();
  }

  @Override
  public Optional<CoachResponse> findById(UUID coachId) {
    throw unavailable();
  }
}
