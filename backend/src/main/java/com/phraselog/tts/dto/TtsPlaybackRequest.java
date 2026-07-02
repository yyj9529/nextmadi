package com.phraselog.tts.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /tts/playback} 요청 본문.
 *
 * <p>{@code expressionVariantId}는 선택 — 주어지면 캐시 미스 시 {@code expression_variants.tts_audio_cache_id}를
 * 링크한다. UUID 파싱/필수값 검증은 컨트롤러에서 수행한다.
 */
public record TtsPlaybackRequest(
    String text,
    @JsonProperty("voice_id") String voiceId,
    @JsonProperty("expression_variant_id") String expressionVariantId) {}
