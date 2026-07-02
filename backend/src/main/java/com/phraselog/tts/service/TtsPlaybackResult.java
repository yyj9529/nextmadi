package com.phraselog.tts.service;

import java.util.UUID;

/**
 * {@link TtsPlaybackService#playback}의 결과.
 *
 * <p>REST 컨트롤러와 S12 {@code PracticeAudioService} 양쪽이 각자의 응답 타입으로 어댑트한다. {@code cacheStatus}는 {@code
 * "hit"} 또는 {@code "miss"}.
 */
public record TtsPlaybackResult(
    UUID ttsAudioCacheId, String audioUrl, Integer durationMs, String cacheStatus) {

  public static final String HIT = "hit";
  public static final String MISS = "miss";
}
