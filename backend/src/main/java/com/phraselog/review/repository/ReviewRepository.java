package com.phraselog.review.repository;

import com.phraselog.review.dto.ReviewCardResponse;
import com.phraselog.review.dto.ReviewCardState;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence contract for the S10 review queue (#49). */
public interface ReviewRepository {

  /**
   * Due cards for the user, newest-due first, capped at {@code limit}. Due = {@code next_review_at
   * <= now()} AND not removed from queue AND parent expression not soft-deleted. {@code excludeIds}
   * drops cards already loaded in the current session.
   */
  List<ReviewCardResponse> findDueCards(UUID userId, List<UUID> excludeIds, int limit);

  /** Total due cards for the user, ignoring {@code excludeIds} — the stable session denominator. */
  int countDue(UUID userId);

  /** The card scoped to its owner, regardless of queue state; empty when missing or not owned. */
  Optional<ReviewCardState> findCardForUser(UUID reviewCardId, UUID userId);

  /**
   * Appends one {@code review_attempts} row and updates the {@code review_cards} latest state in a
   * single transaction.
   */
  void applySubmit(
      UUID reviewCardId,
      UUID userId,
      UUID expressionId,
      String rating,
      int previousIntervalDays,
      int nextIntervalDays,
      OffsetDateTime reviewedAt,
      OffsetDateTime nextReviewAt);

  /**
   * Sets {@code removed_from_queue_at = now()}; returns {@code false} when missing or not owned.
   */
  boolean removeFromQueue(UUID reviewCardId, UUID userId);

  /**
   * Clears {@code removed_from_queue_at} and resets the card to a brand-new schedule ({@code
   * next_review_at = now()}, {@code current_interval_days = 1}). Returns {@code false} when the
   * card is already in the queue (caller maps that to 409).
   */
  boolean reAddToQueue(UUID reviewCardId, UUID userId);
}
