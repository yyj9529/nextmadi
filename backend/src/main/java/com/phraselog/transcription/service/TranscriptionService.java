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
      if (!StringUtils.hasText(transcript)) {
        logCall(userId, correlationId, latencyMs, AiRequestStatus.ERROR, AiErrorCode.UNKNOWN, null);
        throw validationFailed("transcript must not be blank.");
      }

      double billableSeconds = result.usageSeconds().orElse(metadata.durationSeconds());
      BigDecimal estimatedCost = costCalculator.whisperBySeconds(billableSeconds);
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
        HttpStatus.BAD_REQUEST, "validation_failed", "?낅젰媛믪쓣 ?ㅼ떆 ?뺤씤??二쇱꽭??", developerHint, false);
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
              "?쒕퉬?ㅺ? ?쇱떆?곸쑝濡??댁슜 遺덇??⑸땲?? ?좎떆 ???ㅼ떆 ?쒕룄?댁＜?몄슂.",
              "OpenAI provider returned 5xx error",
              true);
      case PROVIDER_429 ->
          new ApiErrorException(
              HttpStatus.TOO_MANY_REQUESTS,
              "provider_429",
              "?붿껌???덈Т 留롮뒿?덈떎. ?좎떆 ???ㅼ떆 ?쒕룄?댁＜?몄슂.",
              "OpenAI provider rate limited",
              true);
      case TIMEOUT ->
          new ApiErrorException(
              HttpStatus.REQUEST_TIMEOUT,
              "timeout",
              "?묐떟 ?쒓컙??珥덇낵?섏뿀?듬땲?? ?ㅼ떆 ?쒕룄?댁＜?몄슂.",
              "OpenAI request timed out",
              true);
      case NETWORK ->
          new ApiErrorException(
              HttpStatus.SERVICE_UNAVAILABLE,
              "network",
              "?ㅽ듃?뚰겕 ?ㅻ쪟媛 諛쒖깮?덉뒿?덈떎. ?ㅼ떆 ?쒕룄?댁＜?몄슂.",
              "Network error calling OpenAI",
              true);
      default ->
          new ApiErrorException(
              HttpStatus.INTERNAL_SERVER_ERROR,
              "internal_server_error",
              "臾몄젣媛 諛쒖깮?덉뼱?? ?좎떆 ???ㅼ떆 ?쒕룄??二쇱꽭??",
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
