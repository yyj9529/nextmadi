package com.phraselog.coach.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.coach.dto.CoachResponse;
import com.phraselog.coach.service.CoachService;
import com.phraselog.common.web.GlobalExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CoachControllerTests {

  private CoachService coachService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    coachService = mock(CoachService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new CoachController(coachService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
            .build();
  }

  @Test
  void listReturnsCoachesEnvelope() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID coachId = UUID.randomUUID();
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(userId.toString());
    when(coachService.list(principal))
        .thenReturn(List.of(new CoachResponse(coachId, "mia", "Mia", "친절한 코치.", "shimmer")));

    mockMvc
        .perform(
            get("/api/v1/coaches").requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coaches.length()").value(1))
        .andExpect(jsonPath("$.coaches[0].id").value(coachId.toString()))
        .andExpect(jsonPath("$.coaches[0].slug").value("mia"))
        .andExpect(jsonPath("$.coaches[0].display_name").value("Mia"))
        .andExpect(jsonPath("$.coaches[0].persona_summary").value("친절한 코치."))
        .andExpect(jsonPath("$.coaches[0].tts_voice_id").value("shimmer"));
  }

  @Test
  void listWithoutVerifiedPrincipalReturns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/coaches"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error_code").value("internal_auth_invalid"));
  }
}
