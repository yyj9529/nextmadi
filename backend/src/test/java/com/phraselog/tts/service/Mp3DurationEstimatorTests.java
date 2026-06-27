package com.phraselog.tts.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class Mp3DurationEstimatorTests {

  private final Mp3DurationEstimator estimator = new Mp3DurationEstimator();

  // MPEG1 Layer III, 128kbps, 44100Hz, no padding → 프레임 크기 417바이트.
  private static final int FRAME_SIZE = 417;
  private static final byte[] FRAME_HEADER = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00};

  @Test
  void estimatesDurationOfCbrMp3() {
    int frames = 200;
    byte[] mp3 = cbrMp3(frames, new byte[0]);

    OptionalInt durationMs = estimator.estimateDurationMs(mp3);

    // 바이트 기반 추정: 200*417*8/128000 ≈ 5212ms. 샘플 기반 실제값(200*1152/44100 ≈ 5224ms)에 근접.
    assertThat(durationMs).isPresent();
    assertThat(durationMs.getAsInt()).isBetween(5100, 5300);
  }

  @Test
  void skipsId3v2TagBeforeFirstFrame() {
    byte[] id3 = id3v2Tag(64);
    byte[] mp3 = cbrMp3(200, id3);

    OptionalInt durationMs = estimator.estimateDurationMs(mp3);

    // ID3 바이트는 frameStart 이후만 세므로 길이 추정에 포함되지 않아 태그 없는 경우와 동일.
    assertThat(durationMs).isPresent();
    assertThat(durationMs.getAsInt()).isBetween(5100, 5300);
  }

  @Test
  void returnsEmptyForGarbageBytes() {
    byte[] garbage = new byte[256];
    for (int i = 0; i < garbage.length; i++) {
      garbage[i] = (byte) (i % 17); // 절대 0xFF 프레임 sync가 안 나오게.
    }

    assertThat(estimator.estimateDurationMs(garbage)).isEmpty();
  }

  @Test
  void returnsEmptyForNullOrTooShort() {
    assertThat(estimator.estimateDurationMs(null)).isEmpty();
    assertThat(estimator.estimateDurationMs(new byte[] {(byte) 0xFF})).isEmpty();
  }

  @Test
  void returnsEmptyForFreeFormatBitrateIndex() {
    // bitrate index 0(free) → 추정 불가. byte2 상위 4비트를 0으로.
    byte[] mp3 = new byte[FRAME_SIZE];
    mp3[0] = (byte) 0xFF;
    mp3[1] = (byte) 0xFB;
    mp3[2] = 0x00; // bitrateIndex=0
    mp3[3] = 0x00;

    assertThat(estimator.estimateDurationMs(mp3)).isEmpty();
  }

  private static byte[] cbrMp3(int frames, byte[] prefix) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(prefix);
    for (int f = 0; f < frames; f++) {
      out.writeBytes(FRAME_HEADER);
      out.writeBytes(new byte[FRAME_SIZE - FRAME_HEADER.length]);
    }
    return out.toByteArray();
  }

  private static byte[] id3v2Tag(int bodySize) {
    byte[] tag = new byte[10 + bodySize];
    tag[0] = 'I';
    tag[1] = 'D';
    tag[2] = '3';
    tag[3] = 0x03; // version
    tag[4] = 0x00;
    tag[5] = 0x00; // flags
    // syncsafe size (bodySize, < 128 이므로 마지막 바이트에 들어감).
    tag[6] = 0x00;
    tag[7] = 0x00;
    tag[8] = 0x00;
    tag[9] = (byte) (bodySize & 0x7F);
    return tag;
  }
}
