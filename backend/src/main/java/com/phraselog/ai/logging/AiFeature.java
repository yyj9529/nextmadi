package com.phraselog.ai.logging;

/**
 * Enumerates the {@code feature_name} values written to {@code ai_request_logs}.
 *
 * <p>The wire values are the canonical strings defined in {@code docs/data-model.md} and {@code
 * docs/AI_PIPELINE.md}; they must match those documents exactly because dashboards and diagnostic
 * queries group on them.
 */
public enum AiFeature {
  S07_ANALYSIS("s07_analysis"),
  ROLEPLAY_SESSION_INIT("roleplay_session_init"),
  ROLEPLAY_TURN_RESPONSE("roleplay_turn_response"),
  ROLEPLAY_TURN_FEEDBACK("roleplay_turn_feedback"),
  ROLEPLAY_RESULT("roleplay_result"),
  STT_TRANSCRIPTION("stt_transcription"),
  TTS_SYNTHESIS("tts_synthesis");

  private final String wireName;

  AiFeature(String wireName) {
    this.wireName = wireName;
  }

  /** The persisted {@code feature_name} string. */
  public String wireName() {
    return wireName;
  }
}
