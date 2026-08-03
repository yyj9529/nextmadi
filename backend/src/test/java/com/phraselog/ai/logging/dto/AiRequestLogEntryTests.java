package com.phraselog.ai.logging.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AiRequestLogEntryTests {

  @Test
  void entryExposesOnlyMetadataColumnsAndNoRawContentField() {
    // The privacy guarantee is structural: if a raw-text/transcript/prompt-body field is ever
    // added, this assertion fails and forces a conscious review. Token counts and the prompt
    // VERSION label are metadata, not content.
    Set<String> componentNames =
        Arrays.stream(AiRequestLogEntry.class.getRecordComponents())
            .map(RecordComponent::getName)
            .collect(Collectors.toSet());

    assertThat(componentNames)
        .containsExactlyInAnyOrder(
            "userId",
            "feature",
            "modelName",
            "promptVersion",
            "inputTokens",
            "outputTokens",
            "latencyMs",
            "estimatedCostUsd",
            "status",
            "errorCode",
            "requestCorrelationId",
            // ADR-011 attempt grain. Reviewed against the privacy rule: a group id, an ordinal, and
            // a boolean. Knowing a call was retried reveals nothing about what the user said.
            "attemptGroupId",
            "attemptNumber",
            "isFinalAttempt");
  }

  @Test
  void buildsMinimalSuccessEntry() {
    assertThatCode(
            () ->
                AiRequestLogEntry.builder()
                    .feature(AiFeature.TTS_SYNTHESIS)
                    .modelName("tts-1")
                    .status(AiRequestStatus.CACHE_HIT)
                    .latencyMs(12)
                    .requestCorrelationId(UUID.randomUUID())
                    .build())
        .doesNotThrowAnyException();
  }

  @Test
  void requiresFeature() {
    assertThatThrownBy(
            () ->
                AiRequestLogEntry.builder()
                    .modelName("claude-sonnet-4-6")
                    .status(AiRequestStatus.SUCCESS)
                    .build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresModelName() {
    assertThatThrownBy(
            () ->
                AiRequestLogEntry.builder()
                    .feature(AiFeature.S07_ANALYSIS)
                    .status(AiRequestStatus.SUCCESS)
                    .build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNegativeLatency() {
    assertThatThrownBy(
            () ->
                AiRequestLogEntry.builder()
                    .feature(AiFeature.S07_ANALYSIS)
                    .modelName("claude-sonnet-4-6")
                    .status(AiRequestStatus.SUCCESS)
                    .latencyMs(-1)
                    .build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void failureStatusRequiresErrorCode() {
    assertThatThrownBy(
            () ->
                AiRequestLogEntry.builder()
                    .feature(AiFeature.S07_ANALYSIS)
                    .modelName("claude-sonnet-4-6")
                    .status(AiRequestStatus.ERROR)
                    .latencyMs(30000)
                    .build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nonFailureStatusRejectsErrorCode() {
    assertThatThrownBy(
            () ->
                AiRequestLogEntry.builder()
                    .feature(AiFeature.S07_ANALYSIS)
                    .modelName("claude-sonnet-4-6")
                    .status(AiRequestStatus.SUCCESS)
                    .errorCode(AiErrorCode.UNKNOWN)
                    .build())
        .isInstanceOf(IllegalArgumentException.class);
  }
}
