package com.phraselog.expression.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Response body for saved expressions (#41), matching the OpenAPI Expression schema. */
public record ExpressionResponse(
    UUID id,
    @JsonProperty("source_type") String sourceType,
    @JsonProperty("analysis_request_id") UUID analysisRequestId,
    @JsonProperty("practice_session_id") UUID practiceSessionId,
    @JsonProperty("original_situation") String originalSituation,
    @JsonProperty("selected_variant_id") UUID selectedVariantId,
    List<ExpressionVariantResponse> variants,
    @JsonProperty("review_card_id") UUID reviewCardId,
    @JsonProperty("next_review_at") OffsetDateTime nextReviewAt,
    @JsonProperty("created_at") OffsetDateTime createdAt) {}
