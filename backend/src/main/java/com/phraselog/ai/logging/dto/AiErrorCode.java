package com.phraselog.ai.logging.dto;

/**
 * Failure classification stored in {@code ai_request_logs.error_code}.
 *
 * <p>Wire values match the {@code error_code} enumeration in {@code docs/AI_PIPELINE.md} ("Logging
 * contract"). The codes mirror the failure modes in the timeout/fallback policy so error rates can
 * be sliced per cause.
 */
public enum AiErrorCode {
  SCHEMA_VALIDATION_FAILED("schema_validation_failed"),
  PROVIDER_5XX("provider_5xx"),
  PROVIDER_429("provider_429"),
  TIMEOUT("timeout"),
  NETWORK("network"),
  UNKNOWN("unknown");

  private final String wireName;

  AiErrorCode(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }
}
