package com.phraselog.practice.repository;

import com.phraselog.practice.dto.SubmitPracticeTurnResponse;
import java.util.Optional;
import java.util.UUID;

/** Fails fast when practice routes are invoked without a DataSource. */
public final class UnavailablePracticeTurnRepository implements PracticeTurnRepository {

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "PracticeTurnRepository requires a DataSource; none is configured");
  }

  @Override
  public Optional<PracticeSessionContext> findSessionForUser(UUID sessionId, UUID userId) {
    throw unavailable();
  }

  @Override
  public Optional<SubmitPracticeTurnResponse> findReplay(UUID sessionId, UUID idempotencyKey) {
    throw unavailable();
  }

  @Override
  public PracticeTurnRequestRow reserveRequest(
      UUID sessionId, UUID idempotencyKey, UUID requestCorrelationId) {
    throw unavailable();
  }

  @Override
  public SubmitPracticeTurnResponse appendTurnPair(
      PracticeTurnRequestRow request, AppendTurnPairCommand command) {
    throw unavailable();
  }

  @Override
  public SubmitPracticeTurnResponse completeWithoutTurn(
      PracticeTurnRequestRow request, String retryPrompt) {
    throw unavailable();
  }

  @Override
  public void markFailed(UUID requestId) {
    throw unavailable();
  }
}
