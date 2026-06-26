package com.phraselog.practice.dto;

import java.util.List;

/**
 * A session row plus its turns, ordered by {@code turn_number} ascending (#59). Used both for the
 * GET state-restoration response and for the POST response (where {@code turns} holds just the
 * opening coach turn).
 */
public record PracticeSessionWithTurns(PracticeSessionRow session, List<PracticeTurnRow> turns) {}
