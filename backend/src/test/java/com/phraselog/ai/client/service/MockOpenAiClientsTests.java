package com.phraselog.ai.client.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** #117 로컬 OpenAI mock 스텁 동작 검증. */
class MockOpenAiClientsTests {

  @Test
  void ttsMockReturnsNonEmptyMp3WithValidFrameHeader() throws Exception {
    MockOpenAiTtsClient tts = new MockOpenAiTtsClient();

    byte[] audio = tts.synthesize("tts-1", "shimmer", "anything", Duration.ofSeconds(15));

    assertThat(audio).isNotEmpty();
    // MPEG-1 Layer III 프레임 sync: 0xFF 다음 바이트 상위 3비트(0xE0)가 세팅돼 있어야 한다.
    assertThat(audio[0]).isEqualTo((byte) 0xFF);
    assertThat(audio[1] & 0xE0).isEqualTo(0xE0);
  }

  @Test
  void ttsMockReturnsDefensiveCopy() throws Exception {
    MockOpenAiTtsClient tts = new MockOpenAiTtsClient();

    byte[] first = tts.synthesize("tts-1", "shimmer", "a", Duration.ofSeconds(15));
    first[0] = 0x00; // 호출자가 바이트를 수정해도
    byte[] second = tts.synthesize("tts-1", "shimmer", "a", Duration.ofSeconds(15));

    assertThat(second[0]).isEqualTo((byte) 0xFF); // 다음 반환은 오염되지 않는다.
  }

  @Test
  void transcriptionMockReturnsNonBlankTranscript() throws Exception {
    MockOpenAiTranscriptionClient stt = new MockOpenAiTranscriptionClient();

    OpenAiTranscriptionResult result =
        stt.transcribe(new byte[] {1, 2, 3}, "clip.webm", "audio/webm", Duration.ofSeconds(30));

    assertThat(result.text()).isNotBlank();
    assertThat(result.usageSeconds()).isEmpty();
    assertThat(result.lowestSegmentAvgLogprob()).isEmpty();
  }
}
