package com.phraselog.ai.prompt.dto;

import com.phraselog.ai.prompt.service.ClasspathPromptLoader;

/**
 * Identifies a versioned prompt file by its directory path key and integer version.
 *
 * <p>The {@code path} is the directory segment under {@code prompts/} — for example {@code "s07"}
 * or the two-level {@code "roleplay/init"} — and is the addressing key. It is deliberately NOT the
 * front-matter {@code feature} value, which is a different string (e.g. {@code
 * roleplay_session_init}) and is returned as data rather than used to locate the file.
 *
 * <p>{@code version} is the filename version digit {@code N} in {@code v{N}.md}. It is also
 * cross-checked against the trailing {@code v{N}} of the front-matter {@code prompt_version} at
 * load time (see {@link ClasspathPromptLoader}); the two must agree.
 *
 * @param path directory key under {@code prompts/}, e.g. {@code "s07"} or {@code "roleplay/init"}
 * @param version filename version, the {@code N} in {@code v{N}.md}; must be {@code >= 1}
 */
public record PromptRef(String path, int version) {

  public PromptRef {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("path is required");
    }
    if (path.startsWith("/") || path.endsWith("/")) {
      throw new IllegalArgumentException("path must not start or end with '/': " + path);
    }
    if (version < 1) {
      throw new IllegalArgumentException("version must be >= 1, was " + version);
    }
  }

  /**
   * The classpath resource location for this prompt, e.g. {@code prompts/s07/v1.md}. Prompt files
   * are bundled onto the classpath at build time (see the backend {@code processResources} step),
   * so this is resolved via {@code ClassPathResource}, not the filesystem.
   */
  public String resourcePath() {
    return "prompts/" + path + "/v" + version + ".md";
  }

  @Override
  public String toString() {
    return resourcePath() + " (path=" + path + ", version=" + version + ")";
  }
}
