package com.phraselog.tts.repository;

import java.time.OffsetDateTime;

/**
 * {@code tts_audio_cache} INSERT 입력.
 *
 * <p>{@code expiresAt}은 보통 {@code null}(무기한) — {@code docs/data-model.md}의 만료 정책 기본 후보와 일치. 객체 수명
 * 정책이 정해지면 채운다.
 */
public record InsertTtsCacheCommand(
    String textHash,
    String textContent,
    String voiceId,
    String modelName,
    String audioS3Key,
    Integer durationMs,
    OffsetDateTime expiresAt) {}
