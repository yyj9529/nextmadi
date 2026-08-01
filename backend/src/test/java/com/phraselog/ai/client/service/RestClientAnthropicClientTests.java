package com.phraselog.ai.client.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.client.dto.AnthropicMessage;
import com.phraselog.ai.client.dto.AnthropicResponse;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Pins the split of one Anthropic response body into payload plus usage.
 *
 * <p>Found by the first real keyed S07 call (#107): the client unwrapped {@code content[0].text}
 * and returned only that, so {@code usage} — which sits on the outer object, beside {@code content}
 * — was gone before anything could read it. Every real {@code ai_request_logs} row therefore stored
 * null tokens and null cost while reporting {@code success}.
 *
 * <p>The bodies below follow the shape returned by {@code POST /v1/messages} (verified against a
 * live response, 2026-07-25). The parse step is tested directly rather than over HTTP: the loss was
 * in parsing, and a transport-level test would not have caught it.
 */
class RestClientAnthropicClientTests {

  private final RestClientAnthropicClient client =
      new RestClientAnthropicClient("test-key", new ObjectMapper());

  private static String body(String contentText, String usageJson) {
    return """
        {
          "id": "msg_test",
          "type": "message",
          "role": "assistant",
          "model": "claude-sonnet-4-6",
          "content": [{"type": "text", "text": %s}],
          "stop_reason": "end_turn"%s
        }
        """
        .formatted(contentText, usageJson);
  }

  @Test
  void parseResponseKeepsUsageAlongsideThePayload() throws Exception {
    AnthropicResponse response =
        client.parseResponse(
            body(
                "\"{\\\"expressions\\\": []}\"",
                ",\n  \"usage\": {\"input_tokens\": 1523," + " \"output_tokens\": 1187}"));

    assertThat(response.payload().has("expressions")).isTrue();
    assertThat(response.inputTokens()).isEqualTo(1523);
    assertThat(response.outputTokens()).isEqualTo(1187);
  }

  @Test
  void parseResponseUnwrapsAMarkdownFencedPayloadWithoutLosingUsage() throws Exception {
    AnthropicResponse response =
        client.parseResponse(
            body(
                "\"```json\\n{\\\"expressions\\\": []}\\n```\"",
                ",\n  \"usage\": {\"input_tokens\": 10, \"output_tokens\": 20}"));

    assertThat(response.payload().has("expressions")).isTrue();
    assertThat(response.inputTokens()).isEqualTo(10);
    assertThat(response.outputTokens()).isEqualTo(20);
  }

  /**
   * Observed on 2026-07-28: Claude prefaced the object with prose ("The ..."), the client parsed
   * the whole string as JSON, and the resulting {@code JsonParseException} surfaced as a
   * non-retryable {@code UNKNOWN} — a 500 the schema-retry path could not rescue.
   */
  @Test
  void parseResponseUnwrapsAPayloadWrappedInProse() throws Exception {
    AnthropicResponse response =
        client.parseResponse(
            body(
                "\"The situation calls for three options.\\n\\n{\\\"expressions\\\": []}\\n\\nHope"
                    + " this helps.\"",
                ",\n  \"usage\": {\"input_tokens\": 11, \"output_tokens\": 22}"));

    assertThat(response.payload().has("expressions")).isTrue();
    assertThat(response.inputTokens()).isEqualTo(11);
  }

  /** Prose with no JSON at all must stay a failure — and one the caller is allowed to retry. */
  @Test
  void parseResponseRejectsOutputThatContainsNoJsonAtAll() {
    assertThatThrownBy(() -> client.parseResponse(body("\"I cannot help with that request.\"", "")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("not JSON");
  }

  @Test
  void parseResponseReportsNullUsageWhenTheProviderOmitsIt() throws Exception {
    AnthropicResponse response = client.parseResponse(body("\"{\\\"expressions\\\": []}\"", ""));

    assertThat(response.payload().has("expressions")).isTrue();
    assertThat(response.inputTokens()).isNull();
    assertThat(response.outputTokens()).isNull();
  }

  @Test
  void parseResponseReportsNullUsageForNonNumericTokenCounts() throws Exception {
    AnthropicResponse response =
        client.parseResponse(
            body(
                "\"{\\\"expressions\\\": []}\"",
                ",\n  \"usage\": {\"input_tokens\": null, \"output_tokens\": \"n/a\"}"));

    assertThat(response.inputTokens()).isNull();
    assertThat(response.outputTokens()).isNull();
  }

  @Test
  void parseResponseRejectsAResponseWithoutContent() {
    assertThatThrownBy(() -> client.parseResponse("{\"id\": \"msg_test\"}"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("No content");
  }

  /**
   * The timeout argument used to be accepted and ignored — the client was built from {@code
   * RestClient.builder().build()}, which has no timeout, so a 35s S07 call completed against a
   * documented 30s budget. Rejecting a non-positive budget fails fast instead of falling back to
   * "no limit". Enforcement of the elapsed budget itself is a transport concern and is covered by
   * the manual keyed run, not here.
   */
  @Test
  void sendMessageRejectsANonPositiveTimeout() {
    assertThatThrownBy(() -> client.sendMessage("claude-sonnet-4-6", new AnthropicMessage[0], null))
        .isInstanceOf(AnthropicClient.AnthropicClientException.class)
        .hasMessageContaining("Timeout must be positive");

    assertThatThrownBy(
            () ->
                client.sendMessage(
                    "claude-sonnet-4-6", new AnthropicMessage[0], Duration.ofSeconds(0)))
        .isInstanceOf(AnthropicClient.AnthropicClientException.class)
        .hasMessageContaining("Timeout must be positive");
  }
}
