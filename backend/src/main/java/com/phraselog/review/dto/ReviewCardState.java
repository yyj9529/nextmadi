package com.phraselog.review.dto;

import java.util.UUID;

/**
 * Internal read model for a single {@code review_cards} row, scoped to its owner. Carries just the
 * fields the submit/re-add flows need before writing (#49 S10).
 */
public record ReviewCardState(UUID id, UUID expressionId, int currentIntervalDays) {}
