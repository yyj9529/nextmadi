package com.phraselog.expression.repository;

import com.phraselog.expression.dto.ExpressionListItem;
import com.phraselog.expression.dto.ExpressionResponse;
import com.phraselog.expression.dto.NewExpression;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Fallback wired in the no-DB scaffold context; expression storage requires a DataSource. */
public final class UnavailableExpressionRepository implements ExpressionRepository {

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "ExpressionRepository requires a DataSource; none is configured");
  }

  @Override
  public Optional<ExpressionResponse> findByAnalysisIdForUser(UUID analysisRequestId, UUID userId) {
    throw unavailable();
  }

  @Override
  public ExpressionResponse createFromAnalysis(NewExpression expression) {
    throw unavailable();
  }

  @Override
  public List<ExpressionListItem> list(
      UUID userId, String q, OffsetDateTime cursorCreatedAt, UUID cursorId, int limit) {
    throw unavailable();
  }

  @Override
  public Optional<ExpressionResponse> findByIdForUser(UUID expressionId, UUID userId) {
    throw unavailable();
  }

  @Override
  public boolean softDelete(UUID expressionId, UUID userId) {
    throw unavailable();
  }
}
