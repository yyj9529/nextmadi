package com.phraselog.practice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.phraselog.ai.client.service.AnthropicService;
import com.phraselog.ai.logging.dto.AiFeature;
import com.phraselog.ai.prompt.dto.PromptDefinition;
import com.phraselog.ai.prompt.service.PromptLoader;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.coach.dto.CoachResponse;
import com.phraselog.coach.repository.CoachRepository;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.expression.dto.ExpressionResponse;
import com.phraselog.expression.dto.ExpressionVariantResponse;
import com.phraselog.expression.repository.ExpressionRepository;
import com.phraselog.practice.dto.NewPracticeSession;
import com.phraselog.practice.dto.PracticeSessionResponse;
import com.phraselog.practice.dto.PracticeSessionRow;
import com.phraselog.practice.dto.PracticeSessionWithTurns;
import com.phraselog.practice.dto.PracticeTurnResponse;
import com.phraselog.practice.dto.PracticeTurnRow;
import com.phraselog.practice.dto.StartSessionRequest;
import com.phraselog.practice.repository.PracticeRepository;
import com.phraselog.user.dto.UserResponse;
import com.phraselog.user.repository.UsageRepository;
import com.phraselog.user.repository.UserRepository;
import com.phraselog.user.service.UsageService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Orchestrates the S12 roleplay session lifecycle (#59): idempotency, daily-limit enforcement,
 * input resolution (expression + coach), the {@code roleplay_session_init} Sonnet call, and
 * persistence of the session plus its opening coach turn.
 *
 * <p>Mirrors {@link com.phraselog.analysis.service.AnalysisService}: the idempotency lookup runs
 * before any LLM call, and a {@link DuplicateKeyException} on insert is resolved by re-reading the
 * winning row. Unlike analysis there is no anonymous path — roleplay always requires an
 * authenticated user.
 *
 * <p>TTS for the opening turn is intentionally out of scope here (#30): the opening turn's {@code
 * tts_audio_url} is null, which S12 renders as a text-only coach bubble.
 */
@Service
public class PracticeSessionService {

  private static final String INIT_PROMPT_PATH = "roleplay/init";
  private static final int INIT_PROMPT_VERSION = 1;

  private final PracticeRepository repository;
  private final AnthropicService anthropicService;
  private final PromptLoader promptLoader;
  private final ExpressionRepository expressionRepository;
  private final CoachRepository coachRepository;
  private final UserRepository userRepository;
  private final UsageRepository usageRepository;
  private final Clock clock;

  public PracticeSessionService(
      PracticeRepository repository,
      AnthropicService anthropicService,
      PromptLoader promptLoader,
      ExpressionRepository expressionRepository,
      CoachRepository coachRepository,
      UserRepository userRepository,
      UsageRepository usageRepository,
      Clock clock) {
    this.repository = repository;
    this.anthropicService = anthropicService;
    this.promptLoader = promptLoader;
    this.expressionRepository = expressionRepository;
    this.coachRepository = coachRepository;
    this.userRepository = userRepository;
    this.usageRepository = usageRepository;
    this.clock = clock;
  }

  /** Handles {@code POST /practice/sessions}. */
  public PracticeSessionResponse start(
      InternalAuthPrincipal principal, StartSessionRequest body, String idempotencyKeyHeader) {

    UUID userId = requireAuthenticatedUser(principal);
    UUID idempotencyKey = parseIdempotencyKey(idempotencyKeyHeader);

    Optional<PracticeSessionWithTurns> existing =
        repository.findByUserAndKey(userId, idempotencyKey);
    if (existing.isPresent()) {
      return toStartResponse(existing.get());
    }

    UUID expressionId =
        parseRequiredUuid(body == null ? null : body.expressionId(), "expression_id");
    ExpressionResponse expression =
        expressionRepository
            .findByIdForUser(expressionId, userId)
            .orElseThrow(() -> notFound("Expression is missing or not owned by the caller."));

    CoachResponse coach = resolveCoach(body, userId);

    // Daily limit is checked after input resolution but before the (billable) LLM call, so an
    // over-limit attempt neither bills Sonnet nor creates a session row.
    enforceDailyLimit(userId);

    PromptDefinition prompt = promptLoader.load(INIT_PROMPT_PATH, INIT_PROMPT_VERSION);
    UUID correlationId = UUID.randomUUID();
    String userContent = buildSessionInput(coach, expression);

    // callClaude validates roleplay_session_init_v1 (planned_turns 3-10) and logs success/failure
    // to ai_request_logs with this correlationId; it throws ApiErrorException on provider/schema
    // failure, so a malformed planned_turns never reaches the insert below.
    JsonNode output =
        anthropicService.callClaude(
            AiFeature.ROLEPLAY_SESSION_INIT, prompt, userContent, userId, correlationId);
    int plannedTurns = output.get("planned_turns").asInt();
    String scenarioSetup = output.get("scenario_setup").asText();

    NewPracticeSession newSession =
        new NewPracticeSession(
            userId, expressionId, coach.id(), plannedTurns, idempotencyKey, scenarioSetup);
    try {
      return toStartResponse(repository.insertSessionWithOpeningTurn(newSession));
    } catch (DuplicateKeyException race) {
      // A concurrent retry under the same idempotency key won the insert. Return its row.
      return toStartResponse(
          repository.findByUserAndKey(userId, idempotencyKey).orElseThrow(() -> race));
    }
  }

  /**
   * Handles {@code GET /practice/sessions/{id}}: owner-only, 404 for missing/not-owned/malformed.
   */
  public PracticeSessionResponse get(InternalAuthPrincipal principal, String sessionId) {
    UUID userId = requireAuthenticatedUser(principal);
    UUID id = parseIdOrNotFound(sessionId);
    return repository
        .findByIdForOwner(id, userId)
        .map(this::toGetResponse)
        .orElseThrow(() -> notFound("Session is missing or not owned by the caller."));
  }

  private CoachResponse resolveCoach(StartSessionRequest body, UUID userId) {
    String requestedCoachId = body == null ? null : body.coachId();
    UUID coachId;
    if (StringUtils.hasText(requestedCoachId)) {
      coachId = parseRequiredUuid(requestedCoachId, "coach_id");
    } else {
      coachId =
          userRepository
              .findById(userId)
              .map(UserResponse::selectedCoachId)
              .orElseThrow(
                  () ->
                      validationFailed("No coach_id provided and user has no selected_coach_id."));
      if (coachId == null) {
        throw validationFailed("No coach_id provided and user has no selected_coach_id.");
      }
    }
    return coachRepository
        .findById(coachId)
        .orElseThrow(() -> validationFailed("coach_id does not match any coach."));
  }

  private void enforceDailyLimit(UUID userId) {
    OffsetDateTime startOfDay =
        LocalDate.now(clock.withZone(ZoneOffset.UTC)).atStartOfDay().atOffset(ZoneOffset.UTC);
    OffsetDateTime startOfNextDay = startOfDay.plusDays(1);
    int count = usageRepository.countRoleplaySessions(userId, startOfDay, startOfNextDay);
    if (count >= UsageService.DAILY_ROLEPLAY_LIMIT) {
      throw new ApiErrorException(
          HttpStatus.TOO_MANY_REQUESTS,
          "rate_limit_exceeded",
          "오늘은 2번 다 썼어요. 내일 다시 만나요",
          "Daily roleplay session limit reached (abandoned sessions excluded).",
          false);
    }
  }

  /**
   * Builds the user-content block for the session-init prompt (coach persona + saved expression).
   */
  private String buildSessionInput(CoachResponse coach, ExpressionResponse expression) {
    String selectedEnglish = selectedVariantEnglish(expression);
    return "Coach: "
        + coach.displayName()
        + " — "
        + coach.personaSummary()
        + "\n\nSaved expression:\n"
        + "- Original Korean situation: "
        + expression.originalSituation()
        + "\n- Selected English variant: "
        + selectedEnglish;
  }

  private static String selectedVariantEnglish(ExpressionResponse expression) {
    UUID selectedId = expression.selectedVariantId();
    List<ExpressionVariantResponse> variants = expression.variants();
    if (selectedId != null && variants != null) {
      for (ExpressionVariantResponse variant : variants) {
        if (selectedId.equals(variant.id())) {
          return variant.englishText();
        }
      }
    }
    // Defensive: a saved expression always has a selected variant, but fall back to the first
    // variant's text rather than failing the whole session start on a data anomaly.
    if (variants != null && !variants.isEmpty()) {
      return variants.get(0).englishText();
    }
    return "";
  }

  private PracticeSessionResponse toStartResponse(PracticeSessionWithTurns saved) {
    PracticeSessionRow s = saved.session();
    PracticeTurnResponse openingTurn =
        saved.turns().stream().findFirst().map(PracticeSessionService::toTurnResponse).orElse(null);
    return new PracticeSessionResponse(
        s.id(),
        s.status(),
        s.plannedTurns(),
        s.coachId(),
        s.expressionId(),
        s.startedAt(),
        s.endedAt(),
        s.resultJson(),
        null,
        openingTurn);
  }

  private PracticeSessionResponse toGetResponse(PracticeSessionWithTurns full) {
    PracticeSessionRow s = full.session();
    List<PracticeTurnResponse> turns = new ArrayList<>(full.turns().size());
    for (PracticeTurnRow turn : full.turns()) {
      turns.add(toTurnResponse(turn));
    }
    return new PracticeSessionResponse(
        s.id(),
        s.status(),
        s.plannedTurns(),
        s.coachId(),
        s.expressionId(),
        s.startedAt(),
        s.endedAt(),
        s.resultJson(),
        turns,
        null);
  }

  private static PracticeTurnResponse toTurnResponse(PracticeTurnRow turn) {
    // tts_audio_url stays null until the TTS backend (#30) synthesizes coach audio.
    return new PracticeTurnResponse(
        turn.id(),
        turn.turnNumber(),
        turn.speaker(),
        turn.textContent(),
        null,
        turn.sttConfidence(),
        turn.feedbackShown(),
        turn.createdAt());
  }

  private static UUID requireAuthenticatedUser(InternalAuthPrincipal principal) {
    if (principal == null || !principal.isAuthenticatedUser()) {
      throw new ApiErrorException(
          HttpStatus.UNAUTHORIZED,
          "internal_auth_invalid",
          "로그인이 필요해요.",
          "Practice session routes require an authenticated user_id principal.",
          false);
    }
    try {
      return UUID.fromString(principal.userId());
    } catch (IllegalArgumentException e) {
      throw validationFailed("user_id claim must be a UUID.");
    }
  }

  private static UUID parseIdempotencyKey(String header) {
    if (!StringUtils.hasText(header)) {
      throw validationFailed("Idempotency-Key header is required.");
    }
    try {
      return UUID.fromString(header.trim());
    } catch (IllegalArgumentException e) {
      throw validationFailed("Idempotency-Key must be a UUID.");
    }
  }

  private static UUID parseRequiredUuid(String value, String field) {
    if (!StringUtils.hasText(value)) {
      throw validationFailed(field + " is required.");
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      throw validationFailed(field + " must be a UUID.");
    }
  }

  private static UUID parseIdOrNotFound(String sessionId) {
    try {
      return UUID.fromString(sessionId);
    } catch (IllegalArgumentException | NullPointerException e) {
      // A malformed id cannot identify any row; treat as not found per the GET contract.
      throw notFound("Malformed session id.");
    }
  }

  private static ApiErrorException validationFailed(String developerHint) {
    return new ApiErrorException(
        HttpStatus.BAD_REQUEST, "validation_failed", "입력값을 다시 확인해 주세요.", developerHint, false);
  }

  private static ApiErrorException notFound(String developerHint) {
    return new ApiErrorException(
        HttpStatus.NOT_FOUND, "not_found", "찾을 수 없는 세션이에요.", developerHint, false);
  }
}
