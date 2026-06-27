package com.phraselog.storage;

import com.phraselog.common.web.ApiErrorException;
import org.springframework.http.HttpStatus;

/**
 * AWS S3가 구성되지 않은 컨텍스트(기본/테스트 프로필)용 {@link AudioStorage} 폴백.
 *
 * <p>빈 그래프는 로딩되지만 TTS 합성 경로가 실제 호출되면 503으로 실패한다 — {@code UnavailablePracticeRepository}와 동일한 패턴.
 */
public final class UnavailableAudioStorage implements AudioStorage {

  @Override
  public void putAudio(String key, byte[] bytes, String contentType) {
    throw unavailable();
  }

  @Override
  public String presignGet(String key) {
    throw unavailable();
  }

  private static ApiErrorException unavailable() {
    return new ApiErrorException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "service_unavailable",
        "음성 기능을 사용할 수 없어요. 잠시 후 다시 시도해주세요.",
        "AudioStorage is unavailable: S3 is not configured in this context.",
        true);
  }
}
