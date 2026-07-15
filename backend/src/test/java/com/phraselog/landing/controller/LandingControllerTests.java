package com.phraselog.landing.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.common.web.GlobalExceptionHandler;
import com.phraselog.landing.dto.LandingExampleResponse;
import com.phraselog.landing.service.LandingExampleService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LandingControllerTests {

  private LandingExampleService landingExampleService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    landingExampleService = mock(LandingExampleService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new LandingController(landingExampleService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
            .build();
  }

  @Test
  void examplesReturnsEnvelope() throws Exception {
    UUID id = UUID.randomUUID();
    when(landingExampleService.sample())
        .thenReturn(List.of(new LandingExampleResponse(id, "병원에서 증상을 정확하게 설명하고 싶어요.")));

    mockMvc
        .perform(get("/api/v1/landing/examples"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.examples.length()").value(1))
        .andExpect(jsonPath("$.examples[0].id").value(id.toString()))
        .andExpect(jsonPath("$.examples[0].korean_text").value("병원에서 증상을 정확하게 설명하고 싶어요."));
  }

  @Test
  void examplesReturnsEmptyArrayWhenPoolIsEmpty() throws Exception {
    when(landingExampleService.sample()).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/landing/examples"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.examples.length()").value(0));
  }
}
