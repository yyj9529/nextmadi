package com.phraselog.ai.client.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One Claude call's result: the schema-bound payload plus the provider's token accounting.
 *
 * <p>Why a record instead of a bare {@code JsonNode}: the Anthropic response carries two things at
 * different levels — the model output (inside {@code content[0].text}) and the usage metadata
 * (alongside it, in {@code usage}). The client used to return only the unwrapped payload, so {@code
 * usage} was discarded before {@link com.phraselog.ai.client.service.AnthropicService} looked for
 * it, and every {@code ai_request_logs} row from the real client stored null tokens and null cost.
 * The local mock returns no usage either, so unit tests and local runs could not surface it — only
 * a real keyed call did (#107).
 *
 * <p>Keeping the two apart makes the loss impossible to reintroduce silently: a client that forgets
 * usage now has to pass nulls explicitly.
 *
 * @param payload the parsed model output — this is what gets schema-validated and returned to
 *     callers
 * @param inputTokens prompt tokens billed, or null when the provider did not report them
 * @param outputTokens completion tokens billed, or null when the provider did not report them
 */
public record AnthropicResponse(JsonNode payload, Integer inputTokens, Integer outputTokens) {

  /** Result with no usage accounting — used by the local mock, which does not bill tokens. */
  public static AnthropicResponse withoutUsage(JsonNode payload) {
    return new AnthropicResponse(payload, null, null);
  }
}
