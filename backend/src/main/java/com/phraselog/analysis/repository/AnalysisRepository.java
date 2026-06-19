package com.phraselog.analysis.repository;

import com.phraselog.analysis.dto.AnalysisRequestRow;
import com.phraselog.analysis.dto.NewAnalysis;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for {@code analysis_requests} (#39). */
public interface AnalysisRepository {

  /**
   * Inserts one row and returns it with the generated {@code id}/{@code created_at}.
   *
   * @throws org.springframework.dao.DuplicateKeyException if the (caller, idempotency_key) unique
   *     index is violated — a concurrent retry won the race; the caller should re-read.
   */
  AnalysisRequestRow insert(NewAnalysis analysis);

  /** Finds an analysis by id, authorized to the caller (user_id or session_token). */
  Optional<AnalysisRequestRow> findByIdForOwner(UUID id, InternalAuthPrincipal principal);

  /** Finds an existing analysis for an idempotent retry from the same caller. */
  Optional<AnalysisRequestRow> findByCallerAndKey(
      InternalAuthPrincipal principal, UUID idempotencyKey);

  /**
   * Resolves the {@code ai_request_logs.id} written by the AI client for the given correlation id,
   * so it can be linked as the {@code analysis_requests.ai_request_log_id} FK. Empty when no log
   * row exists (logging swallowed a failure, or the no-DB store is active).
   */
  Optional<UUID> findLogIdByCorrelation(UUID correlationId);
}
