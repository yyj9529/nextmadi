package com.phraselog.review.service;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.review.dto.ReviewCardState;
import com.phraselog.review.dto.ReviewTodayResponse;
import com.phraselog.review.dto.SubmitRatingRequest;
import com.phraselog.review.dto.SubmitRatingResponse;
import com.phraselog.review.repository.ReviewRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Backend core for the S10 review queue: due query, rating submission, queue toggle (#49). */
@Service
public class ReviewService {

  private static final int DEFAULT_LIMIT = 10;
  private static final int MAX_LIMIT = 50;

  private final ReviewRepository reviewRepository;

  public ReviewService(ReviewRepository reviewRepository) {
    this.reviewRepository = reviewRepository;
  }

  /** Due cards for today plus the stable total-due denominator. */
  @Transactional(readOnly = true)
  public ReviewTodayResponse today(
      InternalAuthPrincipal principal, Integer limit, String excludeIds) {
    UUID userId = requireAuthenticatedUser(principal);
    int pageSize = clampLimit(limit);
    List<UUID> excluded = parseExcludeIds(excludeIds);
    return new ReviewTodayResponse(
        reviewRepository.findDueCards(userId, excluded, pageSize),
        reviewRepository.countDue(userId));
  }

  /** Records one rating: appends history and reschedules the card with the ×1/×2/×3 model. */
  @Transactional
  public SubmitRatingResponse submit(
      InternalAuthPrincipal principal, UUID reviewCardId, SubmitRatingRequest body) {
    UUID userId = requireAuthenticatedUser(principal);
    String rating = requireRating(body);
    ReviewCardState card =
        reviewRepository
            .findCardForUser(reviewCardId, userId)
            .orElseThrow(ReviewService::reviewCardNotFound);

    int previousIntervalDays = card.currentIntervalDays();
    int nextIntervalDays = nextInterval(rating, previousIntervalDays);
    OffsetDateTime reviewedAt = OffsetDateTime.now();
    OffsetDateTime nextReviewAt = reviewedAt.plusDays(nextIntervalDays);

    reviewRepository.applySubmit(
        reviewCardId,
        userId,
        card.expressionId(),
        rating,
        previousIntervalDays,
        nextIntervalDays,
        reviewedAt,
        nextReviewAt);

    return new SubmitRatingResponse(
        reviewCardId, previousIntervalDays, nextIntervalDays, nextReviewAt);
  }

  /** Pauses a card from the queue (S09 action); 404 if missing or not owned. */
  @Transactional
  public void removeFromQueue(InternalAuthPrincipal principal, UUID reviewCardId) {
    UUID userId = requireAuthenticatedUser(principal);
    if (!reviewRepository.removeFromQueue(reviewCardId, userId)) {
      throw reviewCardNotFound();
    }
  }

  /** Re-adds a paused card with a fresh schedule; 404 if missing, 409 if already in queue. */
  @Transactional
  public void reAddToQueue(InternalAuthPrincipal principal, UUID reviewCardId) {
    UUID userId = requireAuthenticatedUser(principal);
    if (reviewRepository.findCardForUser(reviewCardId, userId).isEmpty()) {
      throw reviewCardNotFound();
    }
    if (!reviewRepository.reAddToQueue(reviewCardId, userId)) {
      throw new ApiErrorException(
          HttpStatus.CONFLICT,
          "already_in_queue",
          "This expression is already in the review queue.",
          "re-add-to-queue called while removed_from_queue_at IS NULL.",
          false);
    }
  }

  private static int nextInterval(String rating, int previousIntervalDays) {
    return switch (rating) {
      case "hard" -> 1;
      case "good" -> previousIntervalDays * 2;
      case "easy" -> previousIntervalDays * 3;
      default -> throw validationFailed("rating must be hard, good, or easy.");
    };
  }

  private static int clampLimit(Integer limit) {
    if (limit == null || limit < 1) {
      return DEFAULT_LIMIT;
    }
    return Math.min(limit, MAX_LIMIT);
  }

  private static List<UUID> parseExcludeIds(String excludeIds) {
    if (!StringUtils.hasText(excludeIds)) {
      return List.of();
    }
    List<UUID> parsed = new ArrayList<>();
    for (String token : excludeIds.split(",")) {
      String trimmed = token.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      try {
        parsed.add(UUID.fromString(trimmed));
      } catch (IllegalArgumentException ignored) {
        // Malformed ids are skipped so one bad token never fails the whole batch fetch.
      }
    }
    return parsed;
  }

  private static String requireRating(SubmitRatingRequest body) {
    if (body == null || !StringUtils.hasText(body.rating())) {
      throw validationFailed("rating is required.");
    }
    return body.rating().trim();
  }

  private static UUID requireAuthenticatedUser(InternalAuthPrincipal principal) {
    if (principal == null || !principal.isAuthenticatedUser()) {
      throw new ApiErrorException(
          HttpStatus.UNAUTHORIZED,
          "internal_auth_invalid",
          "Login required.",
          "A /review route requires an authenticated user_id principal.",
          false);
    }
    try {
      return UUID.fromString(principal.userId());
    } catch (IllegalArgumentException e) {
      throw validationFailed("user_id claim must be a UUID.");
    }
  }

  private static ApiErrorException validationFailed(String developerHint) {
    return new ApiErrorException(
        HttpStatus.BAD_REQUEST,
        "validation_failed",
        "Check the request and try again.",
        developerHint,
        false);
  }

  private static ApiErrorException reviewCardNotFound() {
    return new ApiErrorException(
        HttpStatus.NOT_FOUND,
        "not_found",
        "Review card was not found.",
        "Review card is missing or not owned by the caller.",
        false);
  }
}
