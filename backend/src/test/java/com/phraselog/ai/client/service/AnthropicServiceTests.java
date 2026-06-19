package com.phraselog.ai.client.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.client.config.FeatureRouting;
import com.phraselog.ai.client.dto.AnthropicMessage;
import com.phraselog.ai.logging.dto.AiFeature;
import com.phraselog.ai.logging.dto.AiRequestLogEntry;
import com.phraselog.ai.logging.dto.AiRequestStatus;
import com.phraselog.ai.logging.repository.AiRequestLogStore;
import com.phraselog.ai.logging.service.AiCostCalculator;
import com.phraselog.ai.logging.service.AiRequestLogger;
import com.phraselog.ai.prompt.dto.PromptDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnthropicServiceTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

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

  @Test
  void callClaudeValidatesS07ResponseAgainstClasspathSchema() throws Exception {
    RecordingLogStore logStore = new RecordingLogStore();
    JsonNode providerResponse = MAPPER.readTree(validS07Analysis());
    AnthropicClient client = (modelId, messages, timeout) -> providerResponse;
    AnthropicService service =
        new AnthropicService(
            client,
            new AiRequestLogger(logStore),
            new AiCostCalculator(),
            new JsonSchemaValidator(MAPPER));
    UUID userId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();

    JsonNode response =
        service.callClaude(
            AiFeature.S07_ANALYSIS, promptDefinition(), "user situation", userId, correlationId);

    assertThat(response.get("expressions")).hasSize(3);
    assertThat(logStore.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.feature()).isEqualTo(AiFeature.S07_ANALYSIS);
              assertThat(entry.status()).isEqualTo(AiRequestStatus.SUCCESS);
              assertThat(entry.userId()).isEqualTo(userId);
              assertThat(entry.requestCorrelationId()).isEqualTo(correlationId);
              assertThat(entry.promptVersion()).isEqualTo("s07-v1");
            });
  }

  private static PromptDefinition promptDefinition() {
    return new PromptDefinition(
        "s07_analysis",
        "s07-v1",
        "claude-sonnet-4-6",
        "s07_analysis_v1",
        "2026-06-04",
        null,
        "system prompt body");
  }

  private static String validS07Analysis() {
    return """
        {
          "expressions": [
            {
              "english": "A",
              "tone_label": "polite",
              "ipa": "/a/",
              "korean_pronunciation": "ei",
              "pronunciation_tip": "Keep it short.",
              "cultural_tip": "Use with close friends."
            },
            {
              "english": "B",
              "tone_label": "gentle",
              "ipa": "/b/",
              "korean_pronunciation": "bi",
              "pronunciation_tip": "Keep it short.",
              "cultural_tip": "Use with friends."
            },
            {
              "english": "C",
              "tone_label": "firm",
              "ipa": "/c/",
              "korean_pronunciation": "si",
              "pronunciation_tip": "Keep it short.",
              "cultural_tip": "Use when the situation repeats."
            }
          ]
        }
        """;
  }

  private static final class RecordingLogStore implements AiRequestLogStore {

    private final List<AiRequestLogEntry> entries = new ArrayList<>();

    @Override
    public void save(AiRequestLogEntry entry) {
      entries.add(entry);
    }

    List<AiRequestLogEntry> entries() {
      return entries;
    }
  }
}
