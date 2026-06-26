package com.phraselog.practice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Response body for POST /practice/sessions/{id}/turns. */
public record SubmitPracticeTurnResponse(
    @JsonProperty("turn_consumed") boolean turnConsumed,
    @JsonProperty("retry_prompt") String retryPrompt,
    @JsonProperty("user_turn") PracticeTurnResponse userTurn,
    @JsonProperty("coach_turn") PracticeTurnResponse coachTurn,
    TurnFeedbackResponse feedback,
    @JsonProperty("session_status") String sessionStatus) {

  public static SubmitPracticeTurnResponse consumed(
      PracticeTurnResponse userTurn,
      PracticeTurnResponse coachTurn,
      TurnFeedbackResponse feedback,
      String sessionStatus) {
    return new SubmitPracticeTurnResponse(true, null, userTurn, coachTurn, feedback, sessionStatus);
  }

  public static SubmitPracticeTurnResponse notConsumed(String retryPrompt) {
    return new SubmitPracticeTurnResponse(false, retryPrompt, null, null, null, "active");
  }
}
