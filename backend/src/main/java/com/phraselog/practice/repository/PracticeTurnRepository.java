package com.phraselog.practice.repository;

import com.phraselog.practice.dto.SubmitPracticeTurnResponse;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for S12 turn submission (#60). */
public interface PracticeTurnRepository {

  Optional<PracticeSessionContext> findSessionForUser(UUID sessionId, UUID userId);

  Optional<SubmitPracticeTurnResponse> findReplay(UUID sessionId, UUID idempotencyKey);

  PracticeTurnRequestRow reserveRequest(
      UUID sessionId, UUID idempotencyKey, UUID requestCorrelationId);

  SubmitPracticeTurnResponse appendTurnPair(
      PracticeTurnRequestRow request, AppendTurnPairCommand command);

  SubmitPracticeTurnResponse completeWithoutTurn(
      PracticeTurnRequestRow request, String retryPrompt);

  void markFailed(UUID requestId);
}
