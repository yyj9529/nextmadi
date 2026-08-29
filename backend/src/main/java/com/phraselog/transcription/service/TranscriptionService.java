package com.phraselog.transcription.service;

import com.phraselog.ai.client.service.OpenAiTranscriptionClient;
import com.phraselog.ai.client.service.OpenAiTranscriptionResult;
import com.phraselog.ai.logging.dto.AiErrorCode;
import com.phraselog.ai.logging.dto.AiFeature;
import com.phraselog.ai.logging.dto.AiRequestLogEntry;
import com.phraselog.ai.logging.dto.AiRequestStatus;
import com.phraselog.ai.logging.service.AiCostCalculator;
import com.phraselog.ai.logging.service.AiRequestLogger;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.transcription.dto.TranscriptionResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TranscriptionService {

  private static final Duration STT_TIMEOUT = Duration.ofSeconds(30);
  private static final String MODEL = "whisper-1";

  private final OpenAiTranscriptionClient client;
  private final AiRequestLogger aiRequestLogger;
  private final AiCostCalculator costCalculator;
  private final WebmOpusInspector inspector;

  public TranscriptionService(
      OpenAiTranscriptionClient client,
      AiRequestLogger aiRequestLogger,
      AiCostCalculator costCalculator,
      WebmOpusInspector inspector) {
    this.client = client;
    this.aiRequestLogger = aiRequestLogger;
    this.costCalculator = costCalculator;
    this.inspector = inspector;
  }

  public TranscriptionResponse transcribe(InternalAuthPrincipal principal, MultipartFile audio) {
    return transcribe(principal, audio, UUID.randomUUID());
  }

  public TranscriptionResponse transcribe(
      InternalAuthPrincipal principal, MultipartFile audio, UUID correlationId) {
    byte[] audioBytes = readAudioBytes(audio);
    WebmOpusInspector.AudioMetadata metadata;
    try {
      metadata = inspector.inspect(audioBytes);
    } catch (WebmOpusInspector.InvalidAudioException e) {
      throw validationFailed(e.getMessage());
    }

    UUID userId = principal.isAuthenticatedUser() ? UUID.fromString(principal.userId()) : null;
    long startTimeMs = System.currentTimeMillis();

    try {
      OpenAiTranscriptionResult result =
          client.transcribe(audioBytes, safeFilename(audio), safeContentType(audio), STT_TIMEOUT);
      long latencyMs = System.currentTimeMillis() - startTimeMs;
      String transcript = result.text() == null ? "" : result.text().trim();
      double billableSeconds = result.usageSeconds().orElse(metadata.durationSeconds());
      BigDecimal estimatedCost = costCalculator.whisperBySeconds(billableSeconds);
      if (!StringUtils.hasText(transcript)) {
        // 무발화라도 Whisper 과금은 이미 발생했다. 비용을 남기지 않으면 남용이 로그에서 보이지 않는다.
        logCall(
            userId,
            correlationId,
            latencyMs,
            AiRequestStatus.ERROR,
            AiErrorCode.UNKNOWN,
            estimatedCost);
        throw emptyTranscript("transcript was blank; no speech detected.");
      }

      logCall(userId, correlationId, latencyMs, AiRequestStatus.SUCCESS, null, estimatedCost);

      return new TranscriptionResponse(transcript, confidence(result));
    } catch (OpenAiTranscriptionClient.OpenAiTranscriptionException e) {
      long latencyMs = System.currentTimeMillis() - startTimeMs;
      logCall(
          userId, correlationId, latencyMs, statusForErrorCode(e.errorCode()), e.errorCode(), null);
      throw toApiError(e.errorCode());
    }
  }

  public static ApiErrorException validationFailed(String developerHint) {
    return new ApiErrorException(
        HttpStatus.BAD_REQUEST, "validation_failed", "입력값을 다시 확인해 주세요.", developerHint, false);
  }

  /**
   * 오디오 자체는 규격에 맞았지만 말소리가 잡히지 않은 경우. 규격 오류(400 validation_failed)와 구분해야 클라이언트가 "다시 녹음" 대신 "다시
   * 말해보기"로 안내할 수 있다(s02.md UI states). 같은 오디오를 재전송해도 결과가 같으므로 retryable 은 false 다.
   */
  public static ApiErrorException emptyTranscript(String developerHint) {
    return new ApiErrorException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "empty_transcript",
        "말소리를 알아듣지 못했어요. 다시 말해볼까요?",
        developerHint,
        false);
  }

  private byte[] readAudioBytes(MultipartFile audio) {
    if (audio == null || audio.isEmpty()) {
      throw validationFailed("audio is required.");
    }
    try {
      return audio.getBytes();
    } catch (IOException e) {
      throw validationFailed("audio could not be read.");
    }
  }

  private String safeFilename(MultipartFile audio) {
    String filename = audio.getOriginalFilename();
    return StringUtils.hasText(filename) ? filename : "audio.webm";
  }

  private String safeContentType(MultipartFile audio) {
    String contentType = audio.getContentType();
    return StringUtils.hasText(contentType) ? contentType : "audio/webm";
  }

  private BigDecimal confidence(OpenAiTranscriptionResult result) {
    if (result.lowestSegmentAvgLogprob().isEmpty()) {
      return null;
    }
    double confidence = Math.exp(result.lowestSegmentAvgLogprob().getAsDouble());
    confidence = Math.max(0.0, Math.min(1.0, confidence));
    return BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP);
  }

  private AiRequestStatus statusForErrorCode(AiErrorCode errorCode) {
    return errorCode == AiErrorCode.TIMEOUT ? AiRequestStatus.TIMEOUT : AiRequestStatus.ERROR;
  }

  private ApiErrorException toApiError(AiErrorCode errorCode) {
    return switch (errorCode) {
      case PROVIDER_5XX ->
          new ApiErrorException(
              HttpStatus.SERVICE_UNAVAILABLE,
              "provider_5xx",
              "서비스가 일시적으로 이용 불가합니다. 잠시 후 다시 시도해주세요.",
              "OpenAI provider returned 5xx error",
              true);
      case PROVIDER_429 ->
          new ApiErrorException(
              HttpStatus.TOO_MANY_REQUESTS,
              "provider_429",
              "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.",
              "OpenAI provider rate limited",
              true);
      case TIMEOUT ->
          new ApiErrorException(
              HttpStatus.REQUEST_TIMEOUT,
              "timeout",
              "응답 시간이 초과되었습니다. 다시 시도해주세요.",
              "OpenAI request timed out",
              true);
      case NETWORK ->
          new ApiErrorException(
              HttpStatus.SERVICE_UNAVAILABLE,
              "network",
              "네트워크 오류가 발생했습니다. 다시 시도해주세요.",
              "Network error calling OpenAI",
              true);
      default ->
          new ApiErrorException(
              HttpStatus.INTERNAL_SERVER_ERROR,
              "internal_server_error",
              "문제가 발생했어요. 잠시 후 다시 시도해 주세요.",
              "Unexpected OpenAI transcription error: " + errorCode,
              false);
    };
  }

  private void logCall(
      UUID userId,
      UUID correlationId,
      long latencyMs,
      AiRequestStatus status,
      AiErrorCode errorCode,
      BigDecimal estimatedCostUsd) {
    aiRequestLogger.log(
        AiRequestLogEntry.builder()
            .userId(userId)
            .feature(AiFeature.STT_TRANSCRIPTION)
            .modelName(MODEL)
            .latencyMs(latencyMs)
            .estimatedCostUsd(estimatedCostUsd)
            .status(status)
            .errorCode(errorCode)
            .requestCorrelationId(correlationId)
            .build());
  }
}
