package com.phraselog.practice.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/** Completed-session context used to generate or save an S12b roleplay result (#62). */
public record PracticeResultContext(
    UUID sessionId,
    UUID userId,
    UUID expressionId,
    UUID coachId,
    String status,
    int plannedTurns,
    JsonNode resultJson,
    String originalSituation,
    String selectedExpression,
    String coachName,
    String coachPersona,
    List<PracticeResultTurn> turns) {}
