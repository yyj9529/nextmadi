package com.phraselog.practice.repository;

import java.util.UUID;

/** Reserved idempotency row for one submitted user turn. */
public record PracticeTurnRequestRow(UUID id, UUID requestCorrelationId) {}
