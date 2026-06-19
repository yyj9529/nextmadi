package com.phraselog.analysis.repository;

import com.phraselog.analysis.dto.AnalysisRequestRow;
import com.phraselog.analysis.dto.NewAnalysis;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import java.util.Optional;
import java.util.UUID;

/**
 * Placeholder {@link AnalysisRepository} wired when no {@code DataSource} is present (the no-DB
 * scaffold context, mirroring {@link com.phraselog.ai.logging.repository.NoOpAiRequestLogStore}).
 * The analysis endpoints cannot function without a database, so every method fails fast; the no-DB
 * context only exercises context loading and actuator, never {@code /analysis}.
 */
public final class UnavailableAnalysisRepository implements AnalysisRepository {

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "AnalysisRepository requires a DataSource; none is configured");
  }

  @Override
  public AnalysisRequestRow insert(NewAnalysis analysis) {
    throw unavailable();
  }

  @Override
  public Optional<AnalysisRequestRow> findByIdForOwner(UUID id, InternalAuthPrincipal principal) {
    throw unavailable();
  }

  @Override
  public Optional<AnalysisRequestRow> findByCallerAndKey(
      InternalAuthPrincipal principal, UUID idempotencyKey) {
    throw unavailable();
  }

  @Override
  public Optional<UUID> findLogIdByCorrelation(UUID correlationId) {
    throw unavailable();
  }
}
