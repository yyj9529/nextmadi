package com.phraselog.review.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code POST /review/{id}/submit} result: the computed next interval and due date (#49 S10). */
public record SubmitRatingResponse(
    @JsonProperty("review_card_id") UUID reviewCardId,
    @JsonProperty("previous_interval_days") int previousIntervalDays,
    @JsonProperty("next_interval_days") int nextIntervalDays,
    @JsonProperty("next_review_at") OffsetDateTime nextReviewAt) {}
