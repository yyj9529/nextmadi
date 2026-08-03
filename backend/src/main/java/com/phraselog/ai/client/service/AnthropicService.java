package com.phraselog.ai.client.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.phraselog.ai.client.config.FeatureRouting;
import com.phraselog.ai.client.dto.AnthropicMessage;
import com.phraselog.ai.client.dto.AnthropicResponse;
import com.phraselog.ai.logging.dto.AiErrorCode;
import com.phraselog.ai.logging.dto.AiFeature;
import com.phraselog.ai.logging.dto.AiRequestLogEntry;
import com.phraselog.ai.logging.dto.AiRequestStatus;
import com.phraselog.ai.logging.service.AiCostCalculator;
import com.phraselog.ai.logging.service.AiRequestLogger;
import com.phraselog.ai.prompt.dto.PromptDefinition;
import com.phraselog.common.web.ApiErrorException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** High-level orchestrator for Claude calls in the AI pipeline (ticket #26). */
@Service
public class AnthropicService {

  private static final Logger log = LoggerFactory.getLogger(AnthropicService.class);

  private final AnthropicClient client;
  private final AiRequestLogger aiRequestLogger;
  private final AiCostCalculator costCalculator;
  private final JsonSchemaValidator schemaValidator;

  /**
   * Seam for the inter-attempt backoff. Defaults to {@link Thread#sleep(long)}; tests inject a fake
   * so the 429 1s/3s schedule can be verified without real waiting.
   */
  private Sleeper sleeper = Thread::sleep;

  public AnthropicService(
      AnthropicClient client,
      AiRequestLogger aiRequestLogger,
      AiCostCalculator costCalculator,
      JsonSchemaValidator schemaValidator) {
    this.client = client;
    this.aiRequestLogger = aiRequestLogger;
    this.costCalculator = costCalculator;
    this.schemaValidator = schemaValidator;
  }

  /** Test-only: override the backoff sleeper so retry schedules run without real delays. */
  void setSleeper(Sleeper sleeper) {
    this.sleeper = sleeper;
  }

  /** Backoff seam; records the millis it was asked to wait so tests can assert the schedule. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }

  public JsonNode callClaude(
      AiFeature feature,
      PromptDefinition prompt,
      String userContent,
      UUID userId,
      UUID correlationId)
      throws ApiErrorException {

    String modelId = FeatureRouting.getModelForFeature(feature);
    String outputSchema = prompt.outputSchema();
    AnthropicMessage[] baseMessages = buildMessages(prompt.body(), userContent);

    log.debug(
        "Calling Claude feature={} model={} output_schema={}",
        feature.wireName(),
        modelId,
        outputSchema);

    // Retry loop per the ticket #26 fallback matrix. The retry SCHEDULE is fixed by the first
    // failure (deterministic), while the caller-visible error_code reflects the last attempt.
    //
    // Logging is per ATTEMPT (ADR-011): every iteration writes its own row, sharing one
    // attemptGroupId, so the tokens burned by a failed attempt stay in the cost sums and the retry
    // rate is countable in SQL. Exactly one row per group carries isFinalAttempt.
    UUID attemptGroupId = UUID.randomUUID();
    AiErrorCode firstError = null;
    AiErrorCode lastError = null;
    int attempt = 0;

    while (true) {
      // A schema failure retries with the constraint reminder appended exactly once; transport
      // retries (5xx/429/network) resend the original messages unchanged.
      AnthropicMessage[] messages =
          lastError == AiErrorCode.SCHEMA_VALIDATION_FAILED
              ? withConstraintReminder(baseMessages, outputSchema)
              : baseMessages;

      int attemptNumber = attempt + 1;
      long attemptStartMs = System.currentTimeMillis();
      long attemptLatencyMs;
      // A schema failure still received a billable response, so its tokens must survive into the
      // log row. A transport failure has no response and leaves these null — "unknown", not zero.
      Integer inputTokens = null;
      Integer outputTokens = null;

      try {

        AnthropicResponse response =
            client.sendMessage(modelId, messages, FeatureRouting.getTimeoutForFeature(feature));
        inputTokens = response.inputTokens();
        outputTokens = response.outputTokens();
        schemaValidator.validate(outputSchema, response.payload());

        logAttempt(
            feature,
            modelId,
            prompt.promptVersion(),
            inputTokens,
            outputTokens,
            System.currentTimeMillis() - attemptStartMs,
            AiRequestStatus.SUCCESS,
            null,
            userId,
            correlationId,
            cost(modelId, inputTokens, outputTokens),
            attemptGroupId,
            attemptNumber,
            true);
        return response.payload();

      } catch (AnthropicClient.AnthropicClientException e) {
        attemptLatencyMs = System.currentTimeMillis() - attemptStartMs;
        lastError = e.errorCode();
        inputTokens = null;
        outputTokens = null;
        if (!e.isRetryable()) {
          logAttempt(
              feature,
              modelId,
              prompt.promptVersion(),
              null,
              null,
              attemptLatencyMs,
              statusForErrorCode(lastError),
              lastError,
              userId,
              correlationId,
              null,
              attemptGroupId,
              attemptNumber,
              true);
          throw toApiError(lastError);
        }
      } catch (JsonSchemaValidator.JsonSchemaValidationException e) {
        attemptLatencyMs = System.currentTimeMillis() - attemptStartMs;
        lastError = AiErrorCode.SCHEMA_VALIDATION_FAILED;
      }

      if (firstError == null) {
        firstError = lastError;
      }

      OptionalLong backoffMs = retryDelayMs(firstError, attempt);
      boolean willRetry = backoffMs.isPresent();

      if (willRetry) {
        log.warn(
            "Retryable error calling Claude ({}), retry attempt {} after {}ms",
            lastError,
            attemptNumber,
            backoffMs.getAsLong());
        try {
          sleeper.sleep(backoffMs.getAsLong());
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          // No further attempt will be made, so this one is the final attempt after all. Deciding
          // that before writing the row keeps "exactly one is_final_attempt per group" true even
          // on shutdown.
          willRetry = false;
        }
      }

      logAttempt(
          feature,
          modelId,
          prompt.promptVersion(),
          inputTokens,
          outputTokens,
          attemptLatencyMs,
          statusForErrorCode(lastError),
          lastError,
          userId,
          correlationId,
          cost(modelId, inputTokens, outputTokens),
          attemptGroupId,
          attemptNumber,
          !willRetry);

      if (!willRetry) {
        break;
      }
      attempt++;
    }

    throw toApiError(lastError);
  }

  /** Cost of one attempt; null when the provider reported no usage, which means unknown. */
  private BigDecimal cost(String modelId, Integer inputTokens, Integer outputTokens) {
    return (inputTokens != null && outputTokens != null)
        ? costCalculator.llm(modelId, inputTokens, outputTokens)
        : null;
  }

  /**
   * Backoff schedule for a retry, keyed by the first failure and the zero-based retry attempt.
   * Empty means "no further retry". Mirrors the ticket #26 fallback matrix: schema retries once
   * immediately, 5xx/network retry once after 500ms, 429 retries at 1s then 3s, timeout never
   * retries.
   */
  private OptionalLong retryDelayMs(AiErrorCode firstError, int attempt) {
    return switch (firstError) {
      case SCHEMA_VALIDATION_FAILED -> attempt == 0 ? OptionalLong.of(0L) : OptionalLong.empty();
      case PROVIDER_5XX, NETWORK -> attempt == 0 ? OptionalLong.of(500L) : OptionalLong.empty();
      case PROVIDER_429 ->
          switch (attempt) {
            case 0 -> OptionalLong.of(1000L);
            case 1 -> OptionalLong.of(3000L);
            default -> OptionalLong.empty();
          };
      default -> OptionalLong.empty();
    };
  }

  private AnthropicMessage[] buildMessages(String systemPrompt, String userContent) {
    return new AnthropicMessage[] {AnthropicMessage.user(systemPrompt + "\n\n" + userContent)};
  }

  /** Appends a single constraint-reminder user turn used only on the schema-validation retry. */
  private AnthropicMessage[] withConstraintReminder(AnthropicMessage[] base, String outputSchema) {
    AnthropicMessage[] withReminder = Arrays.copyOf(base, base.length + 1);
    withReminder[base.length] =
        AnthropicMessage.user(
            "이전 응답이 요구된 JSON 스키마("
                + outputSchema
                + ")를 만족하지 않았습니다. 설명이나 코드펜스 없이, 스키마 제약을 지킨 유효한 JSON만 다시 출력하세요.");
    return withReminder;
  }

  private AiRequestStatus statusForErrorCode(AiErrorCode errorCode) {
    return errorCode == AiErrorCode.TIMEOUT ? AiRequestStatus.TIMEOUT : AiRequestStatus.ERROR;
  }

  private ApiErrorException toApiError(AiErrorCode errorCode) {
    return switch (errorCode) {
      case PROVIDER_5XX ->
          new ApiErrorException(
              HttpStatus.SERVICE_UNAVAILABLE,
              "provider_5xx",
              "서비스가 일시적으로 이용 불가합니다. 잠시 후 다시 시도해주세요.",
              "Claude provider returned 5xx error",
              true);
      case PROVIDER_429 ->
          new ApiErrorException(
              HttpStatus.TOO_MANY_REQUESTS,
              "provider_429",
              "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.",
              "Claude provider rate limited",
              true);
      case TIMEOUT ->
          new ApiErrorException(
              HttpStatus.REQUEST_TIMEOUT,
              "timeout",
              "응답 시간이 초과되었습니다. 다시 시도해주세요.",
              "Claude request timed out",
              true);
      case NETWORK ->
          new ApiErrorException(
              HttpStatus.SERVICE_UNAVAILABLE,
              "network",
              "네트워크 오류가 발생했습니다. 다시 시도해주세요.",
              "Network error calling Claude",
              true);
      case SCHEMA_VALIDATION_FAILED ->
          new ApiErrorException(
              HttpStatus.INTERNAL_SERVER_ERROR,
              "schema_validation_failed",
              "응답 형식이 유효하지 않습니다.",
              "Schema validation failed",
              true);
      default ->
          new ApiErrorException(
              HttpStatus.INTERNAL_SERVER_ERROR,
              "internal_server_error",
              "문제가 발생했어요. 잠시 후 다시 시도해 주세요.",
              "Unexpected error: " + errorCode,
              false);
    };
  }

  /**
   * Writes one {@code ai_request_logs} row for a single attempt. {@code latencyMs} is that
   * attempt's own duration and excludes any backoff that preceded or followed it.
   */
  private void logAttempt(
      AiFeature feature,
      String modelId,
      String promptVersion,
      Integer inputTokens,
      Integer outputTokens,
      long latencyMs,
      AiRequestStatus status,
      AiErrorCode errorCode,
      UUID userId,
      UUID correlationId,
      BigDecimal estimatedCostUsd,
      UUID attemptGroupId,
      int attemptNumber,
      boolean isFinalAttempt) {
    AiRequestLogEntry entry =
        AiRequestLogEntry.builder()
            .userId(userId)
            .feature(feature)
            .modelName(modelId)
            .promptVersion(promptVersion)
            .inputTokens(inputTokens)
            .outputTokens(outputTokens)
            .latencyMs(latencyMs)
            .estimatedCostUsd(estimatedCostUsd)
            .status(status)
            .errorCode(errorCode)
            .requestCorrelationId(correlationId)
            .attemptGroupId(attemptGroupId)
            .attemptNumber(attemptNumber)
            .isFinalAttempt(isFinalAttempt)
            .build();
    aiRequestLogger.log(entry);
  }
}
