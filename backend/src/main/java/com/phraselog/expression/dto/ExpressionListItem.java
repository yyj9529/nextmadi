package com.phraselog.expression.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Compact projection for the S08 library list (#45), matching the OpenAPI {@code
 * ExpressionListItem} schema. {@code englishText}/{@code toneLabel} come from the selected variant.
 */
public record ExpressionListItem(
    UUID id,
    @JsonProperty("original_situation") String originalSituation,
    @JsonProperty("english_text") String englishText,
    @JsonProperty("tone_label") String toneLabel,
    @JsonProperty("created_at") OffsetDateTime createdAt) {}
