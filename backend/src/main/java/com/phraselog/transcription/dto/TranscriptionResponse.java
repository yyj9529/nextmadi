package com.phraselog.transcription.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record TranscriptionResponse(
    String transcript, @JsonProperty("stt_confidence") BigDecimal sttConfidence) {}
