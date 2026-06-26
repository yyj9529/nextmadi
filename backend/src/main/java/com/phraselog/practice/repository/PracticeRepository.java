package com.phraselog.practice.repository;

import com.phraselog.practice.dto.NewPracticeSession;
import com.phraselog.practice.dto.PracticeSessionWithTurns;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for {@code practice_sessions} / {@code practice_turns} (#59). */
public interface PracticeRepository {

  /**
   * Inserts a session (status {@code active}) and its opening coach turn ({@code turn_number = 1})
   * in one transaction, returning the persisted session plus that turn.
   *
   * @throws org.springframework.dao.DuplicateKeyException if the {@code (user_id, idempotency_key)}
   *     unique index is violated — a concurrent retry won the race; the caller should re-read via
   *     {@link #findByUserAndKey}.
   */
  PracticeSessionWithTurns insertSessionWithOpeningTurn(NewPracticeSession session);

  /** Finds an existing session for an idempotent retry from the same user, with its turns. */
  Optional<PracticeSessionWithTurns> findByUserAndKey(UUID userId, UUID idempotencyKey);

  /**
   * Full session state owned by the user, with all turns ordered by {@code turn_number} (GET state
   * restoration). Empty when missing or not owned — the caller maps both to 404.
   */
  Optional<PracticeSessionWithTurns> findByIdForOwner(UUID sessionId, UUID userId);
}
