package com.phraselog.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.review.dto.ReviewCardState;
import com.phraselog.review.dto.SubmitRatingRequest;
import com.phraselog.review.dto.SubmitRatingResponse;
import com.phraselog.review.repository.ReviewRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class ReviewServiceTests {

  private ReviewRepository reviewRepository;
  private ReviewService reviewService;
  private InternalAuthPrincipal principal;

  @BeforeEach
  void setUp() {
    reviewRepository = mock(ReviewRepository.class);
    reviewService = new ReviewService(reviewRepository);
    principal = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
  }

  @Test
  void hardRatingResetsIntervalToOne() {
    assertThat(submitWith("hard", 5).nextIntervalDays()).isEqualTo(1);
  }

  @Test
  void goodRatingDoublesPreviousIntervalWithBoundaryOneTimesTwo() {
    assertThat(submitWith("good", 1).nextIntervalDays()).isEqualTo(2);
    assertThat(submitWith("good", 6).nextIntervalDays()).isEqualTo(12);
  }

  @Test
  void easyRatingTriplesPreviousIntervalWithBoundaryOneTimesThree() {
    assertThat(submitWith("easy", 1).nextIntervalDays()).isEqualTo(3);
    assertThat(submitWith("easy", 4).nextIntervalDays()).isEqualTo(12);
  }

  @Test
  void submitPersistsPreviousAndNextIntervalToTheRepository() {
    UUID cardId = UUID.randomUUID();
    UUID expressionId = UUID.randomUUID();
    when(reviewRepository.findCardForUser(eq(cardId), any()))
        .thenReturn(Optional.of(new ReviewCardState(cardId, expressionId, 2)));

    reviewService.submit(principal, cardId, new SubmitRatingRequest("good"));

    ArgumentCaptor<Integer> previous = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> next = ArgumentCaptor.forClass(Integer.class);
    verify(reviewRepository)
        .applySubmit(
            eq(cardId),
            any(),
            eq(expressionId),
            eq("good"),
            previous.capture(),
            next.capture(),
            any(),
            any());
    assertThat(previous.getValue()).isEqualTo(2);
    assertThat(next.getValue()).isEqualTo(4);
  }

  @Test
  void invalidRatingIsRejectedWithoutTouchingTheRepository() {
    UUID cardId = UUID.randomUUID();
    when(reviewRepository.findCardForUser(eq(cardId), any()))
        .thenReturn(Optional.of(new ReviewCardState(cardId, UUID.randomUUID(), 1)));

    assertThatThrownBy(
            () -> reviewService.submit(principal, cardId, new SubmitRatingRequest("nope")))
        .isInstanceOf(ApiErrorException.class)
        .extracting(e -> ((ApiErrorException) e).status())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    verify(reviewRepository, never())
        .applySubmit(any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
  }

  @Test
  void submitOnMissingCardReturns404() {
    UUID cardId = UUID.randomUUID();
    when(reviewRepository.findCardForUser(eq(cardId), any())).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> reviewService.submit(principal, cardId, new SubmitRatingRequest("good")))
        .isInstanceOf(ApiErrorException.class)
        .extracting(e -> ((ApiErrorException) e).status())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void reAddOnMissingCardReturns404() {
    UUID cardId = UUID.randomUUID();
    when(reviewRepository.findCardForUser(eq(cardId), any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> reviewService.reAddToQueue(principal, cardId))
        .isInstanceOf(ApiErrorException.class)
        .extracting(e -> ((ApiErrorException) e).status())
        .isEqualTo(HttpStatus.NOT_FOUND);
    verify(reviewRepository, never()).reAddToQueue(any(), any());
  }

  @Test
  void reAddOnCardAlreadyInQueueReturns409() {
    UUID cardId = UUID.randomUUID();
    when(reviewRepository.findCardForUser(eq(cardId), any()))
        .thenReturn(Optional.of(new ReviewCardState(cardId, UUID.randomUUID(), 1)));
    when(reviewRepository.reAddToQueue(eq(cardId), any())).thenReturn(false);

    assertThatThrownBy(() -> reviewService.reAddToQueue(principal, cardId))
        .isInstanceOf(ApiErrorException.class)
        .extracting(e -> ((ApiErrorException) e).status())
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void removeOnMissingCardReturns404() {
    UUID cardId = UUID.randomUUID();
    when(reviewRepository.removeFromQueue(eq(cardId), any())).thenReturn(false);

    assertThatThrownBy(() -> reviewService.removeFromQueue(principal, cardId))
        .isInstanceOf(ApiErrorException.class)
        .extracting(e -> ((ApiErrorException) e).status())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void todayClampsLimitToMaxAndForwardsParsedExcludeIds() {
    UUID keep = UUID.randomUUID();
    when(reviewRepository.findDueCards(any(), any(), anyInt())).thenReturn(List.of());
    when(reviewRepository.countDue(any())).thenReturn(0);

    reviewService.today(principal, 999, keep + " , not-a-uuid , ");

    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<UUID>> excluded = ArgumentCaptor.forClass(List.class);
    verify(reviewRepository).findDueCards(any(), excluded.capture(), limit.capture());
    assertThat(limit.getValue()).isEqualTo(50);
    assertThat(excluded.getValue()).containsExactly(keep);
  }

  private SubmitRatingResponse submitWith(String rating, int previousInterval) {
    UUID cardId = UUID.randomUUID();
    when(reviewRepository.findCardForUser(eq(cardId), any()))
        .thenReturn(Optional.of(new ReviewCardState(cardId, UUID.randomUUID(), previousInterval)));
    return reviewService.submit(principal, cardId, new SubmitRatingRequest(rating));
  }
}
