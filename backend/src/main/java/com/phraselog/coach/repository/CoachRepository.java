package com.phraselog.coach.repository;

import com.phraselog.coach.dto.CoachResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for the seed coach catalog (#52). */
public interface CoachRepository {

  /** All coach profiles, ordered deterministically by slug for stable card rendering. */
  List<CoachResponse> findAll();

  /** Single coach by id (S04 home greeting, #54); empty when the id is unknown. */
  Optional<CoachResponse> findById(UUID coachId);
}
