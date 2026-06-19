package com.phraselog.expression.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** Request body for {@code POST /expressions} (#41). */
public record CreateExpressionRequest(
    @JsonProperty("analysis_request_id") UUID analysisRequestId,
    @JsonProperty("selected_variant_order") Integer selectedVariantOrder) {}
