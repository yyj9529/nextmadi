package com.phraselog.practice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.client.service.AnthropicService;
import com.phraselog.ai.logging.dto.AiFeature;
import com.phraselog.ai.prompt.dto.PromptDefinition;
import com.phraselog.ai.prompt.service.PromptLoader;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.expression.dto.ExpressionResponse;
import com.phraselog.expression.dto.NewExpressionVariant;
import com.phraselog.expression.dto.NewRoleplayExpression;
import com.phraselog.expression.dto.SaveExpressionResult;
import com.phraselog.expression.repository.ExpressionRepository;
import com.phraselog.practice.dto.PracticeResultContext;
import com.phraselog.practice.dto.SaveRoleplayExpressionRequest;
import com.phraselog.practice.repository.PracticeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** S12b roleplay result generation and save backend (#62). */
@Service
public class PracticeResultService {

  private static final String RESULT_PROMPT_PATH = "roleplay/result";
  private static final int RESULT_PROMPT_VERSION = 1;
  private static final int EXPECTED_RECOMMENDATION_COUNT = 3;

  private final PracticeRepository practiceRepository;
  private final ExpressionRepository expressionRepository;
  private final AnthropicService anthropicService;
  private final PromptLoader promptLoader;
  private final ObjectMapper objectMapper;

  public PracticeResultService(
      PracticeRepository practiceRepository,
      ExpressionRepository expressionRepository,
      AnthropicService anthropicService,
      PromptLoader promptLoader,
      ObjectMapper objectMapper) {
    this.practiceRepository = practiceRepository;
    this.expressionRepository = expressionRepository;
    this.anthropicService = anthropicService;
    this.promptLoader = promptLoader;
    this.objectMapper = objectMapper;
  }

  /** Handles {@code POST /practice/sessions/{id}/result}. */
  public JsonNode generateResult(InternalAuthPrincipal principal, String sessionIdText) {
    UUID userId = authenticatedUserId(principal);
    UUID sessionId = parseSessionId(sessionIdText);
    PracticeResultContext context = resultContext(sessionId, userId);
    requireCompleted(context);

    if (context.resultJson() != null && !context.resultJson().isNull()) {
      return context.resultJson();
    }

    PromptDefinition prompt = promptLoader.load(RESULT_PROMPT_PATH, RESULT_PROMPT_VERSION);
    UUID correlationId = UUID.randomUUID();
    JsonNode generated =
        anthropicService.callClaude(
            AiFeature.ROLEPLAY_RESULT,
            prompt,
            buildResultPromptInput(context),
            userId,
            correlationId);
    return practiceRepository.saveResultJsonIfAbsent(sessionId, userId, generated);
  }

  /** Handles {@code POST /practice/sessions/{id}/save-expression}. */
  public SaveExpressionResult saveExpression(
      InternalAuthPrincipal principal,
      String sessionIdText,
      SaveRoleplayExpressionRequest body,
      String idempotencyKeyText) {
    UUID userId = authenticatedUserId(principal);
    UUID sessionId = parseSessionId(sessionIdText);
    int resultIndex = recommendedExpressionIndex(body);
    UUID idempotencyKey = parseIdempotencyKey(idempotencyKeyText);

    Optional<ExpressionResponse> idempotentReplay =
        expressionRepository.findByRoleplayIdempotencyKey(sessionId, userId, idempotencyKey);
    if (idempotentReplay.isPresent()) {
      return new SaveExpressionResult(idempotentReplay.get(), true);
    }

    PracticeResultContext context = resultContext(sessionId, userId);
    requireCompleted(context);
    JsonNode resultJson = requireResultJson(context);

    Optional<ExpressionResponse> existingCardSave =
        expressionRepository.findActiveRoleplaySaveByIndex(sessionId, userId, resultIndex);
    if (existingCardSave.isPresent()) {
      return new SaveExpressionResult(existingCardSave.get(), true);
    }

    NewRoleplayExpression command =
        new NewRoleplayExpression(
            userId,
            sessionId,
            originalSituation(context),
            resultIndex + 1,
            resultIndex,
            idempotencyKey,
            variantsFrom(resultJson));
    try {
      return new SaveExpressionResult(
          expressionRepository.createFromRoleplayResult(command), false);
    } catch (DuplicateKeyException duplicate) {
      Optional<ExpressionResponse> replayAfterRace =
          expressionRepository.findByRoleplayIdempotencyKey(sessionId, userId, idempotencyKey);
      if (replayAfterRace.isPresent()) {
        return new SaveExpressionResult(replayAfterRace.get(), true);
      }
      return new SaveExpressionResult(
          expressionRepository
              .findActiveRoleplaySaveByIndex(sessionId, userId, resultIndex)
              .orElseThrow(() -> duplicate),
          true);
    }
  }

  private PracticeResultContext resultContext(UUID sessionId, UUID userId) {
    return practiceRepository
        .findResultContext(sessionId, userId)
        .orElseThrow(
            () ->
                new ApiErrorException(
                    HttpStatus.NOT_FOUND,
                    "not_found",
                    "Practice session was not found.",
                    "Practice session is missing or not owned by caller.",
                    false));
  }

  private void requireCompleted(PracticeResultContext context) {
    if (!"completed".equals(context.status())) {
      throw new ApiErrorException(
          HttpStatus.CONFLICT,
          "conflict",
          "This practice session is not ready for a result.",
          "Roleplay result generation/save requires status=completed.",
          false);
    }
  }

  private JsonNode requireResultJson(PracticeResultContext context) {
    if (context.resultJson() == null || context.resultJson().isNull()) {
      throw new ApiErrorException(
          HttpStatus.CONFLICT,
          "conflict",
          "Practice result is not ready yet.",
          "Generate roleplay result before saving a recommended expression.",
          true);
    }
    return context.resultJson();
  }

  private String buildResultPromptInput(PracticeResultContext context) {
    String turnsJson = objectMapper.valueToTree(context.turns()).toString();
    return """
        <session>
        original_situation: %s
        selected_expression: %s
        coach_name: %s
        coach_persona: %s
        conversation_history_json: %s
        </session>
        """
        .formatted(
            context.originalSituation(),
            context.selectedExpression(),
            context.coachName(),
            context.coachPersona(),
            turnsJson);
  }

  private static UUID authenticatedUserId(InternalAuthPrincipal principal) {
    if (principal == null || !principal.isAuthenticatedUser()) {
      throw new ApiErrorException(
          HttpStatus.UNAUTHORIZED,
          "internal_auth_invalid",
          "Login is required.",
          "S12b result routes require an authenticated user principal.",
          false);
    }
    try {
      return UUID.fromString(principal.userId());
    } catch (IllegalArgumentException e) {
      throw validationFailed("user_id claim must be a UUID.");
    }
  }

  private static UUID parseSessionId(String sessionIdText) {
    try {
      return UUID.fromString(sessionIdText);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new ApiErrorException(
          HttpStatus.NOT_FOUND,
          "not_found",
          "Practice session was not found.",
          "Malformed practice session id.",
          false);
    }
  }

  private static UUID parseIdempotencyKey(String idempotencyKeyText) {
    if (!StringUtils.hasText(idempotencyKeyText)) {
      throw validationFailed("Idempotency-Key header is required.");
    }
    try {
      return UUID.fromString(idempotencyKeyText.trim());
    } catch (IllegalArgumentException e) {
      throw validationFailed("Idempotency-Key must be a UUID.");
    }
  }

  private static int recommendedExpressionIndex(SaveRoleplayExpressionRequest body) {
    Integer index = body == null ? null : body.recommendedExpressionIndex();
    if (index == null || index < 0 || index >= EXPECTED_RECOMMENDATION_COUNT) {
      throw validationFailed("recommended_expression_index must be 0, 1, or 2.");
    }
    return index;
  }

  private static String originalSituation(PracticeResultContext context) {
    if (!StringUtils.hasText(context.originalSituation())) {
      throw new ApiErrorException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "internal_server_error",
          "Practice result cannot be saved.",
          "Roleplay session has no source expression situation to copy.",
          false);
    }
    return context.originalSituation();
  }

  private static List<NewExpressionVariant> variantsFrom(JsonNode resultJson) {
    JsonNode recommended = resultJson == null ? null : resultJson.get("recommended_expressions");
    if (recommended == null
        || !recommended.isArray()
        || recommended.size() != EXPECTED_RECOMMENDATION_COUNT) {
      throw new ApiErrorException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "internal_server_error",
          "Practice result cannot be saved.",
          "roleplay_result_v1 must contain exactly 3 recommended_expressions.",
          true);
    }

    List<NewExpressionVariant> variants = new ArrayList<>(EXPECTED_RECOMMENDATION_COUNT);
    for (int i = 0; i < recommended.size(); i++) {
      JsonNode node = recommended.get(i);
      variants.add(
          new NewExpressionVariant(
              i + 1,
              textOrNull(node, "tone_label"),
              textOrNull(node, "english"),
              textOrNull(node, "ipa"),
              textOrNull(node, "korean_pronunciation"),
              textOrNull(node, "pronunciation_tip"),
              textOrNull(node, "cultural_tip")));
    }
    return variants;
  }

  private static String textOrNull(JsonNode node, String field) {
    return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
  }

  private static ApiErrorException validationFailed(String developerHint) {
    return new ApiErrorException(
        HttpStatus.BAD_REQUEST,
        "validation_failed",
        "Check the request and try again.",
        developerHint,
        false);
  }
}
