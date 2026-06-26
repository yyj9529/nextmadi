package com.phraselog.practice.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A persisted {@code practice_sessions} row as read back from the database (#59). */
public record PracticeSessionRow(
    UUID id,
    UUID userId,
    UUID expressionId,
    UUID coachId,
    String status,
    int plannedTurns,
    OffsetDateTime startedAt,
    OffsetDateTime endedAt) {}
