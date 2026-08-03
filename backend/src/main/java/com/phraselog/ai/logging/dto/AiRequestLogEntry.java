package com.phraselog.ai.logging.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One immutable {@code ai_request_logs} row, carrying only observability metadata.
 *
 * <p><strong>Privacy guarantee.</strong> This type deliberately has no field for raw user text,
 * audio transcripts, prompt bodies, or model response bodies. The "never log raw user content" rule
 * in {@code SECURITY.md} is enforced structurally: there is nowhere to put such content, so it
 * cannot be persisted by accident. The only free-text-ish values are the enumerated {@code
 * feature_name}/{@code status}/{@code error_code} wire strings, the {@code model_name} identifier,
 * and the {@code prompt_version} label — all non-identifying metadata.
 *
 * <p>{@code inputTokens}/{@code outputTokens} are counts, not content; {@code estimatedCostUsd}
 * uses scale 6 to match {@code NUMERIC(10,6)}. Build instances via {@link #builder()}.
 *
 * <p><strong>Attempt grain (ADR-011).</strong> One instance is one <em>attempt</em>, not one
 * logical call. A call the fallback policy retries produces several entries sharing an {@code
 * attemptGroupId}, numbered from 1, with {@code isFinalAttempt} true on exactly one of them. {@code
 * latencyMs}, the token counts, and {@code estimatedCostUsd} describe that single attempt — never a
 * roll-up — so cost queries sum every row with no attempt filter.
 *
 * <p>Callers that cannot retry (STT, TTS) need not set any of the three: the builder defaults to a
 * fresh single-attempt group, which is exactly what a one-shot call means.
 */
public record AiRequestLogEntry(
    UUID userId,
    AiFeature feature,
    String modelName,
    String promptVersion,
    Integer inputTokens,
    Integer outputTokens,
    long latencyMs,
    BigDecimal estimatedCostUsd,
    AiRequestStatus status,
    AiErrorCode errorCode,
    UUID requestCorrelationId,
    UUID attemptGroupId,
    int attemptNumber,
    boolean isFinalAttempt) {

  public AiRequestLogEntry {
    if (feature == null) {
      throw new IllegalArgumentException("feature is required");
    }
    if (modelName == null || modelName.isBlank()) {
      throw new IllegalArgumentException("modelName is required");
    }
    if (status == null) {
      throw new IllegalArgumentException("status is required");
    }
    if (latencyMs < 0) {
      throw new IllegalArgumentException("latencyMs must be >= 0");
    }
    if (status.isFailure() && errorCode == null) {
      throw new IllegalArgumentException(
          "errorCode is required when status is " + status.wireName());
    }
    if (!status.isFailure() && errorCode != null) {
      throw new IllegalArgumentException(
          "errorCode must be null when status is " + status.wireName());
    }
    if (attemptGroupId == null) {
      throw new IllegalArgumentException("attemptGroupId is required");
    }
    if (attemptNumber < 1) {
      throw new IllegalArgumentException("attemptNumber must be >= 1");
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Fluent builder; only {@code feature}, {@code modelName}, {@code status}, and a non-negative
   * {@code latencyMs} are mandatory. Validation runs in the record's compact constructor.
   *
   * <p>The attempt fields default to a fresh single-attempt group ({@code attemptNumber} 1, {@code
   * isFinalAttempt} true, a generated {@code attemptGroupId}). Only a caller that retries needs to
   * set them, and it must reuse one group id across the attempts of the same logical call.
   */
  public static final class Builder {
    private UUID userId;
    private AiFeature feature;
    private String modelName;
    private String promptVersion;
    private Integer inputTokens;
    private Integer outputTokens;
    private long latencyMs;
    private BigDecimal estimatedCostUsd;
    private AiRequestStatus status;
    private AiErrorCode errorCode;
    private UUID requestCorrelationId;
    private UUID attemptGroupId;
    private int attemptNumber = 1;
    private boolean isFinalAttempt = true;

    private Builder() {}

    public Builder userId(UUID userId) {
      this.userId = userId;
      return this;
    }

    public Builder feature(AiFeature feature) {
      this.feature = feature;
      return this;
    }

    public Builder modelName(String modelName) {
      this.modelName = modelName;
      return this;
    }

    public Builder promptVersion(String promptVersion) {
      this.promptVersion = promptVersion;
      return this;
    }

    public Builder inputTokens(Integer inputTokens) {
      this.inputTokens = inputTokens;
      return this;
    }

    public Builder outputTokens(Integer outputTokens) {
      this.outputTokens = outputTokens;
      return this;
    }

    public Builder latencyMs(long latencyMs) {
      this.latencyMs = latencyMs;
      return this;
    }

    public Builder estimatedCostUsd(BigDecimal estimatedCostUsd) {
      this.estimatedCostUsd = estimatedCostUsd;
      return this;
    }

    public Builder status(AiRequestStatus status) {
      this.status = status;
      return this;
    }

    public Builder errorCode(AiErrorCode errorCode) {
      this.errorCode = errorCode;
      return this;
    }

    public Builder requestCorrelationId(UUID requestCorrelationId) {
      this.requestCorrelationId = requestCorrelationId;
      return this;
    }

    public Builder attemptGroupId(UUID attemptGroupId) {
      this.attemptGroupId = attemptGroupId;
      return this;
    }

    public Builder attemptNumber(int attemptNumber) {
      this.attemptNumber = attemptNumber;
      return this;
    }

    public Builder isFinalAttempt(boolean isFinalAttempt) {
      this.isFinalAttempt = isFinalAttempt;
      return this;
    }

    public AiRequestLogEntry build() {
      return new AiRequestLogEntry(
          userId,
          feature,
          modelName,
          promptVersion,
          inputTokens,
          outputTokens,
          latencyMs,
          estimatedCostUsd,
          status,
          errorCode,
          requestCorrelationId,
          attemptGroupId == null ? UUID.randomUUID() : attemptGroupId,
          attemptNumber,
          isFinalAttempt);
    }
  }
}
