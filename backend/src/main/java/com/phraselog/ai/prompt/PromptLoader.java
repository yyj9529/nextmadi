package com.phraselog.ai.prompt;

/**
 * Loads a versioned prompt file ({@code prompts/{path}/v{N}.md}), parses its YAML front-matter, and
 * returns the metadata plus the model-facing body with the front-matter stripped.
 *
 * <p>This is the prompt source for the AI pipeline (issue #28, E04.3). The returned {@link
 * PromptDefinition#promptVersion()} is the label that downstream code (the Anthropic client, #26)
 * passes to {@code ai_request_logs.prompt_version} (#27).
 *
 * <p><strong>Bump-both rule.</strong> When a prompt's content changes, add a new {@code v{N+1}.md}
 * file AND bump its front-matter {@code prompt_version}. The loader treats filename version and
 * {@code prompt_version} as independent values and logs the front-matter label; it fails fast if
 * the two disagree so a stale label cannot silently mislabel cost rows.
 */
public interface PromptLoader {

  /**
   * Loads and parses the prompt identified by {@code ref}.
   *
   * @throws PromptNotFoundException if no file exists at {@code ref.resourcePath()}
   * @throws PromptParseException if the front-matter is malformed, a required field is missing, or
   *     the filename version disagrees with {@code prompt_version}
   */
  PromptDefinition load(PromptRef ref);

  /** Convenience overload for {@link #load(PromptRef)}. */
  default PromptDefinition load(String path, int version) {
    return load(new PromptRef(path, version));
  }
}
