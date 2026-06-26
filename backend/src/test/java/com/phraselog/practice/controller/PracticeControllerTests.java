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
import com.phraselog.practice.dto.PracticeSessionResponse;
import com.phraselog.practice.dto.PracticeTurnResponse;
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
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(PracticeSessionService.class);
    PracticeController controller = new PracticeController(service);
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
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
}
