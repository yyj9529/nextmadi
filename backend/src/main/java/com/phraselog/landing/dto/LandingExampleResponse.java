package com.phraselog.landing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * Response item for {@code GET /landing/examples} (#32), matching the OpenAPI LandingExample
 * schema.
 */
public record LandingExampleResponse(UUID id, @JsonProperty("korean_text") String koreanText) {}
