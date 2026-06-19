package com.phraselog.ai.prompt.service;

import com.phraselog.ai.prompt.dto.PromptRef;

/**
 * Thrown when no prompt file exists for a requested {@link PromptRef} — i.e. the classpath resource
 * {@code prompts/{path}/v{N}.md} is absent. Satisfies the issue #28 "clear error on a non-existent
 * version" criterion: the message names the resolved resource path.
 */
public class PromptNotFoundException extends RuntimeException {

  public PromptNotFoundException(PromptRef ref) {
    super("No prompt at " + ref);
  }
}
