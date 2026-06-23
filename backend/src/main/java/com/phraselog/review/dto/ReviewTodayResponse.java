package com.phraselog.review.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** {@code GET /review/today} payload: the loaded batch plus the full due count (#49 S10). */
public record ReviewTodayResponse(
    List<ReviewCardResponse> cards, @JsonProperty("total_due") int totalDue) {}
