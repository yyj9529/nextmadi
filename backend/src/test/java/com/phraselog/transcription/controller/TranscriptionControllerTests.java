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
import com.phraselog.usage.service.AnonymousTranscriptionUsageService;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TranscriptionControllerTests {

  private TranscriptionService service;
  private MockMvc mockMvc;

  /**
   * 한도는 이 테스트의 관심사가 아니다(그건 {@code AnonymousTranscriptionUsageServiceTests}). 여기서는 작업을 그대로 통과시키는
   * provider 를 끼워 컨트롤러 배선만 본다.
   */
  private static ObjectProvider<AnonymousTranscriptionUsageService> passThroughUsage() {
    AnonymousTranscriptionUsageService usage = mock(AnonymousTranscriptionUsageService.class);
    when(usage.withAnonymousTranscriptionLimit(any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2, Supplier.class).get());

    @SuppressWarnings("unchecked")
    ObjectProvider<AnonymousTranscriptionUsageService> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(usage);
    return provider;
  }

  @BeforeEach
  void setUp() {
    service = mock(TranscriptionService.class);
    TranscriptionController controller = new TranscriptionController(service, passThroughUsage());
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

  // 익명 하루 한도를 다 쓰면 전사 서비스를 아예 호출하지 않아야 한다 — 호출하면 Whisper 과금이
  // 발생해 한도의 목적이 무너진다. X-Client-IP 는 한도를 거는 키이므로 함께 확인한다.
  @Test
  void exhaustedAnonymousBudgetReturns429AndNeverCallsTheProvider() throws Exception {
    AnonymousTranscriptionUsageService usage = mock(AnonymousTranscriptionUsageService.class);
    when(usage.withAnonymousTranscriptionLimit(any(), eq("203.0.113.7"), any()))
        .thenThrow(
            new com.phraselog.common.web.ApiErrorException(
                HttpStatus.TOO_MANY_REQUESTS,
                "rate_limit_exceeded",
                "오늘 사용할 수 있는 음성 입력 횟수를 모두 썼어요. 텍스트로 입력해보세요.",
                "Check anonymous_transcription_usage for this client IP and date.",
                true));

    @SuppressWarnings("unchecked")
    ObjectProvider<AnonymousTranscriptionUsageService> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(usage);

    MockMvc limited =
        MockMvcBuilders.standaloneSetup(new TranscriptionController(service, provider))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
            .build();

    MockMultipartFile audio =
        new MockMultipartFile(
            "audio", "voice.webm", "audio/webm;codecs=opus", WebmTestFixtures.webmOpus(2.0));

    limited
        .perform(
            multipart("/api/v1/transcriptions")
                .file(audio)
                .header("X-Client-IP", "203.0.113.7")
                .requestAttr(
                    "phraselog.internalAuthPrincipal", InternalAuthPrincipal.ofSession("anon")))
        .andExpect(status().is(HttpStatus.TOO_MANY_REQUESTS.value()))
        .andExpect(jsonPath("$.error_code", equalTo("rate_limit_exceeded")));

    verify(service, org.mockito.Mockito.never()).transcribe(any(), any());
  }
}
