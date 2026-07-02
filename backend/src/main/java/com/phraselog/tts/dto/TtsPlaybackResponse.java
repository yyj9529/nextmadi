package com.phraselog.tts.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code POST /tts/playback} 성공 응답. */
public record TtsPlaybackResponse(
    @JsonProperty("audio_url") String audioUrl,
    @JsonProperty("duration_ms") Integer durationMs,
    @JsonProperty("cache_status") String cacheStatus) {}
