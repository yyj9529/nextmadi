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
import com.phraselog.expression.dto.ExpressionResponse;
import com.phraselog.expression.dto.NewExpression;
import com.phraselog.expression.repository.ExpressionRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
            principal, new CreateExpressionRequest(analysisId, null), UUID.randomUUID().toString());

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
            principal, new CreateExpressionRequest(analysisId, 2), UUID.randomUUID().toString());

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
            principal, new CreateExpressionRequest(analysisId, 1), UUID.randomUUID().toString());

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
                    new CreateExpressionRequest(UUID.randomUUID(), 1),
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
                    new CreateExpressionRequest(analysisId, 1),
                    UUID.randomUUID().toString()))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error ->
                assertThat(((ApiErrorException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));
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
