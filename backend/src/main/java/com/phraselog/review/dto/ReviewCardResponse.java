package com.phraselog.review.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.phraselog.expression.dto.ExpressionVariantResponse;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One due {@code review_cards} row with its parent expression's selected variant (#49 S10). */
public record ReviewCardResponse(
    UUID id,
    @JsonProperty("expression_id") UUID expressionId,
    @JsonProperty("next_review_at") OffsetDateTime nextReviewAt,
    @JsonProperty("last_reviewed_at") OffsetDateTime lastReviewedAt,
    @JsonProperty("last_rating") String lastRating,
    @JsonProperty("current_interval_days") int currentIntervalDays,
    @JsonProperty("original_situation") String originalSituation,
    ExpressionVariantResponse variant) {}
