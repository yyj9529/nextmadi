package com.phraselog.user.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Fallback wired in the no-DB scaffold context; usage counters require a DataSource. */
public final class UnavailableUsageRepository implements UsageRepository {

  private static IllegalStateException unavailable() {
    return new IllegalStateException("UsageRepository requires a DataSource; none is configured");
  }

  @Override
  public int countRoleplaySessions(
      UUID userId, OffsetDateTime fromInclusive, OffsetDateTime toExclusive) {
    throw unavailable();
  }

  @Override
  public int countAnalysisRequests(
      UUID userId, OffsetDateTime fromInclusive, OffsetDateTime toExclusive) {
    throw unavailable();
  }
}
