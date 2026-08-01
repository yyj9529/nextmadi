package com.phraselog.ai.client.service;

import com.phraselog.ai.client.dto.AnthropicMessage;
import com.phraselog.ai.client.dto.AnthropicResponse;
import com.phraselog.ai.logging.dto.AiErrorCode;
import java.time.Duration;

/** HTTP client for Anthropic Claude API. Handles request/response, timeouts, and error handling. */
public interface AnthropicClient {

  /**
   * Sends a message to Claude and receives the model output together with its token accounting.
   *
   * @param modelId the model identifier, e.g. "claude-sonnet-4-6" or "claude-haiku-4-5"
   * @param messages the message history for the API call
   * @param timeout the request timeout; must be positive and must be applied to the actual HTTP
   *     call, not merely accepted (see {@code RestClientAnthropicClient})
   * @return the parsed payload plus reported usage; see {@link AnthropicResponse}
   * @throws AnthropicClientException on any error (network, timeout, provider error, parsing)
   */
  AnthropicResponse sendMessage(String modelId, AnthropicMessage[] messages, Duration timeout)
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
