package com.phraselog.transcription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * 실제 Chrome MediaRecorder 가 만든 WebM/Opus 녹음에 대한 회귀 테스트 (#36 단계 0 실측).
 *
 * <p>기존 {@link WebmOpusInspectorTests}는 Duration 을 직접 써넣은 합성 픽스처만 쓴다. 실기기 녹음은 Duration 이 있을 수도, 없을
 * 수도 있어서 두 경우를 픽스처로 고정한다.
 *
 * <p>핵심: MediaRecorder.start() 를 timeslice 없이 부르면 blob 확정 시 헤더의 Segment 크기와 Duration 이 패치되어 검사를
 * 통과한다. timeslice 를 주면 Segment 가 unknown-size 로 남고 Duration 이 아예 기록되지 않아 400 이 된다. 프론트엔드는 timeslice
 * 를 쓰면 안 된다.
 */
class WebmOpusInspectorRealRecordingTests {

  private final WebmOpusInspector inspector = new WebmOpusInspector();

  @Test
  void acceptsRealChromeRecordingWithoutTimeslice() throws IOException {
    byte[] audio = fixture("/fixtures/chrome-mediarecorder.webm");

    WebmOpusInspector.AudioMetadata metadata = inspector.inspect(audio);

    assertThat(metadata.durationSeconds()).isBetween(2.5, 3.5);
  }

  @Test
  void rejectsRealChromeRecordingCapturedWithTimeslice() throws IOException {
    byte[] audio = fixture("/fixtures/chrome-mediarecorder-timeslice.webm");

    // 거부 사유는 duration 부재가 아니라 unknown-size Segment 때문에 파서가 경계에서 먼저 걸린다.
    // 사용자에게는 어느 쪽이든 같은 400 validation_failed 다.
    assertThatThrownBy(() -> inspector.inspect(audio))
        .isInstanceOf(WebmOpusInspector.InvalidAudioException.class)
        .hasMessageContaining("audio must be a valid WebM file");
  }

  private byte[] fixture(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      assertThat(stream).as("fixture %s", path).isNotNull();
      return stream.readAllBytes();
    }
  }
}
