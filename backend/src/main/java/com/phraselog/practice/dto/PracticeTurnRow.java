package com.phraselog.practice.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A persisted {@code practice_turns} row as read back from the database (#59). */
public record PracticeTurnRow(
    UUID id,
    UUID sessionId,
    int turnNumber,
    String speaker,
    String textContent,
    UUID ttsAudioCacheId,
    BigDecimal sttConfidence,
    boolean feedbackShown,
    OffsetDateTime createdAt) {}
