package com.phraselog.ai.client.service;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.client.dto.AnthropicMessage;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Proves the local mock returns a schema-valid canned payload for every feature it must serve, so
 * the analysis / roleplay pipelines can be exercised on localhost without a real API key.
 */
class MockAnthropicClientTests {

  private final MockAnthropicClient mock = new MockAnthropicClient(new ObjectMapper());
  private final JsonSchemaValidator validator = new JsonSchemaValidator(new ObjectMapper());

  private JsonNode call(String promptBody) throws Exception {
    return mock.sendMessage(
        "model", new AnthropicMessage[] {AnthropicMessage.user(promptBody)}, Duration.ofSeconds(1));
  }

  private void assertValid(String promptBody, String schemaId) throws Exception {
    JsonNode response = call(promptBody);
    assertThatCode(() -> validator.validate(schemaId, response)).doesNotThrowAnyException();
  }

  @Test
  void s07AnalysisPayloadIsSchemaValid() throws Exception {
    assertValid("You are the analysis engine for PhraseLog", "s07_analysis_v1");
  }

  @Test
  void unknownPromptFallsBackToValidS07Payload() throws Exception {
    assertValid("something unrecognized", "s07_analysis_v1");
  }

  @Test
  void roleplaySessionInitPayloadIsSchemaValid() throws Exception {
    assertValid("You start a PhraseLog guided roleplay session", "roleplay_session_init_v1");
  }

  @Test
  void roleplayTurnResponsePayloadIsSchemaValid() throws Exception {
    assertValid(
        "You are the selected PhraseLog coach inside an active roleplay session",
        "roleplay_turn_response_v1");
  }

  @Test
  void roleplayTurnFeedbackPayloadIsSchemaValid() throws Exception {
    assertValid("You decide whether to show a brief feedback card", "roleplay_turn_feedback_v1");
  }

  @Test
  void roleplayResultPayloadIsSchemaValid() throws Exception {
    assertValid("You generate the final summary for a completed PhraseLog", "roleplay_result_v1");
  }
}
