package com.phraselog.practice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Nullable feedback card returned by the Haiku roleplay_turn_feedback path. */
public record TurnFeedbackResponse(
    @JsonProperty("show_feedback") boolean showFeedback,
    @JsonProperty("natural_alternative") String naturalAlternative,
    @JsonProperty("korean_comment") String koreanComment) {

  public static TurnFeedbackResponse hidden() {
    return new TurnFeedbackResponse(false, null, null);
  }
}
