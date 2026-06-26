package com.phraselog.practice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.client.service.AnthropicService;
import com.phraselog.ai.logging.dto.AiFeature;
import com.phraselog.ai.prompt.dto.PromptDefinition;
import com.phraselog.ai.prompt.service.PromptLoader;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.practice.dto.SubmitPracticeTurnRequest;
import com.phraselog.practice.dto.SubmitPracticeTurnResponse;
import com.phraselog.practice.dto.TurnFeedbackResponse;
import com.phraselog.practice.repository.AppendTurnPairCommand;
import com.phraselog.practice.repository.PracticeSessionContext;
import com.phraselog.practice.repository.PracticeTurnRepository;
import com.phraselog.practice.repository.PracticeTurnRequestRow;
import com.phraselog.transcription.dto.TranscriptionResponse;
import com.phraselog.transcription.service.TranscriptionService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/** Orchestrates one S12 user turn (#60). */
@Service
public class PracticeTurnService {

  private static final String TURN_PROMPT_PATH = "roleplay/turn";
  private static final String FEEDBACK_PROMPT_PATH = "roleplay/feedback";
  private static final int PROMPT_VERSION = 1;
  private static final BigDecimal LOW_CONFIDENCE_THRESHOLD = new BigDecimal("0.500");
  private static final String RETRY_PROMPT = "I couldn't hear you well. Please say that again";

  private final PracticeTurnRepository repository;
  private final AnthropicService anthropicService;
  private final PromptLoader promptLoader;
  private final TranscriptionService transcriptionService;
  private final PracticeAudioService audioService;
  private final ObjectMapper objectMapper;

  public PracticeTurnService(
      PracticeTurnRepository repository,
      AnthropicService anthropicService,
      PromptLoader promptLoader,
      TranscriptionService transcriptionService,
      PracticeAudioService audioService,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.anthropicService = anthropicService;
    this.promptLoader = promptLoader;
    this.transcriptionService = transcriptionService;
    this.audioService = audioService;
    this.objectMapper = objectMapper;
  }

  public SubmitPracticeTurnResponse submitTextTurn(
      InternalAuthPrincipal principal,
      String sessionId,
      SubmitPracticeTurnRequest body,
      String idempotencyKey) {
    return submitTextTurn(principal, sessionId, body, idempotencyKey, UUID::randomUUID);
  }

  public SubmitPracticeTurnResponse submitTextTurn(
      InternalAuthPrincipal principal,
      String sessionId,
      SubmitPracticeTurnRequest body,
      String idempotencyKey,
      Supplier<UUID> correlationIdSupplier) {
    return submit(
        principal,
        sessionId,
        idempotencyKey,
        correlationIdSupplier,
        null,
        body == null ? null : body.textContent());
  }

  public SubmitPracticeTurnResponse submitAudioTurn(
      InternalAuthPrincipal principal,
      String sessionId,
      MultipartFile audio,
      String idempotencyKey) {
    return submitAudioTurn(principal, sessionId, audio, idempotencyKey, UUID::randomUUID);
  }

  public SubmitPracticeTurnResponse submitAudioTurn(
      InternalAuthPrincipal principal,
      String sessionId,
      MultipartFile audio,
      String idempotencyKey,
      Supplier<UUID> correlationIdSupplier) {
    return submit(principal, sessionId, idempotencyKey, correlationIdSupplier, audio, null);
  }

  private SubmitPracticeTurnResponse submit(
      InternalAuthPrincipal principal,
      String sessionIdText,
      String idempotencyKeyText,
      Supplier<UUID> correlationIdSupplier,
      MultipartFile audio,
      String textContent) {
    UUID userId = authenticatedUserId(principal);
    UUID sessionId = parseSessionId(sessionIdText);
    UUID idempotencyKey = parseIdempotencyKey(idempotencyKeyText);

    PracticeSessionContext context =
        repository.findSessionForUser(sessionId, userId).orElseThrow(PracticeTurnService::notFound);
    if (!"active".equals(context.status())) {
      throw conflict("Practice session is not active.");
    }

    Optional<SubmitPracticeTurnResponse> replay = repository.findReplay(sessionId, idempotencyKey);
    if (replay.isPresent()) {
      return replay.get();
    }

    UUID correlationId = correlationIdSupplier.get();
    PracticeTurnRequestRow request;
    try {
      request = repository.reserveRequest(sessionId, idempotencyKey, correlationId);
    } catch (DuplicateKeyException duplicate) {
      return repository
          .findReplay(sessionId, idempotencyKey)
          .orElseThrow(() -> conflict("Turn request is already processing."));
    }

    try {
      TranscribedInput input = resolveInput(principal, audio, textContent, correlationId);
      if (!StringUtils.hasText(input.text()) || lowConfidence(input.sttConfidence())) {
        return repository.completeWithoutTurn(request, RETRY_PROMPT);
      }

      String coachUtterance = coachResponse(context, input.text(), userId, correlationId);
      FeedbackResult feedback = feedback(context, input.text(), userId, correlationId);
      PracticeAudioResult audioResult = synthesize(userId, coachUtterance, context, correlationId);

      AppendTurnPairCommand command =
          new AppendTurnPairCommand(
              input.text(),
              input.sttConfidence(),
              coachUtterance,
              audioResult == null ? null : audioResult.ttsAudioCacheId(),
              audioResult == null ? null : audioResult.audioUrl(),
              feedback.response() != null && feedback.response().showFeedback(),
              feedback.json());
      return repository.appendTurnPair(request, command);
    } catch (ApiErrorException e) {
      repository.markFailed(request.id());
      throw e;
    } catch (RuntimeException e) {
      repository.markFailed(request.id());
      throw e;
    }
  }

  private TranscribedInput resolveInput(
      InternalAuthPrincipal principal,
      MultipartFile audio,
      String textContent,
      UUID correlationId) {
    if (audio != null && !audio.isEmpty()) {
      TranscriptionResponse response =
          transcriptionService.transcribe(principal, audio, correlationId);
      return new TranscribedInput(trimToNull(response.transcript()), response.sttConfidence());
    }
    return new TranscribedInput(trimToNull(textContent), null);
  }

  private String coachResponse(
      PracticeSessionContext context, String userText, UUID userId, UUID correlationId) {
    PromptDefinition prompt = promptLoader.load(TURN_PROMPT_PATH, PROMPT_VERSION);
    JsonNode response =
        anthropicService.callClaude(
            AiFeature.ROLEPLAY_TURN_RESPONSE,
            prompt,
            promptInput(context, userText),
            userId,
            correlationId);
    String utterance =
        response.hasNonNull("coach_utterance") ? response.get("coach_utterance").asText() : null;
    if (!StringUtils.hasText(utterance)) {
      throw new ApiErrorException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "schema_validation_failed",
          "Could not continue the roleplay. Please try again.",
          "roleplay_turn_response returned a blank coach_utterance.",
          true);
    }
    return utterance.trim();
  }

  private FeedbackResult feedback(
      PracticeSessionContext context, String userText, UUID userId, UUID correlationId) {
    try {
      PromptDefinition prompt = promptLoader.load(FEEDBACK_PROMPT_PATH, PROMPT_VERSION);
      JsonNode response =
          anthropicService.callClaude(
              AiFeature.ROLEPLAY_TURN_FEEDBACK,
              prompt,
              promptInput(context, userText),
              userId,
              correlationId);
      if (!response.path("show_feedback").asBoolean(false)) {
        return new FeedbackResult(null, null);
      }
      TurnFeedbackResponse feedback =
          new TurnFeedbackResponse(
              true,
              textOrNull(response, "natural_alternative"),
              textOrNull(response, "korean_comment"));
      return new FeedbackResult(feedback, response);
    } catch (ApiErrorException e) {
      return new FeedbackResult(null, null);
    }
  }

  private PracticeAudioResult synthesize(
      UUID userId, String coachUtterance, PracticeSessionContext context, UUID correlationId) {
    try {
      return audioService
          .synthesize(userId, coachUtterance, context.ttsVoiceId(), correlationId)
          .orElse(null);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private String promptInput(PracticeSessionContext context, String userText) {
    String history = objectMapper.valueToTree(context.turns()).toString();
    return """
        <session>
        planned_turns: %d
        consumed_user_turns: %d
        original_situation: %s
        selected_expression: %s
        coach_name: %s
        coach_persona: %s
        conversation_history_json: %s
        latest_user_utterance: %s
        </session>
        """
        .formatted(
            context.plannedTurns(),
            context.consumedUserTurns(),
            context.originalSituation(),
            context.selectedExpression(),
            context.coachName(),
            context.coachPersona(),
            history,
            userText);
  }

  private static UUID authenticatedUserId(InternalAuthPrincipal principal) {
    if (principal == null || !principal.isAuthenticatedUser()) {
      throw new ApiErrorException(
          HttpStatus.UNAUTHORIZED,
          "internal_auth_invalid",
          "Login is required.",
          "S12 turn submission requires an authenticated user principal.",
          false);
    }
    return UUID.fromString(principal.userId());
  }

  private static UUID parseSessionId(String sessionId) {
    try {
      return UUID.fromString(sessionId);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw notFound();
    }
  }

  private static UUID parseIdempotencyKey(String idempotencyKey) {
    if (!StringUtils.hasText(idempotencyKey)) {
      throw validationFailed("Idempotency-Key header is required.");
    }
    try {
      return UUID.fromString(idempotencyKey.trim());
    } catch (IllegalArgumentException e) {
      throw validationFailed("Idempotency-Key must be a UUID.");
    }
  }

  private static String trimToNull(String text) {
    if (!StringUtils.hasText(text)) {
      return null;
    }
    return text.trim();
  }

  private static boolean lowConfidence(BigDecimal confidence) {
    return confidence != null && confidence.compareTo(LOW_CONFIDENCE_THRESHOLD) < 0;
  }

  private static String textOrNull(JsonNode node, String field) {
    return node.hasNonNull(field) ? node.get(field).asText() : null;
  }

  private static ApiErrorException validationFailed(String developerHint) {
    return new ApiErrorException(
        HttpStatus.BAD_REQUEST,
        "validation_failed",
        "Check the request and try again.",
        developerHint,
        false);
  }

  private static ApiErrorException notFound() {
    return new ApiErrorException(
        HttpStatus.NOT_FOUND,
        "not_found",
        "Practice session was not found.",
        "Practice session is missing, not owned by caller, or not active.",
        false);
  }

  private static ApiErrorException conflict(String developerHint) {
    return new ApiErrorException(
        HttpStatus.CONFLICT,
        "conflict",
        "This practice turn cannot be processed.",
        developerHint,
        false);
  }

  private record TranscribedInput(String text, BigDecimal sttConfidence) {}

  private record FeedbackResult(TurnFeedbackResponse response, JsonNode json) {}
}
