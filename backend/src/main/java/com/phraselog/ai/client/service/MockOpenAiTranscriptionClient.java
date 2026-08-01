package com.phraselog.ai.client.service;

import java.time.Duration;
import java.util.OptionalDouble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link OpenAiTranscriptionClient}의 로컬 전용 스텁. 유료 Whisper STT를 호출하지 않고, 고정된 한국어 전사문을 즉시 돌려준다.
 * (#117)
 *
 * <p>{@code openai.mock.enabled=true}({@code application-local.yml})일 때만 배선된다 — {@link
 * RestClientOpenAiTranscriptionClient}는 반대 조건이라 상호배타. {@link MockAnthropicClient}와 같은 패턴.
 *
 * <p>{@code usageSeconds}/{@code lowestSegmentAvgLogprob}는 비워 둔다 — {@code TranscriptionService}가 각각
 * 업로드된 오디오 실제 길이(비용용)와 기본 신뢰도로 폴백하므로, mock이 값을 지어낼 필요가 없다.
 */
@Component
@ConditionalOnProperty(name = "openai.mock.enabled", havingValue = "true")
public class MockOpenAiTranscriptionClient implements OpenAiTranscriptionClient {

  private static final Logger log = LoggerFactory.getLogger(MockOpenAiTranscriptionClient.class);

  /** 마이크 → 전사 확인 흐름(S04/S02)을 로컬에서 태우기 위한 고정 전사문. */
  private static final String CANNED_TRANSCRIPT = "[MOCK] 마트에서 계산이 잘못된 걸 정중하게 바로잡고 싶었어요.";

  public MockOpenAiTranscriptionClient() {
    log.warn(
        "MockOpenAiTranscriptionClient ACTIVE (openai.mock.enabled=true) — STT returns a canned "
            + "transcript, NO real OpenAI cost. Do not use this profile in prod/CI.");
  }

  @Override
  public OpenAiTranscriptionResult transcribe(
      byte[] audioBytes, String filename, String contentType, Duration timeout) {
    return new OpenAiTranscriptionResult(
        CANNED_TRANSCRIPT, OptionalDouble.empty(), OptionalDouble.empty());
  }
}
