package com.phraselog.common.web;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ApiErrorResponse(
    @JsonProperty("error_code") String errorCode,
    @JsonProperty("user_message") String userMessage,
    @JsonProperty("developer_hint") String developerHint,
    boolean retryable,
    @JsonProperty("request_correlation_id") String requestCorrelationId) {}
