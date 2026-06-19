package com.phraselog.expression.repository;

import com.phraselog.expression.dto.ExpressionResponse;
import com.phraselog.expression.dto.NewExpression;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for saved expressions (#41). */
public interface ExpressionRepository {

  Optional<ExpressionResponse> findByAnalysisIdForUser(UUID analysisRequestId, UUID userId);

  ExpressionResponse createFromAnalysis(NewExpression expression);
}
