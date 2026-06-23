package com.phraselog.review.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.common.web.GlobalExceptionHandler;
import com.phraselog.expression.dto.ExpressionVariantResponse;
import com.phraselog.review.dto.ReviewCardResponse;
import com.phraselog.review.dto.ReviewTodayResponse;
import com.phraselog.review.dto.SubmitRatingResponse;
import com.phraselog.review.service.ReviewService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReviewControllerTests {

  private ReviewService reviewService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    reviewService = mock(ReviewService.class);
    ReviewController controller = new ReviewController(reviewService);
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
  }

  @Test
  void todayReturns200WithCardsAndTotalDueAndForwardsParams() throws Exception {
    InternalAuthPrincipal principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    ReviewCardResponse card =
        new ReviewCardResponse(
            UUID.randomUUID(),
            UUID.randomUUID(),
            OffsetDateTime.parse("2026-06-23T00:00:00Z"),
            null,
            null,
            1,
            "병원 예약 전화에서 말문이 막혔어요",
            new ExpressionVariantResponse(
                UUID.randomUUID(),
                1,
                "정중한",
                "I'd like to make an appointment.",
                "/a/",
                "아이드",
                "Keep it short.",
                "Use would like for polite requests.",
                null));
    when(reviewService.today(eq(principal), eq(10), eq("a,b")))
        .thenReturn(new ReviewTodayResponse(List.of(card), 7));

    mockMvc
        .perform(
            get("/api/v1/review/today")
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal)
                .param("limit", "10")
                .param("exclude_ids", "a,b"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cards.length()").value(1))
        .andExpect(jsonPath("$.cards[0].original_situation").value("병원 예약 전화에서 말문이 막혔어요"))
        .andExpect(
            jsonPath("$.cards[0].variant.english_text").value("I'd like to make an appointment."))
        .andExpect(jsonPath("$.total_due").value(7));

    verify(reviewService).today(eq(principal), eq(10), eq("a,b"));
  }

  @Test
  void todayWithoutVerifiedPrincipalReturns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/review/today"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error_code").value("internal_auth_invalid"));
  }

  @Test
  void submitReturns200WithComputedIntervals() throws Exception {
    InternalAuthPrincipal principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    UUID cardId = UUID.randomUUID();
    when(reviewService.submit(eq(principal), eq(cardId), any()))
        .thenReturn(
            new SubmitRatingResponse(cardId, 1, 2, OffsetDateTime.parse("2026-06-25T00:00:00Z")));

    mockMvc
        .perform(
            post("/api/v1/review/" + cardId + "/submit")
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal)
                .contentType("application/json")
                .content("{\"rating\":\"good\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.review_card_id").value(cardId.toString()))
        .andExpect(jsonPath("$.previous_interval_days").value(1))
        .andExpect(jsonPath("$.next_interval_days").value(2));

    verify(reviewService).submit(eq(principal), eq(cardId), any());
  }

  @Test
  void submitReturns404WhenServiceThrowsNotFound() throws Exception {
    InternalAuthPrincipal principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    UUID cardId = UUID.randomUUID();
    when(reviewService.submit(eq(principal), eq(cardId), any()))
        .thenThrow(
            new ApiErrorException(
                HttpStatus.NOT_FOUND, "not_found", "Review card was not found.", "hint", false));

    mockMvc
        .perform(
            post("/api/v1/review/" + cardId + "/submit")
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal)
                .contentType("application/json")
                .content("{\"rating\":\"good\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error_code").value("not_found"));
  }

  @Test
  void removeFromQueueReturns204() throws Exception {
    InternalAuthPrincipal principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    UUID cardId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/review/" + cardId + "/remove-from-queue")
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal))
        .andExpect(status().isNoContent());

    verify(reviewService).removeFromQueue(eq(principal), eq(cardId));
  }

  @Test
  void reAddToQueueReturns204() throws Exception {
    InternalAuthPrincipal principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    UUID cardId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/review/" + cardId + "/re-add-to-queue")
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal))
        .andExpect(status().isNoContent());

    verify(reviewService).reAddToQueue(eq(principal), eq(cardId));
  }

  @Test
  void reAddToQueueReturns409WhenAlreadyInQueue() throws Exception {
    InternalAuthPrincipal principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    UUID cardId = UUID.randomUUID();
    doThrow(
            new ApiErrorException(
                HttpStatus.CONFLICT,
                "already_in_queue",
                "This expression is already in the review queue.",
                "hint",
                false))
        .when(reviewService)
        .reAddToQueue(eq(principal), eq(cardId));

    mockMvc
        .perform(
            post("/api/v1/review/" + cardId + "/re-add-to-queue")
                .requestAttr(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error_code").value("already_in_queue"));
  }
}
