package com.phraselog.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.GlobalExceptionHandler;
import com.phraselog.user.dto.PatchMeRequest;
import com.phraselog.user.dto.UserResponse;
import com.phraselog.user.service.UserService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MeControllerTests {

  private UserService userService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    userService = mock(UserService.class);
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    mockMvc =
        MockMvcBuilders.standaloneSetup(new MeController(userService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
  }

  @Test
  void getMeReturnsUserShape() throws Exception {
    UUID userId = UUID.randomUUID();
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(userId.toString());
    when(userService.getMe(principal)).thenReturn(user(userId));

    mockMvc
        .perform(get("/api/v1/me").requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(userId.toString()))
        .andExpect(jsonPath("$.email").value("user@example.com"))
        .andExpect(jsonPath("$.display_name").value("우주"))
        .andExpect(jsonPath("$.is_onboarded").value(true))
        .andExpect(jsonPath("$.scheduled_deletion_at").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void getMeWithoutVerifiedPrincipalReturns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error_code").value("internal_auth_invalid"));
  }

  @Test
  void patchMePassesBodyToServiceAndReturnsUpdatedUser() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID coachId = UUID.randomUUID();
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(userId.toString());
    when(userService.updateMe(eq(principal), any(PatchMeRequest.class))).thenReturn(user(userId));

    mockMvc
        .perform(
            patch("/api/v1/me")
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal)
                .contentType("application/json")
                .content("{\"selected_coach_id\":\"" + coachId + "\",\"is_onboarded\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(userId.toString()));

    verify(userService).updateMe(eq(principal), any(PatchMeRequest.class));
  }

  @Test
  void patchMeWithoutVerifiedPrincipalReturns401() throws Exception {
    mockMvc
        .perform(patch("/api/v1/me").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error_code").value("internal_auth_invalid"));
  }

  private static UserResponse user(UUID userId) {
    return new UserResponse(
        userId,
        "user@example.com",
        "우주",
        null,
        true,
        OffsetDateTime.parse("2026-06-01T00:00:00Z"),
        null);
  }
}
