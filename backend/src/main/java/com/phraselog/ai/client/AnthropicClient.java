package com.phraselog.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.phraselog.ai.logging.AiErrorCode;
import java.time.Duration;

/**
 * HTTP client for Anthropic Claude API. Handles request/response, timeouts, and error handling.
 */
public interface AnthropicClient {

  /**
   * Sends a message to Claude and receives the JSON response.
   *
   * @param modelId the model identifier, e.g. "claude-sonnet-4-6" or "claude-haiku-4-5"
   * @param messages the message history for the API call
   * @param timeout the request timeout; must be positive
   * @return the API response as a JsonNode
   * @throws AnthropicClientException on any error (network, timeout, provider error, parsing)
   */
  JsonNode sendMessage(String modelId, AnthropicMessage[] messages, Duration timeout)
      throws AnthropicClientException;

  /**
   * Container for a request/response error. Carries the failure classification so callers can
   * decide whether to retry.
   */
  class AnthropicClientException extends Exception {

    private final AiErrorCode errorCode;
    private final boolean retryable;

    public AnthropicClientException(
        String message, AiErrorCode errorCode, boolean retryable, Throwable cause) {
      super(message, cause);
      this.errorCode = errorCode;
      this.retryable = retryable;
    }

    public AnthropicClientException(String message, AiErrorCode errorCode, boolean retryable) {
      super(message);
      this.errorCode = errorCode;
      this.retryable = retryable;
    }

    public AiErrorCode errorCode() {
      return errorCode;
    }

    public boolean isRetryable() {
      return retryable;
    }
  }
}
