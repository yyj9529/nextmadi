package com.phraselog.practice.repository;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.UUID;

/** Data persisted atomically when a user turn is consumed. */
public record AppendTurnPairCommand(
    String userText,
    BigDecimal sttConfidence,
    String coachText,
    UUID ttsAudioCacheId,
    String coachAudioUrl,
    boolean feedbackShown,
    JsonNode feedbackContent) {}
