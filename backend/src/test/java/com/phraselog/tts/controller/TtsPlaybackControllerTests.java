package com.phraselog.tts.controller;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.GlobalExceptionHandler;
import com.phraselog.tts.service.TtsPlaybackResult;
import com.phraselog.tts.service.TtsPlaybackService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TtsPlaybackControllerTests {

  private TtsPlaybackService service;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(TtsPlaybackService.class);
    TtsPlaybackController controller = new TtsPlaybackController(service);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
            .build();
  }

  @Test
  void returnsSnakeCaseBodyAndPassesUserAndVariant() throws Exception {
    String userId = UUID.randomUUID().toString();
    String variantId = UUID.randomUUID().toString();
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(userId);
    when(service.playback(
            eq(UUID.fromString(userId)),
            eq("Hello"),
            eq("shimmer"),
            eq(UUID.fromString(variantId)),
            any()))
        .thenReturn(new TtsPlaybackResult(UUID.randomUUID(), "https://signed/x", 1200, "miss"));

    mockMvc
        .perform(
            post("/api/v1/tts/playback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"text\":\"Hello\",\"voice_id\":\"shimmer\",\"expression_variant_id\":\""
                        + variantId
                        + "\"}")
                .requestAttr("phraselog.internalAuthPrincipal", principal))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.audio_url", equalTo("https://signed/x")))
        .andExpect(jsonPath("$.duration_ms", equalTo(1200)))
        .andExpect(jsonPath("$.cache_status", equalTo("miss")));

    verify(service)
        .playback(
            eq(UUID.fromString(userId)),
            eq("Hello"),
            eq("shimmer"),
            eq(UUID.fromString(variantId)),
            any());
  }

  @Test
  void anonymousSessionPrincipalPassesNullUserId() throws Exception {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofSession("anon-session");
    when(service.playback(isNull(), eq("Hi"), eq("nova"), isNull(), any()))
        .thenReturn(new TtsPlaybackResult(UUID.randomUUID(), "https://signed/y", null, "hit"));

    mockMvc
        .perform(
            post("/api/v1/tts/playback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Hi\",\"voice_id\":\"nova\"}")
                .requestAttr("phraselog.internalAuthPrincipal", principal))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cache_status", equalTo("hit")));

    verify(service).playback(isNull(), eq("Hi"), eq("nova"), isNull(), any());
  }

  @Test
  void missingPrincipalReturns401() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/tts/playback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Hi\",\"voice_id\":\"nova\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error_code", equalTo("internal_auth_invalid")));
  }

  @Test
  void invalidVariantIdReturns400() throws Exception {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());

    mockMvc
        .perform(
            post("/api/v1/tts/playback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"text\":\"Hi\",\"voice_id\":\"nova\",\"expression_variant_id\":\"not-a-uuid\"}")
                .requestAttr("phraselog.internalAuthPrincipal", principal))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error_code", equalTo("validation_failed")));
  }
}
