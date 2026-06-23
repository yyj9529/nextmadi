package com.phraselog.home.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.coach.dto.CoachResponse;
import com.phraselog.common.web.GlobalExceptionHandler;
import com.phraselog.expression.dto.ExpressionListItem;
import com.phraselog.home.dto.DashboardResponse;
import com.phraselog.home.service.HomeService;
import com.phraselog.user.dto.UserResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HomeControllerTests {

  private HomeService homeService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    homeService = mock(HomeService.class);
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    mockMvc =
        MockMvcBuilders.standaloneSetup(new HomeController(homeService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
  }

  @Test
  void dashboardReturnsSnakeCaseEnvelope() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID coachId = UUID.randomUUID();
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(userId.toString());
    UserResponse user =
        new UserResponse(
            userId,
            "owner@example.com",
            "우주",
            coachId,
            true,
            OffsetDateTime.parse("2026-06-21T00:00:00Z"),
            null);
    DashboardResponse response =
        new DashboardResponse(
            user,
            new CoachResponse(coachId, "mia", "Mia", "친절한 코치.", "shimmer"),
            7,
            List.of(
                new ExpressionListItem(
                    UUID.randomUUID(),
                    "상황 A",
                    "Say this.",
                    "정중한",
                    OffsetDateTime.parse("2026-06-21T00:00:00Z"))),
            3,
            1,
            2);
    when(homeService.dashboard(principal)).thenReturn(response);

    mockMvc
        .perform(
            get("/api/v1/home/dashboard")
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.id").value(userId.toString()))
        .andExpect(jsonPath("$.user.selected_coach_id").value(coachId.toString()))
        .andExpect(jsonPath("$.coach.slug").value("mia"))
        .andExpect(jsonPath("$.bookshelf_count").value(7))
        .andExpect(jsonPath("$.recent_expressions.length()").value(1))
        .andExpect(jsonPath("$.recent_expressions[0].english_text").value("Say this."))
        .andExpect(jsonPath("$.due_review_count").value(3))
        .andExpect(jsonPath("$.today_roleplay_session_count").value(1))
        .andExpect(jsonPath("$.daily_roleplay_limit").value(2));
  }

  @Test
  void dashboardWithoutVerifiedPrincipalReturns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/home/dashboard"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error_code").value("internal_auth_invalid"));
  }
}
