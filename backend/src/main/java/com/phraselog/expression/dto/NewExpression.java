package com.phraselog.expression.dto;

import java.util.List;
import java.util.UUID;

/** Command object for creating an analysis-sourced expression in one transaction. */
public record NewExpression(
    UUID userId,
    UUID analysisRequestId,
    String originalSituation,
    int selectedVariantOrder,
    List<NewExpressionVariant> variants) {}
