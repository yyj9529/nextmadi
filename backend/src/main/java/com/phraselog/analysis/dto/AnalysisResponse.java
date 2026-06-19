package com.phraselog.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code AnalysisRequest} response body for {@code POST /analysis} (201) and {@code GET
 * /analysis/{id}} (200), per {@code openapi.yaml}. Always exactly 3 variants.
 */
public record AnalysisResponse(
    UUID id,
    @JsonProperty("input_text") String inputText,
    List<ExpressionVariantDto> variants,
    @JsonProperty("prompt_version") String promptVersion,
    @JsonProperty("created_at") OffsetDateTime createdAt) {}
