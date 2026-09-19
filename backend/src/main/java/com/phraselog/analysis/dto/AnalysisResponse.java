package com.phraselog.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code AnalysisRequest} response body for {@code POST /analysis} (201) and {@code GET
 * /analysis/{id}} (200), per {@code openapi.yaml}. Expression results have 3 variants; other
 * branches have none. Legacy stored expression results remain readable.
 */
public record AnalysisResponse(
    UUID id,
    @JsonProperty("input_text") String inputText,
    List<ExpressionVariantDto> variants,
    @JsonProperty("prompt_version") String promptVersion,
    @JsonProperty("created_at") OffsetDateTime createdAt,
    @JsonProperty("result_type") String resultType,
    JsonNode assessment,
    String question,
    JsonNode word) {
  public AnalysisResponse(
      UUID id,
      String inputText,
      List<ExpressionVariantDto> variants,
      String promptVersion,
      OffsetDateTime createdAt) {
    this(id, inputText, variants, promptVersion, createdAt, "expressions", null, null, null);
  }
}
