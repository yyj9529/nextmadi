package com.phraselog.practice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body for {@code POST /practice/sessions/{id}/save-expression} (#62). */
public record SaveRoleplayExpressionRequest(
    @JsonProperty("recommended_expression_index") Integer recommendedExpressionIndex) {}
