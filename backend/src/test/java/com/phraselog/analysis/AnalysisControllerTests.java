package com.phraselog.analysis;

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
import com.phraselog.auth.InternalAuthPrincipal;
import com.phraselog.common.web.GlobalExceptionHandler;
import com.phraselog.usage.ClientIpResolver;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AnalysisControllerTests {

  private AnalysisService analysisService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    analysisService = mock(AnalysisService.class);
    AnalysisController controller = new AnalysisController(analysisService);
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
  }

  @Test
  void postReturns201AndPassesPrincipalAndHeaders() throws Exception {
    InternalAuthPrincipal principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    String idempotencyKey = UUID.randomUUID().toString();
    when(analysisService.create(eq(principal), any(), eq(idempotencyKey), eq("203.0.113.10")))
        .thenReturn(sampleResponse());

    mockMvc
        .perform(
            post("/api/v1/analysis")
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal)
                .header("Idempotency-Key", idempotencyKey)
                .header(ClientIpResolver.HEADER, "203.0.113.10")
                .contentType("application/json")
                .content("{\"input_text\":\"상황\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.input_text").value("상황"))
        .andExpect(jsonPath("$.variants.length()").value(3))
        .andExpect(jsonPath("$.prompt_version").value("s07-v1"));

    verify(analysisService).create(eq(principal), any(), eq(idempotencyKey), eq("203.0.113.10"));
  }

  @Test
  void postWithoutVerifiedPrincipalReturns401() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/analysis")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"input_text\":\"상황\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error_code").value("internal_auth_invalid"));
  }

  @Test
  void getReturns200AndDelegatesPathId() throws Exception {
    InternalAuthPrincipal principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    AnalysisResponse response = sampleResponse();
    when(analysisService.get(eq(principal), eq(response.id().toString()))).thenReturn(response);

    mockMvc
        .perform(
            get("/api/v1/analysis/{id}", response.id().toString())
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(response.id().toString()));

    verify(analysisService).get(eq(principal), eq(response.id().toString()));
  }

  private static AnalysisResponse sampleResponse() {
    UUID id = UUID.randomUUID();
    List<ExpressionVariantDto> variants =
        List.of(
            variant(id, 1, "정중한", "A"), variant(id, 2, "부드러운", "B"), variant(id, 3, "단호한", "C"));
    return new AnalysisResponse(
        id, "상황", variants, "s07-v1", OffsetDateTime.parse("2026-06-19T12:00:00Z"));
  }

  private static ExpressionVariantDto variant(
      UUID analysisId, int order, String tone, String text) {
    UUID variantId =
        UUID.nameUUIDFromBytes((analysisId + ":" + order).getBytes(StandardCharsets.UTF_8));
    return new ExpressionVariantDto(
        variantId, order, tone, text, "/x/", "엑스", "tip", "culture", null);
  }
}
