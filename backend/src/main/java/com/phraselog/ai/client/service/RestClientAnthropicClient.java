package com.phraselog.ai.client.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.client.dto.AnthropicMessage;
import com.phraselog.ai.logging.dto.AiErrorCode;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
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
public class RestClientAnthropicClient implements AnthropicClient {

  private static final Logger log = LoggerFactory.getLogger(RestClientAnthropicClient.class);

  private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
  private static final String ANTHROPIC_API_VERSION = "2024-06-01";

  private final RestClient restClient;
  private final String apiKey;
  private final ObjectMapper objectMapper;

  public RestClientAnthropicClient(
      @Value("${anthropic.api-key:}") String apiKey, ObjectMapper objectMapper) {
    this.apiKey = apiKey;
    this.objectMapper = objectMapper;
    this.restClient = RestClient.builder().build();
  }

  @Override
  public JsonNode sendMessage(String modelId, AnthropicMessage[] messages, Duration timeout)
      throws AnthropicClient.AnthropicClientException {

    if (apiKey == null || apiKey.isBlank()) {
      throw new AnthropicClient.AnthropicClientException(
          "Anthropic API key not configured", AiErrorCode.UNKNOWN, false);
    }

    Map<String, Object> requestBody = buildRequestBody(modelId, messages);

    try {
      ResponseEntity<String> response =
          restClient
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

      JsonNode responseJson = objectMapper.readTree(response.getBody());
      return extractContent(responseJson);

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
    String text = firstContent.get("text").asText();
    return objectMapper.readTree(text);
  }
}
