package com.phraselog.expression.repository;

import com.phraselog.expression.dto.ExpressionResponse;
import com.phraselog.expression.dto.NewExpression;
import java.util.Optional;
import java.util.UUID;

/** Fallback wired in the no-DB scaffold context; expression saving requires a DataSource. */
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
}
