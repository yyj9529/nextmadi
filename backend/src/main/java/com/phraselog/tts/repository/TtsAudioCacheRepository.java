package com.phraselog.tts.repository;

import java.util.Optional;
import java.util.UUID;

/** Boundary for reading and writing {@code tts_audio_cache}. (#30) */
public interface TtsAudioCacheRepository {

  Optional<TtsAudioCacheRow> findByKey(String textHash, String voiceId, String modelName);

  TtsAudioCacheRow insert(InsertTtsCacheCommand command);

  /**
   * Checks whether an expression variant belongs to the authenticated user and has the exact text
   * being synthesized. Used before a provider call so a bad link target cannot create charged work.
   */
  boolean isLinkableVariant(UUID expressionVariantId, UUID userId, String textContent);

  /**
   * Links a cache row to a variant only when the variant still belongs to the same user and text.
   * Returns false if the row was not linked.
   */
  boolean linkVariant(UUID cacheId, UUID expressionVariantId, UUID userId, String textContent);
}
