package com.phraselog.ai.client.config;

import com.phraselog.ai.logging.dto.AiFeature;
import java.time.Duration;

/** Feature-to-model and timeout routing per docs/AI_PIPELINE.md. */
public final class FeatureRouting {

  private FeatureRouting() {}

  /** Get the model ID for a given feature. */
  public static String getModelForFeature(AiFeature feature) {
    return switch (feature) {
      case S07_ANALYSIS, ROLEPLAY_SESSION_INIT, ROLEPLAY_TURN_RESPONSE, ROLEPLAY_RESULT ->
          "claude-sonnet-4-6";
      case ROLEPLAY_TURN_FEEDBACK -> "claude-haiku-4-5";
      case STT_TRANSCRIPTION, TTS_SYNTHESIS ->
          throw new IllegalArgumentException(
              "Feature " + feature + " is not handled by AnthropicService");
    };
  }

  /**
   * Get the timeout duration for a given feature.
   *
   * <p>S07 is 60s, not the 30s the other Sonnet features use. The 30s figure came from a planning
   * storyboard, and until the timeout was actually applied to the HTTP call nothing enforced it.
   * Three real keyed S07 calls measured 25.7s / 27.8s / 35.1s (2026-07-25) — a mean sitting on top
   * of the old limit, so roughly half of real requests would have failed the moment enforcement
   * started working. 60s is set from that measurement.
   *
   * <p>{@code ROLEPLAY_SESSION_INIT} and {@code ROLEPLAY_RESULT} stay at 30s: they are unmeasured,
   * and raising them on the strength of S07's numbers would be a guess. Their outputs are at least
   * as large, so they are likely to need the same treatment — measure before changing.
   */
  public static Duration getTimeoutForFeature(AiFeature feature) {
    return switch (feature) {
      case S07_ANALYSIS -> Duration.ofSeconds(60);
      case ROLEPLAY_SESSION_INIT, ROLEPLAY_RESULT -> Duration.ofSeconds(30);
      case ROLEPLAY_TURN_RESPONSE -> Duration.ofSeconds(15);
      case ROLEPLAY_TURN_FEEDBACK -> Duration.ofSeconds(10);
      case STT_TRANSCRIPTION, TTS_SYNTHESIS ->
          throw new IllegalArgumentException(
              "Feature " + feature + " is not handled by AnthropicService");
    };
  }
}
