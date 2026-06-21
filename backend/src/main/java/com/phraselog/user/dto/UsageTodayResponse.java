package com.phraselog.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body for {@code GET /usage/today} (#52).
 *
 * <p>{@code analysisLimit} is nullable on purpose: {@code null} = unlimited in v1, serialized as
 * JSON {@code null} per the openapi contract.
 */
public record UsageTodayResponse(
    @JsonProperty("roleplay_session_count") int roleplaySessionCount,
    @JsonProperty("daily_roleplay_limit") int dailyRoleplayLimit,
    @JsonProperty("analysis_count") int analysisCount,
    @JsonProperty("analysis_limit") Integer analysisLimit) {}
