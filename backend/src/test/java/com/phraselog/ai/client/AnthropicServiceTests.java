package com.phraselog.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.phraselog.ai.logging.AiFeature;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AnthropicServiceTests {

  @Test
  void testFeatureRoutingSonnet() {
    assertThat(FeatureRouting.getModelForFeature(AiFeature.S07_ANALYSIS))
        .isEqualTo("claude-sonnet-4-6");
    assertThat(FeatureRouting.getModelForFeature(AiFeature.ROLEPLAY_SESSION_INIT))
        .isEqualTo("claude-sonnet-4-6");
    assertThat(FeatureRouting.getModelForFeature(AiFeature.ROLEPLAY_RESULT))
        .isEqualTo("claude-sonnet-4-6");
  }

  @Test
  void testFeatureRoutingHaiku() {
    assertThat(FeatureRouting.getModelForFeature(AiFeature.ROLEPLAY_TURN_FEEDBACK))
        .isEqualTo("claude-haiku-4-5");
  }

  @Test
  void testTimeoutForFeatures() {
    assertThat(FeatureRouting.getTimeoutForFeature(AiFeature.S07_ANALYSIS))
        .isEqualTo(Duration.ofSeconds(30));
    assertThat(FeatureRouting.getTimeoutForFeature(AiFeature.ROLEPLAY_TURN_RESPONSE))
        .isEqualTo(Duration.ofSeconds(15));
    assertThat(FeatureRouting.getTimeoutForFeature(AiFeature.ROLEPLAY_TURN_FEEDBACK))
        .isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void testAnthropicMessageUser() {
    AnthropicMessage msg = AnthropicMessage.user("Hello");
    assertThat(msg.role()).isEqualTo("user");
    assertThat(msg.content()).isEqualTo("Hello");
  }

  @Test
  void testAnthropicMessageAssistant() {
    AnthropicMessage msg = AnthropicMessage.assistant("Response");
    assertThat(msg.role()).isEqualTo("assistant");
    assertThat(msg.content()).isEqualTo("Response");
  }

  @Test
  void testAnthropicMessageValidation() {
    assertThatThrownBy(() -> new AnthropicMessage(null, "content"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AnthropicMessage("  ", "content"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
