package com.phraselog.tts.repository;

import com.phraselog.common.web.ApiErrorException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * DataSource가 없는 컨텍스트(no-DB scaffold)용 {@link TtsAudioCacheRepository} 폴백.
 *
 * <p>{@code UnavailablePracticeTurnRepository}와 동일하게 빈 그래프는 로딩되지만 실제 호출 시 503으로 실패한다.
 */
public final class UnavailableTtsAudioCacheRepository implements TtsAudioCacheRepository {

  @Override
  public Optional<TtsAudioCacheRow> findByKey(String textHash, String voiceId, String modelName) {
    throw unavailable();
  }

  @Override
  public TtsAudioCacheRow insert(InsertTtsCacheCommand command) {
    throw unavailable();
  }

  @Override
  public boolean isLinkableVariant(UUID expressionVariantId, UUID userId, String textContent) {
    throw unavailable();
  }

  @Override
  public boolean linkVariant(
      UUID cacheId, UUID expressionVariantId, UUID userId, String textContent) {
    throw unavailable();
  }

  private static ApiErrorException unavailable() {
    return new ApiErrorException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "service_unavailable",
        "음성 기능을 사용할 수 없어요. 잠시 후 다시 시도해주세요.",
        "TtsAudioCacheRepository is unavailable: no DataSource in this context.",
        true);
  }
}
