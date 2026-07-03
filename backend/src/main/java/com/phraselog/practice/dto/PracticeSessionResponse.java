package com.phraselog.practice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response body for the practice session endpoints (#59), matching the OpenAPI {@code
 * PracticeSession} schema (with the {@code opening_turn} extension on POST).
 *
 * <p>{@code NON_NULL} inclusion mirrors the contract's two shapes from one record: {@code
 * opening_turn} is set on {@code POST} (and {@code turns} omitted); {@code turns} is set on {@code
 * GET} (and {@code opening_turn} omitted).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PracticeSessionResponse(
    UUID id,
    String status,
    @JsonProperty("planned_turns") int plannedTurns,
    @JsonProperty("coach_id") UUID coachId,
    @JsonProperty("expression_id") UUID expressionId,
    @JsonProperty("started_at") OffsetDateTime startedAt,
    @JsonProperty("ended_at") OffsetDateTime endedAt,
    @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("result_json") JsonNode resultJson,
    List<PracticeTurnResponse> turns,
    @JsonProperty("opening_turn") PracticeTurnResponse openingTurn) {}
