package com.phraselog.tts.repository;

import java.util.UUID;

/** {@code tts_audio_cache} 한 행의 조회 결과(필요 컬럼만). */
public record TtsAudioCacheRow(
    UUID id,
    String textHash,
    String voiceId,
    String modelName,
    String audioS3Key,
    Integer durationMs) {}
