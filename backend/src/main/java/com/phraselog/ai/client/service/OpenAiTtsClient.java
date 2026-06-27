package com.phraselog.ai.client.service;

import com.phraselog.ai.logging.dto.AiErrorCode;
import java.time.Duration;

/**
 * OpenAI TTS(text-to-speech) 합성 클라이언트. (#30)
 *
 * <p>{@link OpenAiTranscriptionClient}와 동일한 예외/에러코드 계약을 따른다 — 구현체는 provider
 * 5xx/429/timeout/network를 {@link AiErrorCode}로 분류해 {@link OpenAiTtsException}으로 던진다.
 */
public interface OpenAiTtsClient {

  /**
   * {@code input} 텍스트를 {@code voice}로 합성해 MP3 바이트를 반환한다.
   *
   * @param model OpenAI TTS 모델 id (예: {@code tts-1})
   * @param voice OpenAI voice id ({@code coach_profiles.tts_voice_id})
   * @param input 합성할 텍스트
   * @param timeout connect/read 타임아웃
   * @return MP3 오디오 바이트
   */
  byte[] synthesize(String model, String voice, String input, Duration timeout)
      throws OpenAiTtsException;

  class OpenAiTtsException extends Exception {

    private final AiErrorCode errorCode;
    private final boolean retryable;

    public OpenAiTtsException(String message, AiErrorCode errorCode, boolean retryable) {
      super(message);
      this.errorCode = errorCode;
      this.retryable = retryable;
    }

    public OpenAiTtsException(
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
