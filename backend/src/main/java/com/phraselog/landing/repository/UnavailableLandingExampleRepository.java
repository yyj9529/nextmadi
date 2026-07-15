package com.phraselog.landing.repository;

import com.phraselog.landing.dto.LandingExampleResponse;
import java.util.List;

/** Fallback wired in the no-DB scaffold context; the landing pool requires a DataSource. */
public final class UnavailableLandingExampleRepository implements LandingExampleRepository {

  @Override
  public List<LandingExampleResponse> findRandomActive(int limit) {
    throw new IllegalStateException(
        "LandingExampleRepository requires a DataSource; none is configured");
  }
}
