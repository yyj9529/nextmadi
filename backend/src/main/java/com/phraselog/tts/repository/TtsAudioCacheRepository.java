package com.phraselog.tts.repository;

import java.util.Optional;
import java.util.UUID;

/** {@code tts_audio_cache} 읽기/쓰기 경계. (#30) */
public interface TtsAudioCacheRepository {

  /** content key {@code (text_hash, voice_id, model_name)}로 캐시 행을 조회한다. */
  Optional<TtsAudioCacheRow> findByKey(String textHash, String voiceId, String modelName);

  /**
   * 캐시 행을 INSERT하고, {@code expressionVariantId}가 있으면 같은 트랜잭션에서 {@code
   * expression_variants.tts_audio_cache_id}를 링크한 뒤, 생성된 행을 반환한다.
   *
   * @throws org.springframework.dao.DuplicateKeyException UNIQUE(text_hash, voice_id, model_name)
   *     충돌 시
   */
  TtsAudioCacheRow insertAndLink(InsertTtsCacheCommand command, UUID expressionVariantId);
}
