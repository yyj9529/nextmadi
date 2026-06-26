package com.phraselog.practice.service;

import java.util.Optional;
import java.util.UUID;

/** Text-only fallback until the backend TTS/cache/S3 service from #30 exists. */
public final class NoopPracticeAudioService implements PracticeAudioService {

  @Override
  public Optional<PracticeAudioResult> synthesize(
      UUID userId, String textContent, String voiceId, UUID requestCorrelationId) {
    return Optional.empty();
  }
}
