package com.phraselog.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * One generated expression in the {@code AnalysisRequest} response, matching the {@code
 * ExpressionVariant} schema in {@code openapi.yaml}.
 *
 * <p>At analysis time no {@code expression_variants} rows exist yet (those are created on Save,
 * #expressions), so {@code id} is derived deterministically from {@code (analysis_id,
 * variant_order)} — stable across GETs and unused by the Save flow, which keys off {@code
 * analysis_request_id + selected_variant_order}. {@code ttsAudioUrl} is always null here; audio is
 * fetched on demand via {@code POST /tts/playback}.
 */
public record ExpressionVariantDto(
    UUID id,
    @JsonProperty("variant_order") int variantOrder,
    @JsonProperty("tone_label") String toneLabel,
    @JsonProperty("english_text") String englishText,
    String ipa,
    @JsonProperty("korean_pronunciation") String koreanPronunciation,
    @JsonProperty("pronunciation_tip") String pronunciationTip,
    @JsonProperty("cultural_tip") String culturalTip,
    @JsonProperty("tts_audio_url") String ttsAudioUrl) {}
