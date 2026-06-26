package com.phraselog.practice.repository;

import com.phraselog.practice.dto.PracticeTurnResponse;
import java.util.List;
import java.util.UUID;

/** Context needed to run one S12 turn for an active session owned by the caller. */
public record PracticeSessionContext(
    UUID sessionId,
    UUID userId,
    int plannedTurns,
    int consumedUserTurns,
    String status,
    String originalSituation,
    String selectedExpression,
    String coachName,
    String coachPersona,
    String ttsVoiceId,
    List<PracticeTurnResponse> turns) {}
