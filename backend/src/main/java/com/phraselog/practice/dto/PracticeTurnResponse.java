package com.phraselog.practice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Response shape for one row in practice_turns. */
public record PracticeTurnResponse(
    UUID id,
    @JsonProperty("turn_number") int turnNumber,
    String speaker,
    @JsonProperty("text_content") String textContent,
    @JsonProperty("tts_audio_url") String ttsAudioUrl,
    @JsonProperty("stt_confidence") BigDecimal sttConfidence,
    @JsonProperty("feedback_shown") boolean feedbackShown,
    @JsonProperty("created_at") OffsetDateTime createdAt) {}
