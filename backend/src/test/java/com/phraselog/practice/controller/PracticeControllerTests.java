package com.phraselog.practice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.GlobalExceptionHandler;
import com.phraselog.expression.dto.ExpressionResponse;
import com.phraselog.expression.dto.SaveExpressionResult;
import com.phraselog.practice.dto.PracticeSessionResponse;
import com.phraselog.practice.dto.PracticeTurnResponse;
import com.phraselog.practice.service.PracticeResultService;
import com.phraselog.practice.service.PracticeSessionService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PracticeControllerTests {

  private PracticeSessionService service;
  private PracticeResultService resultService;
  private ObjectMapper objectMapper;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(PracticeSessionService.class);
    resultService = mock(PracticeResultService.class);
    PracticeController controller = new PracticeController(service, resultService);
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
  }

  @Test
  void postReturns201WithOpeningTurnAndPassesPrincipalAndKey() throws Exception {
    InternalAuthPrincipal principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    String idempotencyKey = UUID.randomUUID().toString();
    when(service.start(eq(principal), any(), eq(idempotencyKey))).thenReturn(startResponse());

    mockMvc
        .perform(
            post("/api/v1/practice/sessions")
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content("{\"expression_id\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("active"))
        .andExpect(jsonPath("$.planned_turns").value(5))
        .andExpect(jsonPath("$.opening_turn.turn_number").value(1))
        .andExpect(jsonPath("$.opening_turn.speaker").value("coach"))
        .andExpect(jsonPath("$.turns").doesNotExist());

    verify(service).start(eq(principal), any(), eq(idempotencyKey));
  }

  @Test
  void postWithoutVerifiedPrincipalReturns401() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/practice/sessions")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"expression_id\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error_code").value("internal_auth_invalid"));
  }

  @Test
  void getReturns200WithTurnsAndDelegatesPathId() throws Exception {
    InternalAuthPrincipal principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    PracticeSessionResponse response = getResponse();
    when(service.get(eq(principal), eq(response.id().toString()))).thenReturn(response);

    mockMvc
        .perform(
            get("/api/v1/practice/sessions/{id}", response.id().toString())
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(response.id().toString()))
        .andExpect(jsonPath("$.turns.length()").value(1))
        .andExpect(jsonPath("$.opening_turn").doesNotExist());

    verify(service).get(eq(principal), eq(response.id().toString()));
  }

  @Test
  void postResultReturnsGeneratedJsonAndDelegatesPathId() throws Exception {
    InternalAuthPrincipal principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    UUID sessionId = UUID.randomUUID();
    when(resultService.generateResult(eq(principal), eq(sessionId.toString())))
        .thenReturn(objectMapper.readTree("{\"coach_encouragement\":\"nice\"}"));

    mockMvc
        .perform(
            post("/api/v1/practice/sessions/{id}/result", sessionId.toString())
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coach_encouragement").value("nice"));

    verify(resultService).generateResult(eq(principal), eq(sessionId.toString()));
  }

  @Test
  void postSaveExpressionReturns201ForNewSaveAnd409ForDuplicate() throws Exception {
    InternalAuthPrincipal principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    UUID sessionId = UUID.randomUUID();
    String firstKey = UUID.randomUUID().toString();
    String duplicateKey = UUID.randomUUID().toString();
    ExpressionResponse expression = expressionResponse(sessionId);
    when(resultService.saveExpression(eq(principal), eq(sessionId.toString()), any(), eq(firstKey)))
        .thenReturn(new SaveExpressionResult(expression, false));
    when(resultService.saveExpression(
            eq(principal), eq(sessionId.toString()), any(), eq(duplicateKey)))
        .thenReturn(new SaveExpressionResult(expression, true));

    mockMvc
        .perform(
            post("/api/v1/practice/sessions/{id}/save-expression", sessionId.toString())
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal)
                .header("Idempotency-Key", firstKey)
                .contentType("application/json")
                .content("{\"recommended_expression_index\":1}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(expression.id().toString()));

    mockMvc
        .perform(
            post("/api/v1/practice/sessions/{id}/save-expression", sessionId.toString())
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal)
                .header("Idempotency-Key", duplicateKey)
                .contentType("application/json")
                .content("{\"recommended_expression_index\":1}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.id").value(expression.id().toString()));
  }

  private static PracticeSessionResponse startResponse() {
    return new PracticeSessionResponse(
        UUID.randomUUID(),
        "active",
        5,
        UUID.randomUUID(),
        UUID.randomUUID(),
        OffsetDateTime.parse("2026-06-26T10:00:00Z"),
        null,
        null,
        null,
        openingTurn());
  }

  private static PracticeSessionResponse getResponse() {
    return new PracticeSessionResponse(
        UUID.randomUUID(),
        "active",
        5,
        UUID.randomUUID(),
        UUID.randomUUID(),
        OffsetDateTime.parse("2026-06-26T10:00:00Z"),
        null,
        null,
        List.of(openingTurn()),
        null);
  }

  private static PracticeTurnResponse openingTurn() {
    return new PracticeTurnResponse(
        UUID.randomUUID(),
        1,
        "coach",
        "Hello there.",
        null,
        null,
        false,
        OffsetDateTime.parse("2026-06-26T10:00:00Z"));
  }

  private static ExpressionResponse expressionResponse(UUID sessionId) {
    return new ExpressionResponse(
        UUID.randomUUID(),
        "roleplay_result",
        null,
        sessionId,
        "doctor appointment",
        UUID.randomUUID(),
        List.of(),
        UUID.randomUUID(),
        OffsetDateTime.parse("2026-06-26T10:00:00Z"),
        OffsetDateTime.parse("2026-06-26T10:00:00Z"));
  }
}
