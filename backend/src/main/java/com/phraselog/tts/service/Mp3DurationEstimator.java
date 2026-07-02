package com.phraselog.tts.service;

import java.util.OptionalInt;
import org.springframework.stereotype.Component;

/**
 * MP3 바이트 스트림의 재생 길이(ms)를 추정한다.
 *
 * <p>OpenAI {@code tts-1}의 mp3 출력은 CBR(constant bit rate)이므로, 첫 프레임 헤더에서 비트레이트를 읽고 오디오 바이트 길이로
 * {@code duration = bytes * 8 / bitrate}를 계산하면 충분히 정확하다. 무거운 디코딩 의존성 없이 헤더만 파싱한다.
 *
 * <p>{@code tts_audio_cache.duration_ms}는 nullable이므로, 헤더 파싱에 실패하면 잘못된 추정치 대신 {@link
 * OptionalInt#empty()}를 돌려 NULL로 남긴다(가짜 값보다 NULL이 안전). {@code WebmOpusInspector}가 {@code
 * TranscriptionService} 옆에 사는 것과 같은 위치 규칙으로, TTS 합성 옆에 둔다.
 */
@Component
public class Mp3DurationEstimator {

  // Layer III 비트레이트 테이블(kbps). index 0=free, 15=bad. 1..14만 유효.
  private static final int[] MPEG1_L3_BITRATE = {
    0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, -1
  };
  private static final int[] MPEG2_L3_BITRATE = {
    0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, -1
  };

  /**
   * @return 추정 재생 길이(ms), 파싱 불가 시 {@link OptionalInt#empty()}
   */
  public OptionalInt estimateDurationMs(byte[] mp3) {
    if (mp3 == null || mp3.length < 4) {
      return OptionalInt.empty();
    }

    int offset = skipId3v2(mp3);
    int frameStart = findFrameSync(mp3, offset);
    if (frameStart < 0) {
      return OptionalInt.empty();
    }

    int b1 = mp3[frameStart + 1] & 0xFF;
    int b2 = mp3[frameStart + 2] & 0xFF;

    int versionBits = (b1 >> 3) & 0x03; // 00=2.5, 01=reserved, 10=2, 11=1
    int layerBits = (b1 >> 1) & 0x03; // 01=Layer III
    if (versionBits == 0b01 || layerBits != 0b01) {
      return OptionalInt.empty(); // reserved version 또는 Layer III가 아님 → 미지원
    }

    int bitrateIndex = (b2 >> 4) & 0x0F;
    int sampleRateIndex = (b2 >> 2) & 0x03;
    if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) {
      return OptionalInt.empty(); // free-format/잘못된 인덱스 → 추정 불가
    }

    int bitrateKbps =
        (versionBits == 0b11) ? MPEG1_L3_BITRATE[bitrateIndex] : MPEG2_L3_BITRATE[bitrateIndex];
    if (bitrateKbps <= 0) {
      return OptionalInt.empty();
    }

    long audioBytes = (long) mp3.length - frameStart;
    // duration(s) = bits / bitrate(bps); *1000 → ms.
    long durationMs = (audioBytes * 8L * 1000L) / ((long) bitrateKbps * 1000L);
    if (durationMs <= 0 || durationMs > Integer.MAX_VALUE) {
      return OptionalInt.empty();
    }
    return OptionalInt.of((int) durationMs);
  }

  /** ID3v2 태그가 있으면 그 길이만큼 건너뛴 오프셋을, 없으면 0을 반환한다. */
  private static int skipId3v2(byte[] mp3) {
    if (mp3.length >= 10 && mp3[0] == 'I' && mp3[1] == 'D' && mp3[2] == '3') {
      // size는 6..9 바이트의 syncsafe integer(각 바이트 하위 7비트) + 헤더 10바이트.
      int size =
          ((mp3[6] & 0x7F) << 21)
              | ((mp3[7] & 0x7F) << 14)
              | ((mp3[8] & 0x7F) << 7)
              | (mp3[9] & 0x7F);
      int offset = size + 10;
      return Math.min(offset, mp3.length);
    }
    return 0;
  }

  /** {@code from}부터 프레임 sync(0xFF, 다음 바이트 상위 3비트 set)를 찾아 그 시작 인덱스를 반환한다. 없으면 -1. */
  private static int findFrameSync(byte[] mp3, int from) {
    for (int i = Math.max(0, from); i + 1 < mp3.length; i++) {
      if ((mp3[i] & 0xFF) == 0xFF && (mp3[i + 1] & 0xE0) == 0xE0) {
        return (i + 3 < mp3.length) ? i : -1;
      }
    }
    return -1;
  }
}
