package com.phraselog.transcription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.client.service.OpenAiTranscriptionClient;
import com.phraselog.ai.client.service.OpenAiTranscriptionResult;
import com.phraselog.ai.logging.dto.AiErrorCode;
import com.phraselog.ai.logging.dto.AiFeature;
import com.phraselog.ai.logging.dto.AiRequestLogEntry;
import com.phraselog.ai.logging.dto.AiRequestStatus;
import com.phraselog.ai.logging.repository.AiRequestLogStore;
import com.phraselog.ai.logging.service.AiCostCalculator;
import com.phraselog.ai.logging.service.AiRequestLogger;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.transcription.WebmTestFixtures;
import com.phraselog.transcription.dto.TranscriptionResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class TranscriptionServiceTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void transcribesValidWebmAndLogsSuccessWithoutRawTranscript() throws Exception {
    RecordingLogStore logStore = new RecordingLogStore();
    FakeOpenAiClient client =
        FakeOpenAiClient.returning(
            MAPPER.readTree(
                """
                {
                  "text": " Could you repeat that? ",
                  "usage": { "type": "duration", "seconds": 3.2 },
                  "segments": [
                    { "avg_logprob": -0.2 },
                    { "avg_logprob": -0.5 }
                  ]
                }
                """));
    TranscriptionService service = service(client, logStore);
    UUID userId = UUID.randomUUID();

    TranscriptionResponse response =
        service.transcribe(
            InternalAuthPrincipal.ofUser(userId.toString()), audio(WebmTestFixtures.webmOpus(3.0)));

    assertThat(response.transcript()).isEqualTo("Could you repeat that?");
    assertThat(response.sttConfidence()).isEqualByComparingTo("0.607");
    assertThat(client.calls()).isEqualTo(1);
    assertThat(logStore.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.feature()).isEqualTo(AiFeature.STT_TRANSCRIPTION);
              assertThat(entry.modelName()).isEqualTo("whisper-1");
              assertThat(entry.promptVersion()).isNull();
              assertThat(entry.userId()).isEqualTo(userId);
              assertThat(entry.status()).isEqualTo(AiRequestStatus.SUCCESS);
              assertThat(entry.estimatedCostUsd()).isEqualByComparingTo("0.000320");
              assertThat(entry.requestCorrelationId()).isNotNull();
            });
  }

  @Test
  void rejectsInvalidAudioBeforeCallingProvider() {
    RecordingLogStore logStore = new RecordingLogStore();
    FakeOpenAiClient client = FakeOpenAiClient.returning(MAPPER.createObjectNode());
    TranscriptionService service = service(client, logStore);

    assertThatThrownBy(
            () -> service.transcribe(InternalAuthPrincipal.ofSession("anon"), audio(new byte[0])))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error -> {
              ApiErrorException api = (ApiErrorException) error;
              assertThat(api.status().value()).isEqualTo(400);
              assertThat(api.errorCode()).isEqualTo("validation_failed");
            });

    assertThat(client.calls()).isZero();
    assertThat(logStore.entries()).isEmpty();
  }

  @Test
  void providerFailuresLogOneFailureRowAndMapToApiError() throws Exception {
    RecordingLogStore logStore = new RecordingLogStore();
    FakeOpenAiClient client =
        FakeOpenAiClient.throwing(
            new OpenAiTranscriptionClient.OpenAiTranscriptionException(
                "OpenAI rate limited", AiErrorCode.PROVIDER_429, true));
    TranscriptionService service = service(client, logStore);

    assertThatThrownBy(
            () ->
                service.transcribe(
                    InternalAuthPrincipal.ofSession("anon"), audio(WebmTestFixtures.webmOpus(4.0))))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error -> {
              ApiErrorException api = (ApiErrorException) error;
              assertThat(api.status().value()).isEqualTo(429);
              assertThat(api.errorCode()).isEqualTo("provider_429");
              assertThat(api.retryable()).isTrue();
            });

    assertThat(logStore.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.userId()).isNull();
              assertThat(entry.feature()).isEqualTo(AiFeature.STT_TRANSCRIPTION);
              assertThat(entry.status()).isEqualTo(AiRequestStatus.ERROR);
              assertThat(entry.errorCode()).isEqualTo(AiErrorCode.PROVIDER_429);
            });
  }

  // 무발화는 규격 오류(400)와 다른 실패다. 클라이언트가 "다시 녹음" 대신 "다시 말해보기"로
  // 안내해야 하므로 422 empty_transcript 로 구분해서 내린다(s02.md UI states).
  @Test
  void blankProviderTranscriptReturnsEmptyTranscriptAndLogsError() throws Exception {
    RecordingLogStore logStore = new RecordingLogStore();
    FakeOpenAiClient client = FakeOpenAiClient.returning(MAPPER.readTree("{\"text\":\"   \"}"));
    TranscriptionService service = service(client, logStore);

    assertThatThrownBy(
            () ->
                service.transcribe(
                    InternalAuthPrincipal.ofSession("anon"), audio(WebmTestFixtures.webmOpus(4.0))))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error -> {
              ApiErrorException api = (ApiErrorException) error;
              assertThat(api.status().value()).isEqualTo(422);
              assertThat(api.errorCode()).isEqualTo("empty_transcript");
              // 같은 오디오를 다시 보내도 결과가 같다.
              assertThat(api.retryable()).isFalse();
            });

    assertThat(logStore.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.status()).isEqualTo(AiRequestStatus.ERROR);
              assertThat(entry.errorCode()).isEqualTo(AiErrorCode.UNKNOWN);
              // 무발화라도 Whisper 과금은 발생했다 — 비용이 null 이면 남용이 로그에서 보이지 않는다.
              assertThat(entry.estimatedCostUsd()).isNotNull();
              assertThat(entry.estimatedCostUsd()).isGreaterThan(java.math.BigDecimal.ZERO);
            });
  }

  // 진짜 규격 오류는 계속 400 validation_failed 여야 한다 — 위 422와 갈라지는 것이 이 변경의 요점이다.
  @Test
  void malformedAudioStillReturnsValidationFailed() {
    RecordingLogStore logStore = new RecordingLogStore();
    TranscriptionService service = service(FakeOpenAiClient.returning(null), logStore);

    assertThatThrownBy(
            () ->
                service.transcribe(
                    InternalAuthPrincipal.ofSession("anon"), audio("not a webm".getBytes())))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error -> {
              ApiErrorException api = (ApiErrorException) error;
              assertThat(api.status().value()).isEqualTo(400);
              assertThat(api.errorCode()).isEqualTo("validation_failed");
            });
  }

  @Test
  void transcribeCanUseCallerSuppliedCorrelationIdForInlineRoleplayTurns() throws Exception {
    RecordingLogStore logStore = new RecordingLogStore();
    FakeOpenAiClient client =
        FakeOpenAiClient.returning(
            MAPPER.readTree(
                """
                {
                  "text": "One more time, please.",
                  "usage": { "type": "duration", "seconds": 2.0 }
                }
                """));
    TranscriptionService service = service(client, logStore);
    UUID userId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();

    service.transcribe(
        InternalAuthPrincipal.ofUser(userId.toString()),
        audio(WebmTestFixtures.webmOpus(2.0)),
        correlationId);

    assertThat(logStore.entries())
        .singleElement()
        .satisfies(entry -> assertThat(entry.requestCorrelationId()).isEqualTo(correlationId));
  }

  private static TranscriptionService service(
      OpenAiTranscriptionClient client, RecordingLogStore logStore) {
    return new TranscriptionService(
        client, new AiRequestLogger(logStore), new AiCostCalculator(), new WebmOpusInspector());
  }

  private static MockMultipartFile audio(byte[] bytes) {
    return new MockMultipartFile("audio", "voice.webm", "audio/webm;codecs=opus", bytes);
  }

  private static final class FakeOpenAiClient implements OpenAiTranscriptionClient {

    private final JsonNode response;
    private final OpenAiTranscriptionException exception;
    private int calls;

    private FakeOpenAiClient(JsonNode response, OpenAiTranscriptionException exception) {
      this.response = response;
      this.exception = exception;
    }

    static FakeOpenAiClient returning(JsonNode response) {
      return new FakeOpenAiClient(response, null);
    }

    static FakeOpenAiClient throwing(OpenAiTranscriptionException exception) {
      return new FakeOpenAiClient(null, exception);
    }

    @Override
    public OpenAiTranscriptionResult transcribe(
        byte[] audioBytes, String filename, String contentType, Duration timeout)
        throws OpenAiTranscriptionException {
      calls++;
      if (exception != null) {
        throw exception;
      }
      return OpenAiTranscriptionResult.fromJson(response);
    }

    int calls() {
      return calls;
    }
  }

  private static final class RecordingLogStore implements AiRequestLogStore {

    private final List<AiRequestLogEntry> entries = new ArrayList<>();

    @Override
    public void save(AiRequestLogEntry entry) {
      entries.add(entry);
    }

    List<AiRequestLogEntry> entries() {
      return entries;
    }
  }
}
