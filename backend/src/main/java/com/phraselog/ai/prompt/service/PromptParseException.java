package com.phraselog.ai.prompt.service;

import com.phraselog.ai.prompt.dto.PromptRef;

/**
 * Thrown when a prompt file exists but is malformed: missing or unbalanced {@code ---} front-matter
 * fences, a required front-matter field absent, or the filename version disagreeing with the
 * trailing version of {@code prompt_version} (the bump-both fail-fast guard).
 *
 * <p>Failing loudly here is deliberate: a half-stripped body or a mislabelled {@code
 * prompt_version} must never reach a model call or {@code ai_request_logs}.
 */
public class PromptParseException extends RuntimeException {

  public PromptParseException(PromptRef ref, String reason) {
    super("Malformed prompt at " + ref + ": " + reason);
  }
}
