package com.phraselog.ai.client.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.client.dto.AnthropicMessage;
import com.phraselog.ai.client.dto.AnthropicResponse;
import com.phraselog.ai.logging.dto.AiErrorCode;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * RestClient-based implementation of {@link AnthropicClient}. Calls the Anthropic API at {@code
 * https://api.anthropic.com/v1/messages}.
 */
@Component
@ConditionalOnProperty(
    name = "anthropic.mock.enabled",
    havingValue = "false",
    matchIfMissing = true)
public class RestClientAnthropicClient implements AnthropicClient {

  private static final Logger log = LoggerFactory.getLogger(RestClientAnthropicClient.class);

  private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
  private static final String ANTHROPIC_API_VERSION = "2023-06-01";

  /**
   * TCP connect budget. Kept small and independent of the per-feature read timeout: a connect that
   * has not completed in 5s is a network problem, not a slow model, and burning the whole S07
   * budget on it would leave no time for the answer.
   */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  /**
   * One RestClient per distinct read timeout. The timeout must be bound to the request factory (the
   * JDK connection is configured before the call), so it cannot be applied per-request on a shared
   * client. {@link com.phraselog.ai.client.config.FeatureRouting} yields a small fixed set of
   * durations, so this map stays bounded.
   */
  private final Map<Duration, RestClient> clientsByTimeout = new ConcurrentHashMap<>();

  private final String apiKey;
  private final ObjectMapper objectMapper;

  public RestClientAnthropicClient(
      @Value("${anthropic.api-key:}") String apiKey, ObjectMapper objectMapper) {
    this.apiKey = apiKey;
    this.objectMapper = objectMapper;
  }

  /**
   * Builds (once per duration) a client whose read timeout is the caller's budget. Before this, the
   * {@code timeout} argument was accepted and never applied — {@code RestClient.builder().build()}
   * has no timeout at all, so a 35s S07 call completed against a documented 30s limit and the
   * TIMEOUT error path was unreachable in production.
   */
  private RestClient clientFor(Duration timeout) {
    return clientsByTimeout.computeIfAbsent(
        timeout,
        budget -> {
          SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
          factory.setConnectTimeout(CONNECT_TIMEOUT);
          factory.setReadTimeout(budget);
          return RestClient.builder().requestFactory(factory).build();
        });
  }

  @Override
  public AnthropicResponse sendMessage(
      String modelId, AnthropicMessage[] messages, Duration timeout)
      throws AnthropicClient.AnthropicClientException {

    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new AnthropicClient.AnthropicClientException(
          "Timeout must be positive", AiErrorCode.UNKNOWN, false);
    }

    if (apiKey == null || apiKey.isBlank()) {
      // Logged because this and a parse failure both surface as UNKNOWN with no other signal; the
      // giveaway in ai_request_logs is latency_ms=0 (no HTTP call was made at all).
      log.warn("Anthropic API key not configured — set ANTHROPIC_API_KEY on the backend process.");
      throw new AnthropicClient.AnthropicClientException(
          "Anthropic API key not configured", AiErrorCode.UNKNOWN, false);
    }

    Map<String, Object> requestBody = buildRequestBody(modelId, messages);

    try {
      ResponseEntity<String> response =
          clientFor(timeout)
              .post()
              .uri(ANTHROPIC_API_URL)
              .header("x-api-key", apiKey)
              .header("anthropic-version", ANTHROPIC_API_VERSION)
              .header("content-type", "application/json")
              .body(objectMapper.writeValueAsString(requestBody))
              .retrieve()
              .toEntity(String.class);

      if (response.getStatusCode() != HttpStatus.OK) {
        handleErrorResponse(response.getStatusCode(), response.getBody());
      }

      return parseResponse(response.getBody());

    } catch (HttpServerErrorException e) {
      throw new AnthropicClient.AnthropicClientException(
          "Anthropic provider error: " + e.getStatusCode(), AiErrorCode.PROVIDER_5XX, true, e);
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
        throw new AnthropicClient.AnthropicClientException(
            "Anthropic rate limited (429)", AiErrorCode.PROVIDER_429, true, e);
      }
      throw new AnthropicClient.AnthropicClientException(
          "Anthropic client error: " + e.getStatusCode(), AiErrorCode.UNKNOWN, false, e);
    } catch (ResourceAccessException e) {
      if (e.getCause() instanceof java.net.SocketTimeoutException
          || e.getMessage().contains("timeout")) {
        throw new AnthropicClient.AnthropicClientException(
            "Request timeout", AiErrorCode.TIMEOUT, false, e);
      }
      throw new AnthropicClient.AnthropicClientException(
          "Network error: " + e.getMessage(), AiErrorCode.NETWORK, true, e);
    } catch (NonJsonModelOutputException e) {
      // Retryable: AnthropicService resends with the "valid JSON only" reminder appended.
      throw new AnthropicClient.AnthropicClientException(
          e.getMessage(), AiErrorCode.SCHEMA_VALIDATION_FAILED, true, e);
    } catch (IOException e) {
      throw new AnthropicClient.AnthropicClientException(
          "Failed to parse response: " + e.getMessage(), AiErrorCode.UNKNOWN, false, e);
    } catch (Exception e) {
      log.warn("Unexpected error calling Anthropic API: {}", e.toString());
      throw new AnthropicClient.AnthropicClientException(
          "Unexpected error: " + e.getMessage(), AiErrorCode.UNKNOWN, false, e);
    }
  }

  private Map<String, Object> buildRequestBody(String modelId, AnthropicMessage[] messages) {
    Map<String, Object> body = new HashMap<>();
    body.put("model", modelId);
    body.put("max_tokens", 4096);
    body.put("messages", messages);
    return body;
  }

  private void handleErrorResponse(HttpStatusCode status, String responseBody) throws IOException {
    JsonNode errorNode = objectMapper.readTree(responseBody);
    String errorMessage =
        errorNode.has("error") && errorNode.get("error").has("message")
            ? errorNode.get("error").get("message").asText()
            : "Unknown error";
    throw new IOException("HTTP " + status + ": " + errorMessage);
  }

  /**
   * Splits one Anthropic response body into the two things callers need: the model output and the
   * token accounting. Package-private so the split is regression-tested directly against a captured
   * response shape — the previous version returned only the unwrapped payload, silently dropping
   * {@code usage} and leaving every cost column null.
   */
  AnthropicResponse parseResponse(String body) throws IOException {
    JsonNode responseJson = objectMapper.readTree(body);
    return new AnthropicResponse(
        extractContent(responseJson),
        usageToken(responseJson, "input_tokens"),
        usageToken(responseJson, "output_tokens"));
  }

  /** Reads {@code usage.<field>} off the outer response; null when the provider omitted it. */
  private static Integer usageToken(JsonNode response, String field) {
    JsonNode usage = response.get("usage");
    if (usage == null || !usage.hasNonNull(field)) {
      return null;
    }
    JsonNode value = usage.get(field);
    return value.isNumber() ? value.asInt() : null;
  }

  private JsonNode extractContent(JsonNode response) throws IOException {
    if (!response.has("content") || response.get("content").isNull()) {
      throw new IOException("No content in response");
    }
    JsonNode content = response.get("content");
    if (!content.isArray() || content.size() == 0) {
      throw new IOException("Content is not an array or is empty");
    }
    JsonNode firstContent = content.get(0);
    if (!firstContent.has("text")) {
      throw new IOException("First content block has no text");
    }
    return parseModelJson(firstContent.get("text").asText());
  }

  /**
   * Parses the model's answer as JSON, tolerating the two ways Claude decorates it: a markdown
   * fence and/or prose around the object ("Here is the analysis: {...}"). Both are
   * instruction-following lapses, not transport faults, so a failure here is reported as {@link
   * AiErrorCode#SCHEMA_VALIDATION_FAILED} — that is the code {@link AnthropicService} retries once
   * with the constraint reminder. Classifying it as {@code UNKNOWN} made the worse failure (no JSON
   * at all) the only one that could not recover.
   */
  private JsonNode parseModelJson(String rawText) throws NonJsonModelOutputException {
    String candidate = extractJsonObject(rawText);
    try {
      return objectMapper.readTree(candidate);
    } catch (IOException e) {
      // The head is the diagnostic: it says whether the model refused, explained, or truncated.
      log.warn(
          "Claude returned non-JSON output; retrying with the constraint reminder. head={}",
          rawText.strip().substring(0, Math.min(200, rawText.strip().length())));
      throw new NonJsonModelOutputException(e);
    }
  }

  /**
   * Narrows the text to the outermost JSON object when the model wrapped it in prose. Returns the
   * fence-stripped text unchanged when no braces are present, so a genuine non-JSON answer still
   * fails loudly rather than being silently truncated into something parseable.
   */
  private static String extractJsonObject(String text) {
    String stripped = stripJsonFence(text);
    if (stripped.startsWith("{")) {
      return stripped;
    }
    int start = stripped.indexOf('{');
    int end = stripped.lastIndexOf('}');
    return (start >= 0 && end > start) ? stripped.substring(start, end + 1) : stripped;
  }

  /** Model output that is not JSON. Separate type so it is not classified as a transport fault. */
  private static final class NonJsonModelOutputException extends IOException {
    NonJsonModelOutputException(Throwable cause) {
      super("Model output was not JSON", cause);
    }
  }

  /**
   * Claude commonly wraps a JSON answer in a markdown fence (```json ... ``` or ``` ... ```). The
   * schema-validated callers parse the text as bare JSON, so strip a surrounding fence before
   * parsing. Non-fenced text is returned unchanged.
   */
  private static String stripJsonFence(String text) {
    String trimmed = text.strip();
    if (!trimmed.startsWith("```")) {
      return trimmed;
    }
    int firstNewline = trimmed.indexOf('\n');
    if (firstNewline < 0) {
      return trimmed;
    }
    String inner = trimmed.substring(firstNewline + 1);
    int closingFence = inner.lastIndexOf("```");
    if (closingFence >= 0) {
      inner = inner.substring(0, closingFence);
    }
    return inner.strip();
  }
}
