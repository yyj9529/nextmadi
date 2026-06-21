package com.phraselog.expression.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.GlobalExceptionHandler;
import com.phraselog.expression.dto.ExpressionResponse;
import com.phraselog.expression.dto.ExpressionVariantResponse;
import com.phraselog.expression.dto.SaveExpressionResult;
import com.phraselog.expression.service.ExpressionService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExpressionControllerTests {

  private ExpressionService expressionService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    expressionService = mock(ExpressionService.class);
    ExpressionController controller = new ExpressionController(expressionService);
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
  }

  @Test
  void postReturns201ForNewSave() throws Exception {
    UUID analysisId = UUID.randomUUID();
    InternalAuthPrincipal principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    String idempotencyKey = UUID.randomUUID().toString();
    ExpressionResponse response = expressionResponse(analysisId);
    when(expressionService.create(eq(principal), any(), eq(idempotencyKey)))
        .thenReturn(new SaveExpressionResult(response, false));

    mockMvc
        .perform(
            post("/api/v1/expressions")
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content("{\"analysis_request_id\":\"" + analysisId + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.analysis_request_id").value(analysisId.toString()))
        .andExpect(jsonPath("$.source_type").value("analysis"))
        .andExpect(jsonPath("$.variants.length()").value(3))
        .andExpect(jsonPath("$.review_card_id").value(response.reviewCardId().toString()));

    verify(expressionService).create(eq(principal), any(), eq(idempotencyKey));
  }

  @Test
  void duplicateSaveReturns409WithExistingExpressionBody() throws Exception {
    UUID analysisId = UUID.randomUUID();
    InternalAuthPrincipal principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    String idempotencyKey = UUID.randomUUID().toString();
    ExpressionResponse response = expressionResponse(analysisId);
    when(expressionService.create(eq(principal), any(), eq(idempotencyKey)))
        .thenReturn(new SaveExpressionResult(response, true));

    mockMvc
        .perform(
            post("/api/v1/expressions")
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content("{\"analysis_request_id\":\"" + analysisId + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.id").value(response.id().toString()))
        .andExpect(jsonPath("$.analysis_request_id").value(analysisId.toString()));
  }

  @Test
  void bindsSessionTokenClaimFromBodyAndForwardsItToTheService() throws Exception {
    UUID analysisId = UUID.randomUUID();
    InternalAuthPrincipal principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    String idempotencyKey = UUID.randomUUID().toString();
    when(expressionService.create(eq(principal), any(), eq(idempotencyKey)))
        .thenReturn(new SaveExpressionResult(expressionResponse(analysisId), false));

    mockMvc
        .perform(
            post("/api/v1/expressions")
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content(
                    "{\"analysis_request_id\":\""
                        + analysisId
                        + "\",\"selected_variant_order\":2,\"session_token\":\"anon-session-xyz\"}"))
        .andExpect(status().isCreated());

    org.mockito.ArgumentCaptor<com.phraselog.expression.dto.CreateExpressionRequest> captor =
        org.mockito.ArgumentCaptor.forClass(com.phraselog.expression.dto.CreateExpressionRequest.class);
    verify(expressionService).create(eq(principal), captor.capture(), eq(idempotencyKey));
    org.assertj.core.api.Assertions.assertThat(captor.getValue().sessionToken())
        .isEqualTo("anon-session-xyz");
    org.assertj.core.api.Assertions.assertThat(captor.getValue().selectedVariantOrder())
        .isEqualTo(2);
  }

  @Test
  void postWithoutVerifiedPrincipalReturns401() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/expressions")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"analysis_request_id\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error_code").value("internal_auth_invalid"));
  }

  private static ExpressionResponse expressionResponse(UUID analysisId) {
    UUID expressionId = UUID.randomUUID();
    UUID selectedVariantId = UUID.randomUUID();
    List<ExpressionVariantResponse> variants =
        List.of(
            variant(selectedVariantId, 1),
            variant(UUID.randomUUID(), 2),
            variant(UUID.randomUUID(), 3));
    return new ExpressionResponse(
        expressionId,
        "analysis",
        analysisId,
        null,
        "병원 예약 전화에서 말문이 막혔어요",
        selectedVariantId,
        variants,
        UUID.randomUUID(),
        OffsetDateTime.parse("2026-06-19T12:00:00Z"),
        OffsetDateTime.parse("2026-06-19T12:00:00Z"));
  }

  private static ExpressionVariantResponse variant(UUID id, int order) {
    return new ExpressionVariantResponse(
        id,
        order,
        "정중한",
        "I'd like to make an appointment.",
        "/a/",
        "아이드",
        "Keep it short.",
        "Use would like for polite requests.",
        null);
  }
}
