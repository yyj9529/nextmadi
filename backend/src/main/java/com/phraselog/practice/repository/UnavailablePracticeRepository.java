package com.phraselog.practice.repository;

import com.phraselog.practice.dto.NewPracticeSession;
import com.phraselog.practice.dto.PracticeSessionWithTurns;
import java.util.Optional;
import java.util.UUID;

/**
 * Placeholder {@link PracticeRepository} wired when no {@code DataSource} is present (the no-DB
 * scaffold context, mirroring {@link
 * com.phraselog.analysis.repository.UnavailableAnalysisRepository}). The practice endpoints cannot
 * function without a database, so every method fails fast; the no-DB context never exercises them.
 */
public final class UnavailablePracticeRepository implements PracticeRepository {

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "PracticeRepository requires a DataSource; none is configured");
  }

  @Override
  public PracticeSessionWithTurns insertSessionWithOpeningTurn(NewPracticeSession session) {
    throw unavailable();
  }

  @Override
  public Optional<PracticeSessionWithTurns> findByUserAndKey(UUID userId, UUID idempotencyKey) {
    throw unavailable();
  }

  @Override
  public Optional<PracticeSessionWithTurns> findByIdForOwner(UUID sessionId, UUID userId) {
    throw unavailable();
  }
}
