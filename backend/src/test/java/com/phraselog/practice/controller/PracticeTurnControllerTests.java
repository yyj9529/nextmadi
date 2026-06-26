package com.phraselog.practice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.common.web.GlobalExceptionHandler;
import com.phraselog.practice.dto.PracticeTurnResponse;
import com.phraselog.practice.dto.SubmitPracticeTurnRequest;
import com.phraselog.practice.dto.SubmitPracticeTurnResponse;
import com.phraselog.practice.service.PracticeTurnService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PracticeTurnControllerTests {

  private PracticeTurnService service;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(PracticeTurnService.class);
    PracticeTurnController controller = new PracticeTurnController(service);
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
  }

  @Test
  void postJsonDelegatesTextTurnAndReturnsPayload() throws Exception {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());
    UUID sessionId = UUID.randomUUID();
    String idempotencyKey = UUID.randomUUID().toString();
    when(service.submitTextTurn(eq(principal), eq(sessionId.toString()), any(), eq(idempotencyKey)))
        .thenReturn(sampleResponse());

    mockMvc
        .perform(
            post("/api/v1/practice/sessions/{session_id}/turns", sessionId)
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content("{\"text_content\":\"Could you repeat that?\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.turn_consumed").value(true))
        .andExpect(jsonPath("$.user_turn.text_content").value("Could you repeat that?"))
        .andExpect(jsonPath("$.coach_turn.text_content").value("Tell me more."))
        .andExpect(jsonPath("$.session_status").value("active"));

    verify(service)
        .submitTextTurn(eq(principal), eq(sessionId.toString()), any(), eq(idempotencyKey));
  }

  @Test
  void postMultipartDelegatesAudioTurn() throws Exception {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());
    UUID sessionId = UUID.randomUUID();
    String idempotencyKey = UUID.randomUUID().toString();
    MockMultipartFile audio =
        new MockMultipartFile("audio", "voice.webm", "audio/webm", new byte[] {1, 2, 3});
    when(service.submitAudioTurn(
            eq(principal), eq(sessionId.toString()), any(), eq(idempotencyKey)))
        .thenReturn(sampleResponse());

    mockMvc
        .perform(
            multipart("/api/v1/practice/sessions/{session_id}/turns", sessionId)
                .file(audio)
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal)
                .header("Idempotency-Key", idempotencyKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.turn_consumed").value(true));

    verify(service)
        .submitAudioTurn(eq(principal), eq(sessionId.toString()), any(), eq(idempotencyKey));
  }

  @Test
  void postMultipartWithTextOnlyDelegatesTextTurn() throws Exception {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());
    UUID sessionId = UUID.randomUUID();
    String idempotencyKey = UUID.randomUUID().toString();
    when(service.submitTextTurn(
            eq(principal),
            eq(sessionId.toString()),
            any(SubmitPracticeTurnRequest.class),
            eq(idempotencyKey)))
        .thenReturn(sampleResponse());

    mockMvc
        .perform(
            multipart("/api/v1/practice/sessions/{session_id}/turns", sessionId)
                .param("text_content", "Could you repeat that?")
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal)
                .header("Idempotency-Key", idempotencyKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.turn_consumed").value(true));

    verify(service)
        .submitTextTurn(
            eq(principal),
            eq(sessionId.toString()),
            any(SubmitPracticeTurnRequest.class),
            eq(idempotencyKey));
  }

  @Test
  void postMissingIdempotencyKeyReturns400FromServiceContract() throws Exception {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());
    UUID sessionId = UUID.randomUUID();
    when(service.submitTextTurn(eq(principal), eq(sessionId.toString()), any(), eq(null)))
        .thenThrow(
            new ApiErrorException(
                HttpStatus.BAD_REQUEST,
                "validation_failed",
                "Check the request and try again.",
                "Idempotency-Key header is required.",
                false));

    mockMvc
        .perform(
            post("/api/v1/practice/sessions/{session_id}/turns", sessionId)
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal)
                .contentType("application/json")
                .content("{\"text_content\":\"Hi\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error_code").value("validation_failed"));
  }

  @Test
  void postWithoutVerifiedPrincipalReturns401() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/practice/sessions/{session_id}/turns", UUID.randomUUID())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"text_content\":\"Hi\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error_code").value("internal_auth_invalid"));
  }

  private static SubmitPracticeTurnResponse sampleResponse() {
    PracticeTurnResponse userTurn =
        new PracticeTurnResponse(
            UUID.randomUUID(),
            2,
            "user",
            "Could you repeat that?",
            null,
            null,
            false,
            OffsetDateTime.parse("2026-06-26T00:00:00Z"));
    PracticeTurnResponse coachTurn =
        new PracticeTurnResponse(
            UUID.randomUUID(),
            3,
            "coach",
            "Tell me more.",
            null,
            null,
            false,
            OffsetDateTime.parse("2026-06-26T00:00:01Z"));
    return SubmitPracticeTurnResponse.consumed(userTurn, coachTurn, null, "active");
  }
}
