package com.phraselog.expression.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.analysis.dto.AnalysisRequestRow;
import com.phraselog.analysis.repository.AnalysisRepository;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.expression.dto.CreateExpressionRequest;
import com.phraselog.expression.dto.ExpressionListItem;
import com.phraselog.expression.dto.ExpressionListResponse;
import com.phraselog.expression.dto.ExpressionResponse;
import com.phraselog.expression.dto.NewExpression;
import com.phraselog.expression.repository.ExpressionRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

class ExpressionServiceTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private AnalysisRepository analysisRepository;
  private ExpressionRepository expressionRepository;
  private ExpressionService service;

  @BeforeEach
  void setUp() {
    analysisRepository = mock(AnalysisRepository.class);
    expressionRepository = mock(ExpressionRepository.class);
    service = new ExpressionService(analysisRepository, expressionRepository);
  }

  @Test
  void authenticatedUserSavesOwnedAnalysisIntoExpressionReviewCardDueNow() {
    UUID userId = UUID.randomUUID();
    UUID analysisId = UUID.randomUUID();
    InternalAuthPrincipal principal = new InternalAuthPrincipal(userId.toString(), null);
    AnalysisRequestRow analysis = analysisRow(analysisId, userId);
    ExpressionResponse saved = expressionResponse(analysisId, 1);

    when(expressionRepository.findByAnalysisIdForUser(analysisId, userId))
        .thenReturn(Optional.empty());
    when(analysisRepository.findByIdForOwner(analysisId, principal))
        .thenReturn(Optional.of(analysis));
    when(expressionRepository.createFromAnalysis(org.mockito.ArgumentMatchers.any()))
        .thenReturn(saved);

    var result =
        service.create(
            principal,
            new CreateExpressionRequest(analysisId, null, null),
            UUID.randomUUID().toString());

    assertThat(result.duplicate()).isFalse();
    assertThat(result.expression().analysisRequestId()).isEqualTo(analysisId);
    assertThat(result.expression().sourceType()).isEqualTo("analysis");
    assertThat(result.expression().variants()).hasSize(3);
    assertThat(result.expression().reviewCardId()).isNotNull();
    assertThat(result.expression().nextReviewAt()).isNotNull();

    ArgumentCaptor<NewExpression> captor = ArgumentCaptor.forClass(NewExpression.class);
    org.mockito.Mockito.verify(expressionRepository).createFromAnalysis(captor.capture());
    assertThat(captor.getValue().userId()).isEqualTo(userId);
    assertThat(captor.getValue().analysisRequestId()).isEqualTo(analysisId);
    assertThat(captor.getValue().selectedVariantOrder()).isEqualTo(1);
    assertThat(captor.getValue().originalSituation()).isEqualTo("병원 예약 전화에서 말문이 막혔어요");
    assertThat(captor.getValue().variants()).extracting("variantOrder").containsExactly(1, 2, 3);
  }

  @Test
  void clarificationAndWordCannotBeSavedEvenWithDirectApiCalls() throws Exception {
    UUID userId = UUID.randomUUID();
    var principal = new InternalAuthPrincipal(userId.toString(), null);
    for (String resultType : new String[] {"needs_context", "word"}) {
      UUID id = UUID.randomUUID();
      var row =
          new AnalysisRequestRow(
              id,
              userId,
              null,
              "집주인",
              MAPPER.readTree("{\"result_type\":\"" + resultType + "\"}"),
              "s07-v3",
              null,
              OffsetDateTime.now());
      when(expressionRepository.findByAnalysisIdForUser(id, userId)).thenReturn(Optional.empty());
      when(analysisRepository.findByIdForOwner(id, principal)).thenReturn(Optional.of(row));
      assertThatThrownBy(
              () ->
                  service.create(
                      principal,
                      new CreateExpressionRequest(id, null, null),
                      UUID.randomUUID().toString()))
          .isInstanceOfSatisfying(
              ApiErrorException.class,
              e -> assertThat(e.errorCode()).isEqualTo("analysis_not_saveable"));
    }
    Mockito.verify(expressionRepository, Mockito.never())
        .createFromAnalysis(ArgumentMatchers.any());
  }

  @Test
  void selectedVariantOrderCanBeChanged() {
    UUID userId = UUID.randomUUID();
    UUID analysisId = UUID.randomUUID();
    InternalAuthPrincipal principal = new InternalAuthPrincipal(userId.toString(), null);
    when(expressionRepository.findByAnalysisIdForUser(analysisId, userId))
        .thenReturn(Optional.empty());
    when(analysisRepository.findByIdForOwner(analysisId, principal))
        .thenReturn(Optional.of(analysisRow(analysisId, userId)));
    when(expressionRepository.createFromAnalysis(org.mockito.ArgumentMatchers.any()))
        .thenReturn(expressionResponse(analysisId, 2));

    var result =
        service.create(
            principal,
            new CreateExpressionRequest(analysisId, 2, null),
            UUID.randomUUID().toString());

    assertThat(result.expression().selectedVariantId())
        .isEqualTo(result.expression().variants().get(1).id());
  }

  @Test
  void duplicateSaveReturnsExistingExpressionAsConflictResult() {
    UUID userId = UUID.randomUUID();
    UUID analysisId = UUID.randomUUID();
    InternalAuthPrincipal principal = new InternalAuthPrincipal(userId.toString(), null);
    ExpressionResponse existing = expressionResponse(analysisId, 1);
    when(expressionRepository.findByAnalysisIdForUser(analysisId, userId))
        .thenReturn(Optional.of(existing));

    var result =
        service.create(
            principal,
            new CreateExpressionRequest(analysisId, 1, null),
            UUID.randomUUID().toString());

    assertThat(result.duplicate()).isTrue();
    assertThat(result.expression()).isEqualTo(existing);
  }

  @Test
  void sessionTokenPrincipalCannotSaveExpressions() {
    InternalAuthPrincipal principal = new InternalAuthPrincipal(null, "session-abc");

    assertThatThrownBy(
            () ->
                service.create(
                    principal,
                    new CreateExpressionRequest(UUID.randomUUID(), 1, null),
                    UUID.randomUUID().toString()))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error ->
                assertThat(((ApiErrorException) error).status())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));

    verifyNoInteractions(analysisRepository, expressionRepository);
  }

  @Test
  void missingOrNotOwnedAnalysisReturns404() {
    UUID userId = UUID.randomUUID();
    UUID analysisId = UUID.randomUUID();
    InternalAuthPrincipal principal = new InternalAuthPrincipal(userId.toString(), null);
    when(expressionRepository.findByAnalysisIdForUser(analysisId, userId))
        .thenReturn(Optional.empty());
    when(analysisRepository.findByIdForOwner(analysisId, principal)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.create(
                    principal,
                    new CreateExpressionRequest(analysisId, 1, null),
                    UUID.randomUUID().toString()))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error ->
                assertThat(((ApiErrorException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void authenticatedUserClaimsAnonymousAnalysisWithMatchingSessionTokenThenSaves() {
    UUID userId = UUID.randomUUID();
    UUID analysisId = UUID.randomUUID();
    String sessionToken = "anon-session-xyz";
    InternalAuthPrincipal principal = new InternalAuthPrincipal(userId.toString(), null);
    // 분석은 아직 이 사용자 소유가 아니다 → 소유 조회는 비어 있고, 일치하는 토큰으로 claim이 성공한다.
    when(expressionRepository.findByAnalysisIdForUser(analysisId, userId))
        .thenReturn(Optional.empty());
    when(analysisRepository.findByIdForOwner(analysisId, principal)).thenReturn(Optional.empty());
    when(analysisRepository.claimAnonymousAnalysis(analysisId, sessionToken, userId))
        .thenReturn(Optional.of(analysisRow(analysisId, userId)));
    when(expressionRepository.createFromAnalysis(org.mockito.ArgumentMatchers.any()))
        .thenReturn(expressionResponse(analysisId, 1));

    var result =
        service.create(
            principal,
            new CreateExpressionRequest(analysisId, 1, sessionToken),
            UUID.randomUUID().toString());

    assertThat(result.duplicate()).isFalse();
    assertThat(result.expression().analysisRequestId()).isEqualTo(analysisId);
    org.mockito.Mockito.verify(analysisRepository)
        .claimAnonymousAnalysis(analysisId, sessionToken, userId);
  }

  @Test
  void alreadyOwnedAnalysisSavesWithoutAttemptingAClaim() {
    UUID userId = UUID.randomUUID();
    UUID analysisId = UUID.randomUUID();
    InternalAuthPrincipal principal = new InternalAuthPrincipal(userId.toString(), null);
    when(expressionRepository.findByAnalysisIdForUser(analysisId, userId))
        .thenReturn(Optional.empty());
    when(analysisRepository.findByIdForOwner(analysisId, principal))
        .thenReturn(Optional.of(analysisRow(analysisId, userId)));
    when(expressionRepository.createFromAnalysis(org.mockito.ArgumentMatchers.any()))
        .thenReturn(expressionResponse(analysisId, 1));

    // 토큰을 함께 보내더라도, 이미 소유한 분석이면 claim 경로를 타지 않는다.
    var result =
        service.create(
            principal,
            new CreateExpressionRequest(analysisId, 1, "irrelevant-token"),
            UUID.randomUUID().toString());

    assertThat(result.duplicate()).isFalse();
    org.mockito.Mockito.verify(analysisRepository, org.mockito.Mockito.never())
        .claimAnonymousAnalysis(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void mismatchedSessionTokenCannotHijackAnotherSessionsAnalysis() {
    UUID userId = UUID.randomUUID();
    UUID analysisId = UUID.randomUUID();
    String wrongToken = "attacker-session-token";
    InternalAuthPrincipal principal = new InternalAuthPrincipal(userId.toString(), null);
    when(expressionRepository.findByAnalysisIdForUser(analysisId, userId))
        .thenReturn(Optional.empty());
    when(analysisRepository.findByIdForOwner(analysisId, principal)).thenReturn(Optional.empty());
    // 토큰 불일치 → claim이 0행 → 빈 결과. 절도 불가, 404로 매핑된다.
    when(analysisRepository.claimAnonymousAnalysis(analysisId, wrongToken, userId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.create(
                    principal,
                    new CreateExpressionRequest(analysisId, 1, wrongToken),
                    UUID.randomUUID().toString()))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error ->
                assertThat(((ApiErrorException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));

    // 절도 시도는 저장으로 이어지지 않는다.
    org.mockito.Mockito.verify(expressionRepository, org.mockito.Mockito.never())
        .createFromAnalysis(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void anonymousAnalysisWithoutAClaimTokenReturns404() {
    UUID userId = UUID.randomUUID();
    UUID analysisId = UUID.randomUUID();
    InternalAuthPrincipal principal = new InternalAuthPrincipal(userId.toString(), null);
    when(expressionRepository.findByAnalysisIdForUser(analysisId, userId))
        .thenReturn(Optional.empty());
    when(analysisRepository.findByIdForOwner(analysisId, principal)).thenReturn(Optional.empty());

    // 토큰 없이 남의(또는 익명) 분석을 저장 시도 → claim 경로 자체를 타지 않고 404.
    assertThatThrownBy(
            () ->
                service.create(
                    principal,
                    new CreateExpressionRequest(analysisId, 1, null),
                    UUID.randomUUID().toString()))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error ->
                assertThat(((ApiErrorException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));

    org.mockito.Mockito.verify(analysisRepository, org.mockito.Mockito.never())
        .claimAnonymousAnalysis(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  // ── #45 list / detail / delete ──────────────────────────────────────────────

  @Test
  void listClampsLimitAboveMaxAndTruncatesQueryTo100Chars() {
    UUID userId = UUID.randomUUID();
    InternalAuthPrincipal principal = new InternalAuthPrincipal(userId.toString(), null);
    String longQuery = "가".repeat(150);
    when(expressionRepository.list(
            ArgumentMatchers.eq(userId),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.anyInt()))
        .thenReturn(List.of());

    service.list(principal, longQuery, null, 500);

    ArgumentCaptor<String> qCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
    Mockito.verify(expressionRepository)
        .list(
            ArgumentMatchers.eq(userId),
            qCaptor.capture(),
            ArgumentMatchers.isNull(),
            ArgumentMatchers.isNull(),
            limitCaptor.capture());
    assertThat(qCaptor.getValue()).hasSize(100);
    assertThat(limitCaptor.getValue()).isEqualTo(100);
  }

  @Test
  void listDefaultsLimitWhenMissingOrNonPositiveAndIgnoresBlankQuery() {
    UUID userId = UUID.randomUUID();
    InternalAuthPrincipal principal = new InternalAuthPrincipal(userId.toString(), null);
    when(expressionRepository.list(
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.anyInt()))
        .thenReturn(List.of());

    service.list(principal, "   ", null, 0);

    Mockito.verify(expressionRepository)
        .list(
            ArgumentMatchers.eq(userId),
            ArgumentMatchers.isNull(),
            ArgumentMatchers.isNull(),
            ArgumentMatchers.isNull(),
            ArgumentMatchers.eq(20));
  }

  @Test
  void listEmitsNextCursorOnFullPageAndNullOnPartialPage() {
    UUID userId = UUID.randomUUID();
    InternalAuthPrincipal principal = new InternalAuthPrincipal(userId.toString(), null);
    // 가득 찬 페이지(=limit)면 마지막 행 기준 next_cursor 발급.
    when(expressionRepository.list(
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.eq(2)))
        .thenReturn(List.of(listItem(), listItem()));
    ExpressionListResponse full = service.list(principal, null, null, 2);
    assertThat(full.items()).hasSize(2);
    assertThat(full.nextCursor()).isNotNull();

    // 덜 찬 페이지면 next_cursor 없음.
    when(expressionRepository.list(
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.eq(5)))
        .thenReturn(List.of(listItem()));
    ExpressionListResponse partial = service.list(principal, null, null, 5);
    assertThat(partial.nextCursor()).isNull();
  }

  @Test
  void nextCursorRoundTripsBackIntoTheKeysetArguments() {
    UUID userId = UUID.randomUUID();
    InternalAuthPrincipal principal = new InternalAuthPrincipal(userId.toString(), null);
    ExpressionListItem last = listItem();
    when(expressionRepository.list(
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.eq(1)))
        .thenReturn(List.of(last));

    String cursor = service.list(principal, null, null, 1).nextCursor();
    service.list(principal, null, cursor, 1);

    ArgumentCaptor<OffsetDateTime> createdAt = ArgumentCaptor.forClass(OffsetDateTime.class);
    ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
    Mockito.verify(expressionRepository, Mockito.times(2))
        .list(
            ArgumentMatchers.eq(userId),
            ArgumentMatchers.isNull(),
            createdAt.capture(),
            id.capture(),
            ArgumentMatchers.eq(1));
    // 2번째 호출에서 디코딩된 keyset 값이 마지막 행과 일치.
    assertThat(createdAt.getAllValues().get(1)).isEqualTo(last.createdAt());
    assertThat(id.getAllValues().get(1)).isEqualTo(last.id());
  }

  @Test
  void invalidCursorReturns400() {
    InternalAuthPrincipal principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);

    assertThatThrownBy(() -> service.list(principal, null, "not-a-valid-cursor!!", 20))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error ->
                assertThat(((ApiErrorException) error).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    verifyNoInteractions(expressionRepository);
  }

  @Test
  void getReturnsExpressionWhenOwnedAnd404WhenMissing() {
    UUID userId = UUID.randomUUID();
    UUID expressionId = UUID.randomUUID();
    InternalAuthPrincipal principal = new InternalAuthPrincipal(userId.toString(), null);
    ExpressionResponse expression = expressionResponse(UUID.randomUUID(), 1);
    when(expressionRepository.findByIdForUser(expressionId, userId))
        .thenReturn(Optional.of(expression));

    assertThat(service.get(principal, expressionId)).isEqualTo(expression);

    when(expressionRepository.findByIdForUser(expressionId, userId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(principal, expressionId))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error ->
                assertThat(((ApiErrorException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void deleteSucceedsWhenRowUpdatedAnd404WhenNothingDeleted() {
    UUID userId = UUID.randomUUID();
    UUID expressionId = UUID.randomUUID();
    InternalAuthPrincipal principal = new InternalAuthPrincipal(userId.toString(), null);
    when(expressionRepository.softDelete(expressionId, userId)).thenReturn(true);

    service.delete(principal, expressionId); // no throw

    when(expressionRepository.softDelete(expressionId, userId)).thenReturn(false);
    assertThatThrownBy(() -> service.delete(principal, expressionId))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error ->
                assertThat(((ApiErrorException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  private static ExpressionListItem listItem() {
    return new ExpressionListItem(
        UUID.randomUUID(),
        "병원 예약 전화에서 말문이 막혔어요",
        "I'd like to make an appointment.",
        "정중한",
        OffsetDateTime.parse("2026-06-19T12:00:00Z"));
  }

  private static AnalysisRequestRow analysisRow(UUID analysisId, UUID userId) {
    return new AnalysisRequestRow(
        analysisId,
        userId,
        null,
        "병원 예약 전화에서 말문이 막혔어요",
        analysisOutput(),
        "s07-v1",
        null,
        OffsetDateTime.parse("2026-06-19T12:00:00Z"));
  }

  private static JsonNode analysisOutput() {
    try {
      return MAPPER.readTree(
          """
          {"expressions":[
            {"english":"I'd like to make an appointment.","tone_label":"정중한","ipa":"/a/","korean_pronunciation":"아이드","pronunciation_tip":"Keep it short.","cultural_tip":"Use would like for polite requests."},
            {"english":"Could I schedule a visit?","tone_label":"부드러운","ipa":"/b/","korean_pronunciation":"쿠드","pronunciation_tip":"Raise could slightly.","cultural_tip":"Schedule is natural for clinics."},
            {"english":"Is there any availability this week?","tone_label":"간접적인","ipa":"/c/","korean_pronunciation":"이즈","pronunciation_tip":"Link there any.","cultural_tip":"Availability is common for appointment slots."}
          ]}
          """);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static ExpressionResponse expressionResponse(UUID analysisId, int selectedOrder) {
    UUID expressionId = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID third = UUID.randomUUID();
    var variants =
        java.util.List.of(
            new com.phraselog.expression.dto.ExpressionVariantResponse(
                first,
                1,
                "정중한",
                "I'd like to make an appointment.",
                "/a/",
                "아이드",
                "Keep it short.",
                "Use would like for polite requests.",
                null),
            new com.phraselog.expression.dto.ExpressionVariantResponse(
                second,
                2,
                "부드러운",
                "Could I schedule a visit?",
                "/b/",
                "쿠드",
                "Raise could slightly.",
                "Schedule is natural for clinics.",
                null),
            new com.phraselog.expression.dto.ExpressionVariantResponse(
                third,
                3,
                "간접적인",
                "Is there any availability this week?",
                "/c/",
                "이즈",
                "Link there any.",
                "Availability is common for appointment slots.",
                null));
    UUID selected = selectedOrder == 2 ? second : first;
    return new ExpressionResponse(
        expressionId,
        "analysis",
        analysisId,
        null,
        "병원 예약 전화에서 말문이 막혔어요",
        selected,
        variants,
        UUID.randomUUID(),
        OffsetDateTime.parse("2026-06-19T12:00:00Z"),
        OffsetDateTime.parse("2026-06-19T12:00:00Z"));
  }
}
