package com.phraselog.ai.client.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.phraselog.ai.client.config.FeatureRouting;
import com.phraselog.ai.client.dto.AnthropicMessage;
import com.phraselog.ai.logging.dto.AiErrorCode;
import com.phraselog.ai.logging.dto.AiFeature;
import com.phraselog.ai.logging.dto.AiRequestLogEntry;
import com.phraselog.ai.logging.dto.AiRequestStatus;
import com.phraselog.ai.logging.service.AiCostCalculator;
import com.phraselog.ai.logging.service.AiRequestLogger;
import com.phraselog.ai.prompt.dto.PromptDefinition;
import com.phraselog.common.web.ApiErrorException;
import java.math.BigDecimal;
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

  public JsonNode callClaude(
      AiFeature feature,
      PromptDefinition prompt,
      String userContent,
      UUID userId,
      UUID correlationId)
      throws ApiErrorException {

    long startTimeMs = System.currentTimeMillis();
    String modelId = FeatureRouting.getModelForFeature(feature);
    String outputSchema = prompt.outputSchema();

    try {
      AnthropicMessage[] messages = buildMessages(prompt.body(), userContent);

      log.debug(
          "Calling Claude feature={} model={} output_schema={}",
          feature.wireName(),
          modelId,
          outputSchema);

      JsonNode response = attemptCall(modelId, messages);
      schemaValidator.validate(outputSchema, response);

      long latencyMs = System.currentTimeMillis() - startTimeMs;
      Integer inputTokens = extractTokenCount(response, "input_tokens");
      Integer outputTokens = extractTokenCount(response, "output_tokens");
      BigDecimal cost =
          (inputTokens != null && outputTokens != null)
              ? costCalculator.llm(modelId, inputTokens, outputTokens)
              : null;

      logCall(
          feature,
          modelId,
          prompt.promptVersion(),
          inputTokens,
          outputTokens,
          latencyMs,
          AiRequestStatus.SUCCESS,
          null,
          userId,
          correlationId,
          cost);

      return response;

    } catch (AnthropicClient.AnthropicClientException e) {
      return handleClientException(
          e, feature, modelId, prompt.promptVersion(), startTimeMs, userId, correlationId);
    } catch (JsonSchemaValidator.JsonSchemaValidationException e) {
      long latencyMs = System.currentTimeMillis() - startTimeMs;
      logCall(
          feature,
          modelId,
          prompt.promptVersion(),
          null,
          null,
          latencyMs,
          AiRequestStatus.ERROR,
          AiErrorCode.SCHEMA_VALIDATION_FAILED,
          userId,
          correlationId,
          null);
      throw new ApiErrorException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "schema_validation_failed",
          "응답 형식이 유효하지 않습니다.",
          "Schema validation failed",
          true);
    }
  }

  private JsonNode attemptCall(String modelId, AnthropicMessage[] messages)
      throws AnthropicClient.AnthropicClientException {
    return client.sendMessage(
        modelId, messages, FeatureRouting.getTimeoutForFeature(AiFeature.S07_ANALYSIS));
  }

  private AnthropicMessage[] buildMessages(String systemPrompt, String userContent) {
    return new AnthropicMessage[] {AnthropicMessage.user(systemPrompt + "\n\n" + userContent)};
  }

  private Integer extractTokenCount(JsonNode response, String field) {
    if (response.has("usage") && response.get("usage").has(field)) {
      return response.get("usage").get(field).asInt();
    }
    return null;
  }

  private JsonNode handleClientException(
      AnthropicClient.AnthropicClientException e,
      AiFeature feature,
      String modelId,
      String promptVersion,
      long startTimeMs,
      UUID userId,
      UUID correlationId)
      throws ApiErrorException {

    long latencyMs = System.currentTimeMillis() - startTimeMs;
    AiErrorCode errorCode = e.errorCode();

    if (!e.isRetryable()) {
      logCall(
          feature,
          modelId,
          promptVersion,
          null,
          null,
          latencyMs,
          statusForErrorCode(errorCode),
          errorCode,
          userId,
          correlationId,
          null);
      throw toApiError(errorCode);
    }

    log.warn(
        "Retryable error calling Claude, attempting retry: {} ({})", e.getMessage(), errorCode);

    long backoffMs = backoffForErrorCode(errorCode);
    try {
      Thread.sleep(backoffMs);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      logCall(
          feature,
          modelId,
          promptVersion,
          null,
          null,
          latencyMs,
          AiRequestStatus.ERROR,
          errorCode,
          userId,
          correlationId,
          null);
      throw toApiError(errorCode);
    }

    long totalLatencyMs = System.currentTimeMillis() - startTimeMs;
    logCall(
        feature,
        modelId,
        promptVersion,
        null,
        null,
        totalLatencyMs,
        AiRequestStatus.ERROR,
        errorCode,
        userId,
        correlationId,
        null);
    throw toApiError(errorCode);
  }

  private long backoffForErrorCode(AiErrorCode errorCode) {
    return switch (errorCode) {
      case PROVIDER_5XX, NETWORK -> 500L;
      case PROVIDER_429 -> 1000L;
      default -> 0L;
    };
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

  private void logCall(
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
      BigDecimal estimatedCostUsd) {
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
            .build();
    aiRequestLogger.log(entry);
  }
}
