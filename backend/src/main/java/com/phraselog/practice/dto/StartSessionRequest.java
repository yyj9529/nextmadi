package com.phraselog.practice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for {@code POST /practice/sessions} (#59). {@code coach_id} is optional and defaults
 * to the user's {@code selected_coach_id} when omitted. Both ids are accepted as raw strings so the
 * service can map a malformed value to a clean 400 rather than a Jackson deserialization 500.
 */
public record StartSessionRequest(
    @JsonProperty("expression_id") String expressionId, @JsonProperty("coach_id") String coachId) {}
