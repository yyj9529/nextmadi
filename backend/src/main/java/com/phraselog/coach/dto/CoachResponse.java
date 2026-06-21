package com.phraselog.coach.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** Response item for {@code GET /coaches} (#52), matching the OpenAPI Coach schema. */
public record CoachResponse(
    UUID id,
    String slug,
    @JsonProperty("display_name") String displayName,
    @JsonProperty("persona_summary") String personaSummary,
    @JsonProperty("tts_voice_id") String ttsVoiceId) {}
