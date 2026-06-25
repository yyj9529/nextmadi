package com.phraselog.ai.client.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.OptionalDouble;

public record OpenAiTranscriptionResult(
    String text, OptionalDouble usageSeconds, OptionalDouble lowestSegmentAvgLogprob) {

  public static OpenAiTranscriptionResult fromJson(JsonNode response) {
    String text = response.hasNonNull("text") ? response.get("text").asText() : null;
    OptionalDouble usageSeconds = extractUsageSeconds(response);
    OptionalDouble lowestAvgLogprob = extractLowestSegmentAvgLogprob(response);
    return new OpenAiTranscriptionResult(text, usageSeconds, lowestAvgLogprob);
  }

  private static OptionalDouble extractUsageSeconds(JsonNode response) {
    JsonNode usage = response.get("usage");
    if (usage != null && usage.hasNonNull("seconds")) {
      return OptionalDouble.of(usage.get("seconds").asDouble());
    }
    if (response.hasNonNull("duration")) {
      return OptionalDouble.of(response.get("duration").asDouble());
    }
    return OptionalDouble.empty();
  }

  private static OptionalDouble extractLowestSegmentAvgLogprob(JsonNode response) {
    JsonNode segments = response.get("segments");
    if (segments == null || !segments.isArray()) {
      return OptionalDouble.empty();
    }

    Double lowest = null;
    for (JsonNode segment : segments) {
      if (segment.hasNonNull("avg_logprob")) {
        double avgLogprob = segment.get("avg_logprob").asDouble();
        lowest = lowest == null ? avgLogprob : Math.min(lowest, avgLogprob);
      }
    }
    return lowest == null ? OptionalDouble.empty() : OptionalDouble.of(lowest);
  }
}
