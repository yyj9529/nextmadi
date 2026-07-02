package com.phraselog.practice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.client.service.AnthropicService;
import com.phraselog.ai.logging.dto.AiFeature;
import com.phraselog.ai.prompt.dto.PromptDefinition;
import com.phraselog.ai.prompt.service.PromptLoader;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.expression.dto.ExpressionResponse;
import com.phraselog.expression.dto.ExpressionVariantResponse;
import com.phraselog.expression.dto.NewRoleplayExpression;
import com.phraselog.expression.dto.SaveExpressionResult;
import com.phraselog.expression.repository.ExpressionRepository;
import com.phraselog.practice.dto.PracticeResultContext;
import com.phraselog.practice.dto.PracticeResultTurn;
import com.phraselog.practice.dto.SaveRoleplayExpressionRequest;
import com.phraselog.practice.repository.PracticeRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class PracticeResultServiceTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private FakePracticeRepository practiceRepository;
  private ExpressionRepository expressionRepository;
  private AnthropicService anthropicService;
  private PromptLoader promptLoader;
  private PracticeResultService service;

  private UUID userId;
  private UUID sessionId;

  @BeforeEach
  void setUp() {
    practiceRepository = new FakePracticeRepository();
    expressionRepository = mock(ExpressionRepository.class);
    anthropicService = mock(AnthropicService.class);
    promptLoader = mock(PromptLoader.class);
    service =
        new PracticeResultService(
            practiceRepository, expressionRepository, anthropicService, promptLoader, MAPPER);

    userId = UUID.randomUUID();
    sessionId = UUID.randomUUID();
    when(promptLoader.load("roleplay/result", 1)).thenReturn(promptDefinition());
  }

  @Test
  void completedSessionGeneratesStoresAndReturnsResult() throws Exception {
    practiceRepository.context = completedContext(null);
    JsonNode generated = resultJson();
    when(anthropicService.callClaude(
            eq(AiFeature.ROLEPLAY_RESULT), any(), any(), eq(userId), any()))
        .thenReturn(generated);

    JsonNode result = service.generateResult(user(), sessionId.toString());

    assertThat(result).isEqualTo(generated);
    assertThat(practiceRepository.savedResults).containsExactly(generated);
    verify(anthropicService)
        .callClaude(eq(AiFeature.ROLEPLAY_RESULT), any(), any(), eq(userId), any());
  }

  @Test
  void cachedResultDoesNotCallClaudeAgain() throws Exception {
    JsonNode cached = resultJson();
    practiceRepository.context = completedContext(cached);

    JsonNode result = service.generateResult(user(), sessionId.toString());

    assertThat(result).isEqualTo(cached);
    assertThat(practiceRepository.savedResults).isEmpty();
    verify(anthropicService, never()).callClaude(any(), any(), any(), any(), any());
  }

  @Test
  void activeOrAbandonedSessionCannotGenerateResult() {
    practiceRepository.context = context("active", null);

    assertThatThrownBy(() -> service.generateResult(user(), sessionId.toString()))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));

    verify(anthropicService, never()).callClaude(any(), any(), any(), any(), any());
  }

  @Test
  void providerFailureLeavesNoCachedResultSoRetryStartsFresh() throws Exception {
    practiceRepository.context = completedContext(null);
    ApiErrorException timeout =
        new ApiErrorException(
            HttpStatus.REQUEST_TIMEOUT, "timeout", "timeout", "provider timed out", true);
    when(anthropicService.callClaude(
            eq(AiFeature.ROLEPLAY_RESULT), any(), any(), eq(userId), any()))
        .thenThrow(timeout)
        .thenReturn(resultJson());

    assertThatThrownBy(() -> service.generateResult(user(), sessionId.toString()))
        .isSameAs(timeout);
    assertThat(practiceRepository.savedResults).isEmpty();

    JsonNode retry = service.generateResult(user(), sessionId.toString());
    assertThat(retry).isEqualTo(resultJson());
    verify(anthropicService, org.mockito.Mockito.times(2))
        .callClaude(eq(AiFeature.ROLEPLAY_RESULT), any(), any(), eq(userId), any());
  }

  @Test
  void saveRecommendedExpressionCreatesRoleplayExpressionWithSelectedVariant() throws Exception {
    practiceRepository.context = completedContext(resultJson());
    when(expressionRepository.findByRoleplayIdempotencyKey(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(expressionRepository.findActiveRoleplaySaveByIndex(sessionId, userId, 1))
        .thenReturn(Optional.empty());
    ExpressionResponse saved = roleplayExpression(2);
    when(expressionRepository.createFromRoleplayResult(any())).thenReturn(saved);

    SaveExpressionResult result =
        service.saveExpression(
            user(),
            sessionId.toString(),
            new SaveRoleplayExpressionRequest(1),
            UUID.randomUUID().toString());

    assertThat(result.duplicate()).isFalse();
    assertThat(result.expression()).isEqualTo(saved);
    ArgumentCaptor<NewRoleplayExpression> captor =
        ArgumentCaptor.forClass(NewRoleplayExpression.class);
    verify(expressionRepository).createFromRoleplayResult(captor.capture());
    assertThat(captor.getValue().practiceSessionId()).isEqualTo(sessionId);
    assertThat(captor.getValue().roleplayResultIndex()).isEqualTo(1);
    assertThat(captor.getValue().selectedVariantOrder()).isEqualTo(2);
    assertThat(captor.getValue().originalSituation()).isEqualTo("doctor appointment");
    assertThat(captor.getValue().variants()).hasSize(3);
  }

  @Test
  void idempotentSaveReturnsExistingExpressionWithoutCreatingAnother() {
    UUID key = UUID.randomUUID();
    ExpressionResponse existing = roleplayExpression(1);
    when(expressionRepository.findByRoleplayIdempotencyKey(sessionId, userId, key))
        .thenReturn(Optional.of(existing));

    SaveExpressionResult result =
        service.saveExpression(
            user(), sessionId.toString(), new SaveRoleplayExpressionRequest(0), key.toString());

    assertThat(result.duplicate()).isTrue();
    assertThat(result.expression()).isEqualTo(existing);
    verify(expressionRepository, never()).createFromRoleplayResult(any());
  }

  @Test
  void invalidRecommendedExpressionIndexReturns400() {
    assertThatThrownBy(
            () ->
                service.saveExpression(
                    user(),
                    sessionId.toString(),
                    new SaveRoleplayExpressionRequest(3),
                    UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void saveWithoutCachedResultReturns409() {
    practiceRepository.context = completedContext(null);
    when(expressionRepository.findByRoleplayIdempotencyKey(any(), any(), any()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.saveExpression(
                    user(),
                    sessionId.toString(),
                    new SaveRoleplayExpressionRequest(0),
                    UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void sessionTokenPrincipalCannotGenerateOrSaveResult() {
    InternalAuthPrincipal anonymous = InternalAuthPrincipal.ofSession("anon-session");

    assertThatThrownBy(() -> service.generateResult(anonymous, sessionId.toString()))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.UNAUTHORIZED));

    assertThatThrownBy(
            () ->
                service.saveExpression(
                    anonymous,
                    sessionId.toString(),
                    new SaveRoleplayExpressionRequest(0),
                    UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  private InternalAuthPrincipal user() {
    return InternalAuthPrincipal.ofUser(userId.toString());
  }

  private PracticeResultContext completedContext(JsonNode resultJson) {
    return context("completed", resultJson);
  }

  private PracticeResultContext context(String status, JsonNode resultJson) {
    return new PracticeResultContext(
        sessionId,
        userId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        status,
        3,
        resultJson,
        "doctor appointment",
        "Could you repeat that?",
        "Mia",
        "Warm coach",
        List.of(new PracticeResultTurn(1, "coach", "Let's practice.", null, false, null)));
  }

  private static PromptDefinition promptDefinition() {
    return new PromptDefinition(
        "roleplay_result",
        "roleplay-result-v1",
        "claude-sonnet-4-6",
        "roleplay_result_v1",
        "2026-06-12",
        null,
        "prompt body");
  }

  private static JsonNode resultJson() {
    try {
      return MAPPER.readTree(
          """
          {
            "recommended_expressions": [
              {"english":"Could you repeat that?","tone_label":"polite","ipa":"/a/","korean_pronunciation":"could","pronunciation_tip":"short could","cultural_tip":"clarification"},
              {"english":"I want to make sure I understood.","tone_label":"careful","ipa":"/b/","korean_pronunciation":"want","pronunciation_tip":"link words","cultural_tip":"careful check"},
              {"english":"Can I say that back to you?","tone_label":"confirming","ipa":"/c/","korean_pronunciation":"can","pronunciation_tip":"light can","cultural_tip":"paraphrase"}
            ],
            "awkward_pairs": [],
            "pronunciation_focus_words": [],
            "coach_encouragement": "잘했어요."
          }
          """);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static ExpressionResponse roleplayExpression(int selectedOrder) {
    UUID expressionId = UUID.randomUUID();
    List<ExpressionVariantResponse> variants = List.of(variant(1), variant(2), variant(3));
    return new ExpressionResponse(
        expressionId,
        "roleplay_result",
        null,
        UUID.randomUUID(),
        "doctor appointment",
        variants.get(selectedOrder - 1).id(),
        variants,
        UUID.randomUUID(),
        OffsetDateTime.parse("2026-06-26T00:00:00Z"),
        OffsetDateTime.parse("2026-06-26T00:00:00Z"));
  }

  private static ExpressionVariantResponse variant(int order) {
    return new ExpressionVariantResponse(
        UUID.randomUUID(),
        order,
        "tone " + order,
        "English " + order,
        "/" + order + "/",
        "pron " + order,
        "tip " + order,
        "culture " + order,
        null);
  }

  private static final class FakePracticeRepository implements PracticeRepository {

    PracticeResultContext context;
    List<JsonNode> savedResults = new ArrayList<>();

    @Override
    public com.phraselog.practice.dto.PracticeSessionWithTurns insertSessionWithOpeningTurn(
        com.phraselog.practice.dto.NewPracticeSession session) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<com.phraselog.practice.dto.PracticeSessionWithTurns> findByUserAndKey(
        UUID userId, UUID idempotencyKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<com.phraselog.practice.dto.PracticeSessionWithTurns> findByIdForOwner(
        UUID sessionId, UUID userId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<PracticeResultContext> findResultContext(UUID sessionId, UUID userId) {
      if (context == null
          || !context.sessionId().equals(sessionId)
          || !context.userId().equals(userId)) {
        return Optional.empty();
      }
      return Optional.of(context);
    }

    @Override
    public JsonNode saveResultJsonIfAbsent(UUID sessionId, UUID userId, JsonNode resultJson) {
      savedResults.add(resultJson);
      context =
          new PracticeResultContext(
              context.sessionId(),
              context.userId(),
              context.expressionId(),
              context.coachId(),
              context.status(),
              context.plannedTurns(),
              resultJson,
              context.originalSituation(),
              context.selectedExpression(),
              context.coachName(),
              context.coachPersona(),
              context.turns());
      return resultJson;
    }
  }
}
