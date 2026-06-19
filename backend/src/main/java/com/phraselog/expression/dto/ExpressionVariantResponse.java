package com.phraselog.expression.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** One persisted {@code expression_variants} row in the Expression response. */
public record ExpressionVariantResponse(
    UUID id,
    @JsonProperty("variant_order") int variantOrder,
    @JsonProperty("tone_label") String toneLabel,
    @JsonProperty("english_text") String englishText,
    String ipa,
    @JsonProperty("korean_pronunciation") String koreanPronunciation,
    @JsonProperty("pronunciation_tip") String pronunciationTip,
    @JsonProperty("cultural_tip") String culturalTip,
    @JsonProperty("tts_audio_url") String ttsAudioUrl) {}
