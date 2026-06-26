package com.phraselog.practice.service;

import java.util.Optional;
import java.util.UUID;

/** TTS boundary for coach utterances in S12. #30 will replace the no-op implementation. */
public interface PracticeAudioService {

  Optional<PracticeAudioResult> synthesize(
      UUID userId, String textContent, String voiceId, UUID requestCorrelationId);
}
