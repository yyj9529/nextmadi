package com.phraselog.practice.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/** One turn as summarized for the S12b roleplay result prompt (#62). */
public record PracticeResultTurn(
    int turnNumber,
    String speaker,
    String textContent,
    BigDecimal sttConfidence,
    boolean feedbackShown,
    JsonNode feedbackContent) {}
