package com.phraselog.practice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One {@code practice_turns} row, matching the OpenAPI {@code PracticeTurn} schema (#59).
 *
 * <p>{@code tts_audio_url} is always null in this ticket: TTS synthesis for coach turns lands with
 * the TTS backend (#30). S12 tolerates a null audio url (text-only fallback), so the contract holds.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PracticeTurnResponse(
    UUID id,
    @JsonProperty("turn_number") int turnNumber,
    String speaker,
    @JsonProperty("text_content") String textContent,
    @JsonProperty("tts_audio_url") String ttsAudioUrl,
    @JsonProperty("stt_confidence") BigDecimal sttConfidence,
    @JsonProperty("feedback_shown") boolean feedbackShown,
    @JsonProperty("created_at") OffsetDateTime createdAt) {}
