package com.phraselog.practice.service;

import com.phraselog.tts.service.TtsPlaybackResult;
import com.phraselog.tts.service.TtsPlaybackService;
import java.util.Optional;
import java.util.UUID;

/**
 * S12 코치 음성용 {@link PracticeAudioService} 실제 구현(#30). {@link TtsPlaybackService} 코어를 그대로 재사용한다.
 *
 * <p>{@code expressionVariantId}는 없음(코치 발화는 변형 표현이 아니라 턴 발화) — 캐시는 content key로만 공유되고, 턴-캐시 링크는 호출자
 * ({@code PracticeTurnService})가 {@code practice_turns.tts_audio_cache_id}에 저장한다.
 */
public final class TtsPracticeAudioService implements PracticeAudioService {

  private final TtsPlaybackService ttsPlaybackService;

  public TtsPracticeAudioService(TtsPlaybackService ttsPlaybackService) {
    this.ttsPlaybackService = ttsPlaybackService;
  }

  @Override
  public Optional<PracticeAudioResult> synthesize(
      UUID userId, String textContent, String voiceId, UUID requestCorrelationId) {
    TtsPlaybackResult result =
        ttsPlaybackService.playback(userId, textContent, voiceId, null, requestCorrelationId);
    return Optional.of(new PracticeAudioResult(result.ttsAudioCacheId(), result.audioUrl()));
  }
}
