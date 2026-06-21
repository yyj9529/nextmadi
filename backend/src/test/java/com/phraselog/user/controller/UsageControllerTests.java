package com.phraselog.user.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.GlobalExceptionHandler;
import com.phraselog.user.dto.UsageTodayResponse;
import com.phraselog.user.service.UsageService;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UsageControllerTests {

  private UsageService usageService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    usageService = mock(UsageService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new UsageController(usageService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
            .build();
  }

  @Test
  void todayReturnsCountersWithNullAnalysisLimit() throws Exception {
    UUID userId = UUID.randomUUID();
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(userId.toString());
    when(usageService.getToday(principal)).thenReturn(new UsageTodayResponse(1, 2, 7, null));

    mockMvc
        .perform(
            get("/api/v1/usage/today")
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roleplay_session_count").value(1))
        .andExpect(jsonPath("$.daily_roleplay_limit").value(2))
        .andExpect(jsonPath("$.analysis_count").value(7))
        .andExpect(jsonPath("$.analysis_limit").value(Matchers.nullValue()));
  }

  @Test
  void todayWithoutVerifiedPrincipalReturns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/usage/today"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error_code").value("internal_auth_invalid"));
  }
}
