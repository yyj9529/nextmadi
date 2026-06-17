package com.phraselog.ai.client;

import com.phraselog.ai.logging.AiFeature;
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

  /** Get the timeout duration for a given feature. */
  public static Duration getTimeoutForFeature(AiFeature feature) {
    return switch (feature) {
      case S07_ANALYSIS, ROLEPLAY_SESSION_INIT, ROLEPLAY_RESULT -> Duration.ofSeconds(30);
      case ROLEPLAY_TURN_RESPONSE -> Duration.ofSeconds(15);
      case ROLEPLAY_TURN_FEEDBACK -> Duration.ofSeconds(10);
      case STT_TRANSCRIPTION, TTS_SYNTHESIS ->
          throw new IllegalArgumentException(
              "Feature " + feature + " is not handled by AnthropicService");
    };
  }
}
