package com.phraselog.coach.repository;

import com.phraselog.coach.dto.CoachResponse;
import java.util.List;

/** Fallback wired in the no-DB scaffold context; the coach catalog requires a DataSource. */
public final class UnavailableCoachRepository implements CoachRepository {

  @Override
  public List<CoachResponse> findAll() {
    throw new IllegalStateException("CoachRepository requires a DataSource; none is configured");
  }
}
