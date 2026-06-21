package com.phraselog.user.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Persistence boundary for {@code GET /usage/today} daily counters (#52). */
public interface UsageRepository {

  /**
   * Non-abandoned roleplay sessions started in {@code [fromInclusive, toExclusive)} for the user.
   * Matches the daily-limit query in data-model.md so the S11 display and the limit agree.
   */
  int countRoleplaySessions(UUID userId, OffsetDateTime fromInclusive, OffsetDateTime toExclusive);

  /** Analysis requests created in {@code [fromInclusive, toExclusive)} for the user. */
  int countAnalysisRequests(UUID userId, OffsetDateTime fromInclusive, OffsetDateTime toExclusive);
}
