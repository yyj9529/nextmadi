package com.phraselog.coach.repository;

import com.phraselog.coach.dto.CoachResponse;
import java.util.List;

/** Persistence boundary for the seed coach catalog (#52). */
public interface CoachRepository {

  /** All coach profiles, ordered deterministically by slug for stable card rendering. */
  List<CoachResponse> findAll();
}
