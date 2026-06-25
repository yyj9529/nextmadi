package com.phraselog.transcription.controller;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.GlobalExceptionHandler;
import com.phraselog.transcription.WebmTestFixtures;
import com.phraselog.transcription.dto.TranscriptionResponse;
import com.phraselog.transcription.service.TranscriptionService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TranscriptionControllerTests {

  private TranscriptionService service;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(TranscriptionService.class);
    TranscriptionController controller = new TranscriptionController(service);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
            .build();
  }

  @Test
  void postReturnsTranscriptAndPassesPrincipalAndAudio() throws Exception {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());
    when(service.transcribe(eq(principal), any()))
        .thenReturn(new TranscriptionResponse("Could you repeat that?", new BigDecimal("0.732")));

    MockMultipartFile audio =
        new MockMultipartFile(
            "audio", "voice.webm", "audio/webm;codecs=opus", WebmTestFixtures.webmOpus(2.0));

    mockMvc
        .perform(
            multipart("/api/v1/transcriptions")
                .file(audio)
                .requestAttr("phraselog.internalAuthPrincipal", principal))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transcript", equalTo("Could you repeat that?")))
        .andExpect(jsonPath("$.stt_confidence", equalTo(0.732)));

    verify(service).transcribe(eq(principal), any());
  }

  @Test
  void postWithoutVerifiedPrincipalReturns401() throws Exception {
    MockMultipartFile audio =
        new MockMultipartFile(
            "audio", "voice.webm", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[] {1});

    mockMvc
        .perform(multipart("/api/v1/transcriptions").file(audio))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error_code", equalTo("internal_auth_invalid")));
  }

  @Test
  void validationErrorUsesSharedErrorContract() throws Exception {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofSession("anon-session");
    when(service.transcribe(eq(principal), any()))
        .thenThrow(
            TranscriptionService.validationFailed(
                "audio must be WebM/Opus and at most 60 seconds."));

    MockMultipartFile audio =
        new MockMultipartFile("audio", "voice.txt", MediaType.TEXT_PLAIN_VALUE, "nope".getBytes());

    mockMvc
        .perform(
            multipart("/api/v1/transcriptions")
                .file(audio)
                .requestAttr("phraselog.internalAuthPrincipal", principal))
        .andExpect(status().is(HttpStatus.BAD_REQUEST.value()))
        .andExpect(jsonPath("$.error_code", equalTo("validation_failed")))
        .andExpect(
            jsonPath(
                "$.developer_hint", equalTo("audio must be WebM/Opus and at most 60 seconds.")))
        .andExpect(jsonPath("$.retryable", equalTo(false)))
        .andExpect(jsonPath("$.request_correlation_id").exists());
  }
}
