package com.phraselog.expression.dto;

import java.util.List;
import java.util.UUID;

/** Command object for creating an expression saved from an S12b roleplay result (#62). */
public record NewRoleplayExpression(
    UUID userId,
    UUID practiceSessionId,
    String originalSituation,
    int selectedVariantOrder,
    int roleplayResultIndex,
    UUID idempotencyKey,
    List<NewExpressionVariant> variants) {}
