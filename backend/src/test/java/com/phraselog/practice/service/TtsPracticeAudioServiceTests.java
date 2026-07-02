package com.phraselog.practice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.phraselog.tts.service.TtsPlaybackResult;
import com.phraselog.tts.service.TtsPlaybackService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TtsPracticeAudioServiceTests {

  @Test
  void delegatesToPlaybackServiceAndMapsResult() {
    TtsPlaybackService playback = mock(TtsPlaybackService.class);
    TtsPracticeAudioService service = new TtsPracticeAudioService(playback);

    UUID userId = UUID.randomUUID();
    UUID correlation = UUID.randomUUID();
    UUID cacheId = UUID.randomUUID();
    when(playback.playback(
            eq(userId), eq("Sure, here you go."), eq("onyx"), isNull(), eq(correlation)))
        .thenReturn(new TtsPlaybackResult(cacheId, "https://signed/coach", 1500, "miss"));

    Optional<PracticeAudioResult> result =
        service.synthesize(userId, "Sure, here you go.", "onyx", correlation);

    assertThat(result).isPresent();
    assertThat(result.get().ttsAudioCacheId()).isEqualTo(cacheId);
    assertThat(result.get().audioUrl()).isEqualTo("https://signed/coach");
    verify(playback).playback(userId, "Sure, here you go.", "onyx", null, correlation);
  }
}
