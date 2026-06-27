package com.phraselog.tts.service;

import com.phraselog.ai.client.service.OpenAiTtsClient;
import com.phraselog.ai.logging.dto.AiErrorCode;
import com.phraselog.ai.logging.dto.AiFeature;
import com.phraselog.ai.logging.dto.AiRequestLogEntry;
import com.phraselog.ai.logging.dto.AiRequestStatus;
import com.phraselog.ai.logging.service.AiCostCalculator;
import com.phraselog.ai.logging.service.AiRequestLogger;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.storage.AudioStorage;
import com.phraselog.tts.repository.InsertTtsCacheCommand;
import com.phraselog.tts.repository.TtsAudioCacheRepository;
import com.phraselog.tts.repository.TtsAudioCacheRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * TTS 합성 + content-hash 캐시의 코어 서비스(#30). REST 엔드포인트({@code POST /tts/playback})와 S12 코치 음성용 {@code
 * PracticeAudioService} 양쪽이 이 서비스를 공유한다.
 *
 * <p>흐름: {@code (sha256(text), voice, model)}로 캐시 조회 → 히트면 OpenAI 호출 없이 서명 URL, 미스면 OpenAI 합성 → S3
 * 업로드 → 캐시 INSERT(+옵션 variant 링크) → 서명 URL. 동시 미스 경합은 결정적 S3 키 덮어쓰기 + INSERT UNIQUE 충돌 재조회로 처리한다(
 * {@code PracticeTurnService.reserveRequest}와 동일한 try-insert/catch/re-select 선례).
 *
 * <p>로깅/에러매핑은 {@code TranscriptionService}와 동일한 패턴을 따른다.
 */
@Service
public class TtsPlaybackService {

  static final String MODEL = "tts-1";
  private static final Duration TTS_TIMEOUT = Duration.ofSeconds(15);
  private static final String AUDIO_CONTENT_TYPE = "audio/mpeg";

  private final OpenAiTtsClient client;
  private final AudioStorage audioStorage;
  private final TtsAudioCacheRepository cacheRepository;
  private final Mp3DurationEstimator durationEstimator;
  private final AiRequestLogger aiRequestLogger;
  private final AiCostCalculator costCalculator;

  public TtsPlaybackService(
      OpenAiTtsClient client,
      AudioStorage audioStorage,
      TtsAudioCacheRepository cacheRepository,
      Mp3DurationEstimator durationEstimator,
      AiRequestLogger aiRequestLogger,
      AiCostCalculator costCalculator) {
    this.client = client;
    this.audioStorage = audioStorage;
    this.cacheRepository = cacheRepository;
    this.durationEstimator = durationEstimator;
    this.aiRequestLogger = aiRequestLogger;
    this.costCalculator = costCalculator;
  }

  public TtsPlaybackResult playback(
      UUID userId, String text, String voiceId, UUID expressionVariantId, UUID correlationId) {
    if (text == null || text.isBlank()) {
      throw validationFailed("text must not be blank.");
    }
    if (voiceId == null || voiceId.isBlank()) {
      throw validationFailed("voice_id must not be blank.");
    }
    UUID correlation = correlationId != null ? correlationId : UUID.randomUUID();
    String hash = sha256Hex(text);

    Optional<TtsAudioCacheRow> cached = cacheRepository.findByKey(hash, voiceId, MODEL);
    if (cached.isPresent()) {
      return servedFromCache(userId, correlation, cached.get());
    }
    return synthesizeAndStore(userId, text, voiceId, expressionVariantId, correlation, hash);
  }

  private TtsPlaybackResult servedFromCache(UUID userId, UUID correlation, TtsAudioCacheRow row) {
    long startTimeMs = System.currentTimeMillis();
    String url = audioStorage.presignGet(row.audioS3Key());
    long latencyMs = System.currentTimeMillis() - startTimeMs;
    logCacheHit(userId, correlation, latencyMs);
    return new TtsPlaybackResult(row.id(), url, row.durationMs(), TtsPlaybackResult.HIT);
  }

  private TtsPlaybackResult synthesizeAndStore(
      UUID userId,
      String text,
      String voiceId,
      UUID expressionVariantId,
      UUID correlation,
      String hash) {
    long startTimeMs = System.currentTimeMillis();
    byte[] mp3;
    try {
      mp3 = client.synthesize(MODEL, voiceId, text, TTS_TIMEOUT);
    } catch (OpenAiTtsClient.OpenAiTtsException e) {
      long latencyMs = System.currentTimeMillis() - startTimeMs;
      logCall(
          userId, correlation, latencyMs, statusForErrorCode(e.errorCode()), e.errorCode(), null);
      throw toApiError(e.errorCode());
    }

    Integer durationMs = estimateDuration(mp3);
    String key = s3Key(voiceId, hash);
    audioStorage.putAudio(key, mp3, AUDIO_CONTENT_TYPE);

    BigDecimal cost = costCalculator.ttsByCharacters(text.length());
    long latencyMs = System.currentTimeMillis() - startTimeMs;
    try {
      TtsAudioCacheRow row =
          cacheRepository.insertAndLink(
              new InsertTtsCacheCommand(hash, text, voiceId, MODEL, key, durationMs, null),
              expressionVariantId);
      logCall(userId, correlation, latencyMs, AiRequestStatus.SUCCESS, null, cost);
      return new TtsPlaybackResult(
          row.id(), audioStorage.presignGet(key), durationMs, TtsPlaybackResult.MISS);
    } catch (DuplicateKeyException race) {
      // 동시 미스 경합에서 진 쪽: OpenAI 호출은 실제 발생했으므로 SUCCESS+비용으로 기록하고(과소집계 방지),
      // 클라이언트에는 승자 행의 URL을 hit으로 돌려준다.
      logCall(userId, correlation, latencyMs, AiRequestStatus.SUCCESS, null, cost);
      TtsAudioCacheRow winner =
          cacheRepository
              .findByKey(hash, voiceId, MODEL)
              .orElseThrow(
                  () ->
                      new ApiErrorException(
                          HttpStatus.INTERNAL_SERVER_ERROR,
                          "internal_server_error",
                          "문제가 발생했어요. 잠시 후 다시 시도해주세요.",
                          "TTS cache row vanished after a duplicate-key race.",
                          true));
      return new TtsPlaybackResult(
          winner.id(),
          audioStorage.presignGet(winner.audioS3Key()),
          winner.durationMs(),
          TtsPlaybackResult.HIT);
    }
  }

  private Integer estimateDuration(byte[] mp3) {
    OptionalInt duration = durationEstimator.estimateDurationMs(mp3);
    return duration.isPresent() ? duration.getAsInt() : null;
  }

  static String s3Key(String voiceId, String hash) {
    return "tts/" + voiceId + "/" + hash + ".mp3";
  }

  static String sha256Hex(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hashed);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required but unavailable", e);
    }
  }

  private static ApiErrorException validationFailed(String developerHint) {
    return new ApiErrorException(
        HttpStatus.BAD_REQUEST, "validation_failed", "입력값을 다시 확인해 주세요.", developerHint, false);
  }

  private static AiRequestStatus statusForErrorCode(AiErrorCode errorCode) {
    return errorCode == AiErrorCode.TIMEOUT ? AiRequestStatus.TIMEOUT : AiRequestStatus.ERROR;
  }

  private static ApiErrorException toApiError(AiErrorCode errorCode) {
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
              "OpenAI TTS request timed out",
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
              "문제가 발생했어요. 잠시 후 다시 시도해주세요.",
              "Unexpected OpenAI TTS error: " + errorCode,
              false);
    };
  }

  private void logCacheHit(UUID userId, UUID correlation, long latencyMs) {
    logCall(userId, correlation, latencyMs, AiRequestStatus.CACHE_HIT, null, BigDecimal.ZERO);
  }

  private void logCall(
      UUID userId,
      UUID correlation,
      long latencyMs,
      AiRequestStatus status,
      AiErrorCode errorCode,
      BigDecimal estimatedCostUsd) {
    aiRequestLogger.log(
        AiRequestLogEntry.builder()
            .userId(userId)
            .feature(AiFeature.TTS_SYNTHESIS)
            .modelName(MODEL)
            .latencyMs(latencyMs)
            .estimatedCostUsd(estimatedCostUsd)
            .status(status)
            .errorCode(errorCode)
            .requestCorrelationId(correlation)
            .build());
  }
}
