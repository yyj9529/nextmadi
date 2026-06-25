package com.phraselog.transcription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.phraselog.transcription.WebmTestFixtures;
import org.junit.jupiter.api.Test;

class WebmOpusInspectorTests {

  private final WebmOpusInspector inspector = new WebmOpusInspector();

  @Test
  void acceptsBrowserWebmOpusAndReturnsDuration() {
    WebmOpusInspector.AudioMetadata metadata = inspector.inspect(WebmTestFixtures.webmOpus(12.5));

    assertThat(metadata.durationSeconds()).isEqualTo(12.5);
  }

  @Test
  void rejectsAudioLongerThanSixtySeconds() {
    assertThatThrownBy(() -> inspector.inspect(WebmTestFixtures.webmOpus(60.001)))
        .isInstanceOf(WebmOpusInspector.InvalidAudioException.class)
        .hasMessageContaining("at most 60 seconds");
  }

  @Test
  void rejectsEmptyAudio() {
    assertThatThrownBy(() -> inspector.inspect(new byte[0]))
        .isInstanceOf(WebmOpusInspector.InvalidAudioException.class)
        .hasMessageContaining("audio is required");
  }

  @Test
  void rejectsNonWebmBytes() {
    assertThatThrownBy(() -> inspector.inspect(new byte[] {1, 2, 3, 4}))
        .isInstanceOf(WebmOpusInspector.InvalidAudioException.class)
        .hasMessageContaining("WebM");
  }

  @Test
  void rejectsWebmWithoutOpusTrack() {
    assertThatThrownBy(() -> inspector.inspect(WebmTestFixtures.webmWithCodec(10, "A_AAC")))
        .isInstanceOf(WebmOpusInspector.InvalidAudioException.class)
        .hasMessageContaining("Opus");
  }

  @Test
  void rejectsWebmWithoutDurationMetadata() {
    assertThatThrownBy(() -> inspector.inspect(WebmTestFixtures.webmWithoutDuration()))
        .isInstanceOf(WebmOpusInspector.InvalidAudioException.class)
        .hasMessageContaining("duration");
  }
}
