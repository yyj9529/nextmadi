package com.phraselog.expression.dto;

/** One variant copied from {@code analysis_requests.output_json.expressions}. */
public record NewExpressionVariant(
    int variantOrder,
    String toneLabel,
    String englishText,
    String ipa,
    String koreanPronunciation,
    String pronunciationTip,
    String culturalTip) {}
