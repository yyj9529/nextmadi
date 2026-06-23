package com.phraselog.review.repository;

import com.phraselog.review.dto.ReviewCardResponse;
import com.phraselog.review.dto.ReviewCardState;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Fallback wired in the no-DB scaffold context; the review queue requires a DataSource. */
public final class UnavailableReviewRepository implements ReviewRepository {

  private static IllegalStateException unavailable() {
    return new IllegalStateException("ReviewRepository requires a DataSource; none is configured");
  }

  @Override
  public List<ReviewCardResponse> findDueCards(UUID userId, List<UUID> excludeIds, int limit) {
    throw unavailable();
  }

  @Override
  public int countDue(UUID userId) {
    throw unavailable();
  }

  @Override
  public Optional<ReviewCardState> findCardForUser(UUID reviewCardId, UUID userId) {
    throw unavailable();
  }

  @Override
  public void applySubmit(
      UUID reviewCardId,
      UUID userId,
      UUID expressionId,
      String rating,
      int previousIntervalDays,
      int nextIntervalDays,
      OffsetDateTime reviewedAt,
      OffsetDateTime nextReviewAt) {
    throw unavailable();
  }

  @Override
  public boolean removeFromQueue(UUID reviewCardId, UUID userId) {
    throw unavailable();
  }

  @Override
  public boolean reAddToQueue(UUID reviewCardId, UUID userId) {
    throw unavailable();
  }
}
