package com.phraselog.ai.logging;

/**
 * Outcome of an AI/STT/TTS call as stored in {@code ai_request_logs.status}.
 *
 * <p>Wire values match the {@code chk_ai_request_logs_status} check constraint in {@code
 * V001__init_schema.sql}. {@code CACHE_HIT} is used by the TTS path when audio is served from
 * {@code tts_audio_cache} without a provider call.
 */
public enum AiRequestStatus {
  SUCCESS("success"),
  ERROR("error"),
  TIMEOUT("timeout"),
  CACHE_HIT("cache_hit");

  private final String wireName;

  AiRequestStatus(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  /** Whether this status represents a failed call that must carry an {@link AiErrorCode}. */
  public boolean isFailure() {
    return this == ERROR || this == TIMEOUT;
  }
}
