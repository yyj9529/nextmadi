package com.phraselog.expression.repository;

import com.phraselog.expression.dto.ExpressionListItem;
import com.phraselog.expression.dto.ExpressionResponse;
import com.phraselog.expression.dto.NewExpression;
import com.phraselog.expression.dto.NewRoleplayExpression;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for saved expressions (#41, #45). */
public interface ExpressionRepository {

  Optional<ExpressionResponse> findByAnalysisIdForUser(UUID analysisRequestId, UUID userId);

  ExpressionResponse createFromAnalysis(NewExpression expression);

  Optional<ExpressionResponse> findByRoleplayIdempotencyKey(
      UUID practiceSessionId, UUID userId, UUID idempotencyKey);

  Optional<ExpressionResponse> findActiveRoleplaySaveByIndex(
      UUID practiceSessionId, UUID userId, int roleplayResultIndex);

  ExpressionResponse createFromRoleplayResult(NewRoleplayExpression expression);

  /**
   * S08 library list (#45): user's active expressions, {@code created_at DESC, id DESC}. Keyset
   * pagination via the {@code cursor*} pair (both null on the first page). Optional {@code q}
   * matches the Korean situation or any variant's English text. Returns at most {@code limit} rows.
   */
  List<ExpressionListItem> list(
      UUID userId, String q, OffsetDateTime cursorCreatedAt, UUID cursorId, int limit);

  /** S04 bookshelf count (#54): the user's active (non-soft-deleted) expressions. */
  int countActive(UUID userId);

  /** Full detail of one active expression owned by the user (#45); empty if missing/not owned. */
  Optional<ExpressionResponse> findByIdForUser(UUID expressionId, UUID userId);

  /** Soft-deletes an active expression owned by the user (#45); false if nothing was updated. */
  boolean softDelete(UUID expressionId, UUID userId);
}
