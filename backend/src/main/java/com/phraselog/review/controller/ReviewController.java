package com.phraselog.review.controller;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.common.web.ApiPaths;
import com.phraselog.review.dto.ReviewTodayResponse;
import com.phraselog.review.dto.SubmitRatingRequest;
import com.phraselog.review.dto.SubmitRatingResponse;
import com.phraselog.review.service.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal BFF-to-Spring endpoints for the S10 review queue (#49). */
@RestController
@RequestMapping(ApiPaths.V1 + "/review")
public class ReviewController {

  private final ReviewService reviewService;

  public ReviewController(ReviewService reviewService) {
    this.reviewService = reviewService;
  }

  @GetMapping("/today")
  public ResponseEntity<ReviewTodayResponse> today(
      HttpServletRequest request,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "exclude_ids", required = false) String excludeIds) {
    return ResponseEntity.ok(reviewService.today(principal(request), limit, excludeIds));
  }

  @PostMapping("/{reviewCardId}/submit")
  public ResponseEntity<SubmitRatingResponse> submit(
      HttpServletRequest request,
      @PathVariable UUID reviewCardId,
      @RequestBody(required = false) SubmitRatingRequest body) {
    return ResponseEntity.ok(reviewService.submit(principal(request), reviewCardId, body));
  }

  @PostMapping("/{reviewCardId}/remove-from-queue")
  public ResponseEntity<Void> removeFromQueue(
      HttpServletRequest request, @PathVariable UUID reviewCardId) {
    reviewService.removeFromQueue(principal(request), reviewCardId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{reviewCardId}/re-add-to-queue")
  public ResponseEntity<Void> reAddToQueue(
      HttpServletRequest request, @PathVariable UUID reviewCardId) {
    reviewService.reAddToQueue(principal(request), reviewCardId);
    return ResponseEntity.noContent().build();
  }

  private static InternalAuthPrincipal principal(HttpServletRequest request) {
    Object attribute = request.getAttribute(InternalAuthPrincipal.REQUEST_ATTRIBUTE);
    if (attribute instanceof InternalAuthPrincipal verified) {
      return verified;
    }
    throw new ApiErrorException(
        HttpStatus.UNAUTHORIZED,
        "internal_auth_invalid",
        "Login required.",
        "No verified InternalAuthPrincipal on a protected review route.",
        false);
  }
}
