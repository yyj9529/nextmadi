package com.phraselog.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.phraselog.analysis.service.AnalysisService;

/**
 * Request body for {@code POST /analysis}. Only {@code input_text} is consumed; {@code
 * landing_example_id} (openapi) is accepted for contract completeness but not persisted — there is
 * no column for it and analysis does not branch on it.
 *
 * <p>Validation (presence, 500-char cap) is performed in {@link AnalysisService}, not via bean
 * validation, because the backend does not depend on {@code spring-boot-starter-validation}.
 */
public record CreateAnalysisRequest(
    @JsonProperty("input_text") String inputText,
    @JsonProperty("landing_example_id") String landingExampleId) {}
