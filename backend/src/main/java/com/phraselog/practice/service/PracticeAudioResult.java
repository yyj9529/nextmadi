package com.phraselog.practice.service;

import java.util.UUID;

/** Optional coach audio metadata returned by the TTS boundary. */
public record PracticeAudioResult(UUID ttsAudioCacheId, String audioUrl) {}
