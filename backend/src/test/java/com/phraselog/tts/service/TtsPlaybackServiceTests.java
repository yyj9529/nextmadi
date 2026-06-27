package com.phraselog.tts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.phraselog.ai.client.service.OpenAiTtsClient;
import com.phraselog.ai.logging.dto.AiErrorCode;
import com.phraselog.ai.logging.dto.AiRequestLogEntry;
import com.phraselog.ai.logging.dto.AiRequestStatus;
import com.phraselog.ai.logging.service.AiCostCalculator;
import com.phraselog.ai.logging.service.AiRequestLogger;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.storage.AudioStorage;
import com.phraselog.tts.repository.InsertTtsCacheCommand;
import com.phraselog.tts.repository.TtsAudioCacheRepository;
import com.phraselog.tts.repository.TtsAudioCacheRow;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

class TtsPlaybackServiceTests {

  private static final byte[] MP3 = {(byte) 0xFF, (byte) 0xFB, 0x10, 0x00};
  private static final String VOICE = "shimmer";
  private static final String TEXT = "Could I get a coffee?";

  private OpenAiTtsClient client;
  private AudioStorage storage;
  private TtsAudioCacheRepository repository;
  private Mp3DurationEstimator durationEstimator;
  private AiRequestLogger logger;
  private AiCostCalculator costCalculator;
  private TtsPlaybackService service;

  @BeforeEach
  void setUp() {
    client = org.mockito.Mockito.mock(OpenAiTtsClient.class);
    storage = org.mockito.Mockito.mock(AudioStorage.class);
    repository = org.mockito.Mockito.mock(TtsAudioCacheRepository.class);
    durationEstimator = org.mockito.Mockito.mock(Mp3DurationEstimator.class);
    logger = org.mockito.Mockito.mock(AiRequestLogger.class);
    costCalculator = new AiCostCalculator();
    service =
        new TtsPlaybackService(
            client, storage, repository, durationEstimator, logger, costCalculator);
  }

  @Test
  void cacheHitSkipsOpenAiAndLogsCacheHit() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID cacheId = UUID.randomUUID();
    when(repository.findByKey(any(), eq(VOICE), eq("tts-1")))
        .thenReturn(
            Optional.of(
                new TtsAudioCacheRow(
                    cacheId, "hash", VOICE, "tts-1", "tts/shimmer/hash.mp3", 1200)));
    when(storage.presignGet("tts/shimmer/hash.mp3")).thenReturn("https://signed/hit");

    TtsPlaybackResult result = service.playback(userId, TEXT, VOICE, null, UUID.randomUUID());

    assertThat(result.cacheStatus()).isEqualTo("hit");
    assertThat(result.audioUrl()).isEqualTo("https://signed/hit");
    assertThat(result.durationMs()).isEqualTo(1200);
    assertThat(result.ttsAudioCacheId()).isEqualTo(cacheId);
    verifyNoInteractions(client);
    verify(repository, never()).insertAndLink(any(), any());
    assertThat(capturedLog().status()).isEqualTo(AiRequestStatus.CACHE_HIT);
  }

  @Test
  void cacheMissSynthesizesStoresAndLogsSuccessWithCost() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID variantId = UUID.randomUUID();
    UUID cacheId = UUID.randomUUID();
    when(repository.findByKey(any(), eq(VOICE), eq("tts-1"))).thenReturn(Optional.empty());
    when(client.synthesize(eq("tts-1"), eq(VOICE), eq(TEXT), any())).thenReturn(MP3);
    when(durationEstimator.estimateDurationMs(MP3)).thenReturn(OptionalInt.of(900));
    when(repository.insertAndLink(any(), eq(variantId)))
        .thenAnswer(
            inv -> {
              InsertTtsCacheCommand cmd = inv.getArgument(0);
              return new TtsAudioCacheRow(
                  cacheId, cmd.textHash(), VOICE, "tts-1", cmd.audioS3Key(), cmd.durationMs());
            });
    when(storage.presignGet(any())).thenReturn("https://signed/miss");

    TtsPlaybackResult result = service.playback(userId, TEXT, VOICE, variantId, UUID.randomUUID());

    assertThat(result.cacheStatus()).isEqualTo("miss");
    assertThat(result.audioUrl()).isEqualTo("https://signed/miss");
    assertThat(result.durationMs()).isEqualTo(900);

    ArgumentCaptor<InsertTtsCacheCommand> cmd =
        ArgumentCaptor.forClass(InsertTtsCacheCommand.class);
    verify(repository).insertAndLink(cmd.capture(), eq(variantId));
    assertThat(cmd.getValue().audioS3Key()).startsWith("tts/shimmer/");
    assertThat(cmd.getValue().modelName()).isEqualTo("tts-1");
    verify(storage).putAudio(eq(cmd.getValue().audioS3Key()), eq(MP3), eq("audio/mpeg"));

    AiRequestLogEntry log = capturedLog();
    assertThat(log.status()).isEqualTo(AiRequestStatus.SUCCESS);
    assertThat(log.estimatedCostUsd())
        .isEqualByComparingTo(costCalculator.ttsByCharacters(TEXT.length()));
  }

  @Test
  void nullDurationPropagatesWhenEstimatorEmpty() throws Exception {
    when(repository.findByKey(any(), any(), any())).thenReturn(Optional.empty());
    when(client.synthesize(any(), any(), any(), any())).thenReturn(MP3);
    when(durationEstimator.estimateDurationMs(MP3)).thenReturn(OptionalInt.empty());
    when(repository.insertAndLink(any(), any()))
        .thenAnswer(
            inv -> {
              InsertTtsCacheCommand cmd = inv.getArgument(0);
              return new TtsAudioCacheRow(
                  UUID.randomUUID(),
                  cmd.textHash(),
                  VOICE,
                  "tts-1",
                  cmd.audioS3Key(),
                  cmd.durationMs());
            });
    when(storage.presignGet(any())).thenReturn("https://signed/miss");

    TtsPlaybackResult result = service.playback(UUID.randomUUID(), TEXT, VOICE, null, null);

    assertThat(result.durationMs()).isNull();
  }

  @Test
  void duplicateKeyRaceReturnsWinnerAsHit() throws Exception {
    UUID winnerId = UUID.randomUUID();
    when(repository.findByKey(any(), eq(VOICE), eq("tts-1")))
        .thenReturn(Optional.empty()) // 첫 조회: 미스
        .thenReturn(
            Optional.of(
                new TtsAudioCacheRow(
                    winnerId, "hash", VOICE, "tts-1", "tts/shimmer/hash.mp3", 700))); // 재조회: 승자
    when(client.synthesize(any(), any(), any(), any())).thenReturn(MP3);
    when(durationEstimator.estimateDurationMs(MP3)).thenReturn(OptionalInt.of(700));
    when(repository.insertAndLink(any(), any())).thenThrow(new DuplicateKeyException("race"));
    when(storage.presignGet("tts/shimmer/hash.mp3")).thenReturn("https://signed/winner");

    TtsPlaybackResult result = service.playback(UUID.randomUUID(), TEXT, VOICE, null, null);

    assertThat(result.cacheStatus()).isEqualTo("hit");
    assertThat(result.ttsAudioCacheId()).isEqualTo(winnerId);
    assertThat(result.audioUrl()).isEqualTo("https://signed/winner");
    // 진 쪽도 OpenAI 호출은 발생했으므로 SUCCESS로 기록.
    assertThat(capturedLog().status()).isEqualTo(AiRequestStatus.SUCCESS);
  }

  @Test
  void providerTimeoutMapsToTimeoutApiErrorAndLogsFailure() throws Exception {
    when(repository.findByKey(any(), any(), any())).thenReturn(Optional.empty());
    when(client.synthesize(any(), any(), any(), any()))
        .thenThrow(new OpenAiTtsClient.OpenAiTtsException("timeout", AiErrorCode.TIMEOUT, true));

    assertThatThrownBy(() -> service.playback(UUID.randomUUID(), TEXT, VOICE, null, null))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            e ->
                assertThat(((ApiErrorException) e).status()).isEqualTo(HttpStatus.REQUEST_TIMEOUT));

    AiRequestLogEntry log = capturedLog();
    assertThat(log.status()).isEqualTo(AiRequestStatus.TIMEOUT);
    assertThat(log.errorCode()).isEqualTo(AiErrorCode.TIMEOUT);
  }

  @Test
  void blankTextFailsValidationWithoutCallingProvider() {
    assertThatThrownBy(() -> service.playback(UUID.randomUUID(), "  ", VOICE, null, null))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            e -> assertThat(((ApiErrorException) e).errorCode()).isEqualTo("validation_failed"));
    verifyNoInteractions(client);
  }

  private AiRequestLogEntry capturedLog() {
    ArgumentCaptor<AiRequestLogEntry> captor = ArgumentCaptor.forClass(AiRequestLogEntry.class);
    verify(logger).log(captor.capture());
    return captor.getValue();
  }
}
