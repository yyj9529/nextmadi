package com.phraselog.ai.client.service;

import com.phraselog.ai.logging.dto.AiErrorCode;
import java.time.Duration;

public interface OpenAiTranscriptionClient {

  OpenAiTranscriptionResult transcribe(
      byte[] audioBytes, String filename, String contentType, Duration timeout)
      throws OpenAiTranscriptionException;

  class OpenAiTranscriptionException extends Exception {

    private final AiErrorCode errorCode;
    private final boolean retryable;

    public OpenAiTranscriptionException(String message, AiErrorCode errorCode, boolean retryable) {
      super(message);
      this.errorCode = errorCode;
      this.retryable = retryable;
    }

    public OpenAiTranscriptionException(
        String message, AiErrorCode errorCode, boolean retryable, Throwable cause) {
      super(message, cause);
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
