package com.phraselog.ai.client.service;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link OpenAiTtsClient}의 로컬 전용 스텁. 유료 OpenAI TTS를 호출하지 않고, 재생 가능한 무음 MP3 바이트를 즉시 돌려준다. (#117)
 *
 * <p>{@code openai.mock.enabled=true}({@code application-local.yml})일 때만 배선된다 — {@link
 * RestClientOpenAiTtsClient}는 반대 조건이라 상호배타. {@link MockAnthropicClient}와 같은 패턴.
 *
 * <p>반환 바이트는 실제 MPEG-1 Layer III 무음 프레임이라 브라우저 {@code <audio>}가 재생할 수 있다. 로컬 저장은 {@code
 * LocalFilesystemAudioStorage}(#118)가 처리하므로, 이 둘을 합치면 키/비용 없이 코치 음성 재생 경로가 끝까지 돈다.
 */
@Component
@ConditionalOnProperty(name = "openai.mock.enabled", havingValue = "true")
public class MockOpenAiTtsClient implements OpenAiTtsClient {

  private static final Logger log = LoggerFactory.getLogger(MockOpenAiTtsClient.class);

  /** MPEG-1 Layer III, 128kbps, 44.1kHz mono 프레임 헤더. 페이로드가 0이면 무음으로 디코딩된다. */
  private static final byte[] FRAME_HEADER = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, (byte) 0xC0};

  private static final int FRAME_SIZE = 417; // 144 * 128000 / 44100 (no padding)
  private static final int FRAME_COUNT = 20; // 프레임당 ~26ms → 약 0.5초

  private static final byte[] SILENT_MP3 = buildSilentMp3();

  public MockOpenAiTtsClient() {
    log.warn(
        "MockOpenAiTtsClient ACTIVE (openai.mock.enabled=true) — TTS returns canned silent MP3, "
            + "NO real OpenAI cost. Do not use this profile in prod/CI.");
  }

  @Override
  public byte[] synthesize(String model, String voice, String input, Duration timeout) {
    // 입력에 무관하게 동일한 무음 MP3를 반환한다. 호출 측이 바이트를 소유·수정할 수 있으므로 방어적 복사.
    return SILENT_MP3.clone();
  }

  private static byte[] buildSilentMp3() {
    byte[] mp3 = new byte[FRAME_SIZE * FRAME_COUNT];
    for (int frame = 0; frame < FRAME_COUNT; frame++) {
      System.arraycopy(FRAME_HEADER, 0, mp3, frame * FRAME_SIZE, FRAME_HEADER.length);
      // 나머지 바이트는 기본값 0 = 무음 페이로드.
    }
    return mp3;
  }
}
