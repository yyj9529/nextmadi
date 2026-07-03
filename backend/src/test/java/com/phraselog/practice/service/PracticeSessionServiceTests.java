package com.phraselog.practice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.phraselog.practice.dto.PracticeTurnRow;
import com.phraselog.practice.dto.StartSessionRequest;
import com.phraselog.practice.repository.PracticeRepository;
import com.phraselog.user.dto.UserResponse;
import com.phraselog.user.repository.UsageRepository;
import com.phraselog.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

class PracticeSessionServiceTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-06-26T10:00:00Z"), ZoneOffset.UTC);

  private FakePracticeRepository repository;
  private AnthropicService anthropicService;
  private PromptLoader promptLoader;
  private ExpressionRepository expressionRepository;
  private CoachRepository coachRepository;
  private UserRepository userRepository;
  private UsageRepository usageRepository;
  private PracticeSessionService service;

  private UUID userId;
  private UUID expressionId;
  private UUID selectedCoachId;
  private UUID selectedVariantId;

  @BeforeEach
  void setUp() {
    repository = new FakePracticeRepository();
    anthropicService = mock(AnthropicService.class);
    promptLoader = mock(PromptLoader.class);
    expressionRepository = mock(ExpressionRepository.class);
    coachRepository = mock(CoachRepository.class);
    userRepository = mock(UserRepository.class);
    usageRepository = mock(UsageRepository.class);

    userId = UUID.randomUUID();
    expressionId = UUID.randomUUID();
    selectedCoachId = UUID.randomUUID();
    selectedVariantId = UUID.randomUUID();

    when(promptLoader.load("roleplay/init", 1)).thenReturn(promptDefinition());
    when(anthropicService.callClaude(
            eq(AiFeature.ROLEPLAY_SESSION_INIT), any(), anyString(), any(), any()))
        .thenReturn(initOutput(5, "Hi, I'm calling about my appointment."));
    when(expressionRepository.findByIdForUser(expressionId, userId))
        .thenReturn(Optional.of(expression()));
    when(coachRepository.findById(selectedCoachId)).thenReturn(Optional.of(coach(selectedCoachId)));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(selectedCoachId)));
    when(usageRepository.countRoleplaySessions(eq(userId), any(), any())).thenReturn(0);

    service =
        new PracticeSessionService(
            repository,
            anthropicService,
            promptLoader,
            expressionRepository,
            coachRepository,
            userRepository,
            usageRepository,
            FIXED_CLOCK);
  }

  @Test
  void startPersistsSessionAndOpeningTurn() {
    PracticeSessionResponse response =
        service.start(user(), body(null), UUID.randomUUID().toString());

    assertThat(response.status()).isEqualTo("active");
    assertThat(response.plannedTurns()).isEqualTo(5);
    assertThat(response.coachId()).isEqualTo(selectedCoachId);
    assertThat(response.expressionId()).isEqualTo(expressionId);
    assertThat(response.turns()).isNull(); // omitted on POST
    assertThat(response.openingTurn()).isNotNull();
    assertThat(response.openingTurn().turnNumber()).isEqualTo(1);
    assertThat(response.openingTurn().speaker()).isEqualTo("coach");
    assertThat(response.openingTurn().textContent())
        .isEqualTo("Hi, I'm calling about my appointment.");
    assertThat(response.openingTurn().ttsAudioUrl()).isNull(); // TTS deferred to #30
    assertThat(repository.inserts).hasSize(1);
    assertThat(repository.inserts.get(0).coachId()).isEqualTo(selectedCoachId);
    assertThat(repository.inserts.get(0).plannedTurns()).isEqualTo(5);
  }

  @Test
  void thirdDailyAttemptReturns429BeforeCallingTheLlm() {
    when(usageRepository.countRoleplaySessions(eq(userId), any(), any())).thenReturn(2);

    assertThatThrownBy(() -> service.start(user(), body(null), UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
              assertThat(error.errorCode()).isEqualTo("rate_limit_exceeded");
            });

    verify(anthropicService, never())
        .callClaude(eq(AiFeature.ROLEPLAY_SESSION_INIT), any(), anyString(), any(), any());
    assertThat(repository.inserts).isEmpty();
  }

  @Test
  void idempotentRetryReturnsSameSessionWithoutSecondLlmCall() {
    String key = UUID.randomUUID().toString();

    PracticeSessionResponse first = service.start(user(), body(null), key);
    PracticeSessionResponse retry = service.start(user(), body(null), key);

    assertThat(retry.id()).isEqualTo(first.id());
    assertThat(repository.inserts).hasSize(1);
    verify(anthropicService, times(1))
        .callClaude(eq(AiFeature.ROLEPLAY_SESSION_INIT), any(), anyString(), any(), any());
  }

  @Test
  void coachIdDefaultsToSelectedCoachWhenOmitted() {
    service.start(user(), body(null), UUID.randomUUID().toString());

    assertThat(repository.inserts.get(0).coachId()).isEqualTo(selectedCoachId);
    verify(userRepository, times(1)).findById(userId);
  }

  @Test
  void explicitCoachIdOverridesSelectedCoach() {
    UUID otherCoach = UUID.randomUUID();
    when(coachRepository.findById(otherCoach)).thenReturn(Optional.of(coach(otherCoach)));

    service.start(user(), body(otherCoach.toString()), UUID.randomUUID().toString());

    assertThat(repository.inserts.get(0).coachId()).isEqualTo(otherCoach);
  }

  @Test
  void getReturnsFullSessionStateWithTurns() {
    PracticeSessionResponse created =
        service.start(user(), body(null), UUID.randomUUID().toString());

    PracticeSessionResponse fetched = service.get(user(), created.id().toString());

    assertThat(fetched.id()).isEqualTo(created.id());
    assertThat(fetched.openingTurn()).isNull(); // omitted on GET
    assertThat(fetched.turns()).hasSize(1);
    assertThat(fetched.turns().get(0).speaker()).isEqualTo("coach");
    assertThat(fetched.turns().get(0).turnNumber()).isEqualTo(1);
  }

  @Test
  void getByNonOwnerReturns404() {
    PracticeSessionResponse created =
        service.start(user(), body(null), UUID.randomUUID().toString());
    InternalAuthPrincipal other = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);

    assertThatThrownBy(() -> service.get(other, created.id().toString()))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(error.errorCode()).isEqualTo("not_found");
            });
  }

  @Test
  void getWithMalformedIdReturns404() {
    assertThatThrownBy(() -> service.get(user(), "not-a-uuid"))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void anonymousPrincipalIsRejected() {
    InternalAuthPrincipal anonymous = new InternalAuthPrincipal(null, "session-token");

    assertThatThrownBy(() -> service.start(anonymous, body(null), UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
    assertThat(repository.inserts).isEmpty();
  }

  @Test
  void missingExpressionIdIsRejected() {
    assertThatThrownBy(() -> service.start(user(), body(null, null), UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> assertThat(error.errorCode()).isEqualTo("validation_failed"));
    assertThat(repository.inserts).isEmpty();
  }

  @Test
  void missingIdempotencyKeyIsRejected() {
    assertThatThrownBy(() -> service.start(user(), body(null), null))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> assertThat(error.errorCode()).isEqualTo("validation_failed"));
    assertThat(repository.inserts).isEmpty();
  }

  @Test
  void notOwnedExpressionReturns404() {
    when(expressionRepository.findByIdForUser(expressionId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.start(user(), body(null), UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
    assertThat(repository.inserts).isEmpty();
  }

  private InternalAuthPrincipal user() {
    return new InternalAuthPrincipal(userId.toString(), null);
  }

  private StartSessionRequest body(String coachId) {
    return new StartSessionRequest(expressionId.toString(), coachId);
  }

  private StartSessionRequest body(String expressionIdOverride, String coachId) {
    return new StartSessionRequest(expressionIdOverride, coachId);
  }

  private ExpressionResponse expression() {
    ExpressionVariantResponse selected =
        new ExpressionVariantResponse(
            selectedVariantId,
            1,
            "정중한",
            "Could you reschedule my appointment?",
            "/.../",
            "쿠쥬",
            "tip",
            "culture",
            null);
    return new ExpressionResponse(
        expressionId,
        "analysis",
        UUID.randomUUID(),
        null,
        "병원 예약을 옮기고 싶었어요",
        selectedVariantId,
        List.of(selected),
        UUID.randomUUID(),
        OffsetDateTime.parse("2026-06-20T00:00:00Z"),
        OffsetDateTime.parse("2026-06-20T00:00:00Z"));
  }

  private static CoachResponse coach(UUID id) {
    return new CoachResponse(id, "mia", "Mia", "Warm and encouraging coach.", "shimmer");
  }

  private static UserResponse user(UUID coachId) {
    return new UserResponse(
        UUID.randomUUID(),
        "u@example.com",
        "User",
        coachId,
        true,
        OffsetDateTime.parse("2026-06-01T00:00:00Z"),
        null);
  }

  private static PromptDefinition promptDefinition() {
    return new PromptDefinition(
        "roleplay_session_init",
        "roleplay-init-v1",
        "claude-sonnet-4-6",
        "roleplay_session_init_v1",
        "2026-06-12",
        null,
        "system prompt body");
  }

  private static JsonNode initOutput(int plannedTurns, String scenarioSetup) {
    try {
      return MAPPER.readTree(
          "{\"planned_turns\":"
              + plannedTurns
              + ",\"scenario_setup\":\""
              + scenarioSetup
              + "\",\"complexity_rationale\":\"의료 상황이라 5턴이 적절\"}");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** In-memory {@link PracticeRepository}; tracks inserts and supports owner/key lookups. */
  private static final class FakePracticeRepository implements PracticeRepository {

    final java.util.List<NewPracticeSession> inserts = new java.util.ArrayList<>();
    private final Map<UUID, PracticeSessionWithTurns> byId = new HashMap<>();
    private final Map<String, PracticeSessionWithTurns> byUserKey = new HashMap<>();

    @Override
    public PracticeSessionWithTurns insertSessionWithOpeningTurn(NewPracticeSession session) {
      String userKey = session.userId() + "|" + session.idempotencyKey();
      if (byUserKey.containsKey(userKey)) {
        throw new DuplicateKeyException("duplicate idempotency key");
      }
      UUID sessionId = UUID.randomUUID();
      OffsetDateTime now = OffsetDateTime.parse("2026-06-26T10:00:00Z");
      PracticeSessionRow row =
          new PracticeSessionRow(
              sessionId,
              session.userId(),
              session.expressionId(),
              session.coachId(),
              "active",
              session.plannedTurns(),
              now,
              null,
              null);
      PracticeTurnRow turn =
          new PracticeTurnRow(
              UUID.randomUUID(),
              sessionId,
              1,
              "coach",
              session.openingText(),
              null,
              null,
              false,
              now);
      PracticeSessionWithTurns saved = new PracticeSessionWithTurns(row, List.of(turn));
      inserts.add(session);
      byId.put(sessionId, saved);
      byUserKey.put(userKey, saved);
      return saved;
    }

    @Override
    public Optional<PracticeSessionWithTurns> findByUserAndKey(UUID userId, UUID idempotencyKey) {
      return Optional.ofNullable(byUserKey.get(userId + "|" + idempotencyKey));
    }

    @Override
    public Optional<PracticeSessionWithTurns> findByIdForOwner(UUID sessionId, UUID userId) {
      PracticeSessionWithTurns saved = byId.get(sessionId);
      if (saved == null || !saved.session().userId().equals(userId)) {
        return Optional.empty();
      }
      return Optional.of(saved);
    }

    @Override
    public Optional<com.phraselog.practice.dto.PracticeResultContext> findResultContext(
        UUID sessionId, UUID userId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public JsonNode saveResultJsonIfAbsent(UUID sessionId, UUID userId, JsonNode resultJson) {
      throw new UnsupportedOperationException();
    }
  }
}
