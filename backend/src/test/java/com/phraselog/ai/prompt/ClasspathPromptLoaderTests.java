package com.phraselog.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure-JVM unit tests for the prompt loader: no Spring context, no DataSource, no provider call, so
 * they run locally and in CI without infrastructure. The real prompt files are on the test
 * classpath because the backend {@code processResources} step bundles repo-root {@code prompts/**};
 * the malformed/mismatch fixtures live in {@code src/test/resources/prompts/test/}.
 */
class ClasspathPromptLoaderTests {

  private final PromptLoader loader = new ClasspathPromptLoader();

  @Test
  void loadsS07AndStripsFrontMatter() {
    PromptDefinition def = loader.load("s07", 1);

    // Front-matter is exposed as metadata...
    assertThat(def.feature()).isEqualTo("s07_analysis");
    assertThat(def.promptVersion()).isEqualTo("s07-v1");
    assertThat(def.model()).isEqualTo("claude-sonnet-4-6");
    assertThat(def.outputSchema()).isEqualTo("s07_analysis_v1");
    assertThat(def.created()).isEqualTo("2026-06-04");
    assertThat(def.notes()).contains("v1 starting point");

    // ...and the body is the model-facing text with the front-matter fully stripped.
    assertThat(def.body()).startsWith("You are the analysis engine for PhraseLog");
    assertThat(def.body()).doesNotContain("prompt_version");
    assertThat(def.body()).doesNotContain("output_schema");
    assertThat(def.body()).doesNotStartWith("---");
  }

  @Test
  void addressesByDirectoryPathNotByFeature() {
    // Two-level path key; the front-matter feature is a different string than the path.
    PromptDefinition def = loader.load("roleplay/init", 1);

    assertThat(def.feature()).isEqualTo("roleplay_session_init");
    assertThat(def.promptVersion()).isEqualTo("roleplay-init-v1");
    assertThat(def.body()).isNotBlank();
  }

  @Test
  void throwsClearErrorForMissingVersion() {
    assertThatThrownBy(() -> loader.load("s07", 9))
        .isInstanceOf(PromptNotFoundException.class)
        .hasMessageContaining("prompts/s07/v9.md");
  }

  @Test
  void failsFastWhenClosingFenceMissing() {
    assertThatThrownBy(() -> loader.load("test/malformed", 1))
        .isInstanceOf(PromptParseException.class)
        .hasMessageContaining("closing '---' fence");
  }

  @Test
  void failsFastWhenFilenameVersionDisagreesWithPromptVersion() {
    // File is v2.md but its prompt_version ends in v1 — D3 bump-both guard must reject it
    // and must NOT fall back to a warning or a passing load.
    assertThatThrownBy(() -> loader.load("test/mismatch", 2))
        .isInstanceOf(PromptParseException.class)
        .hasMessageContaining("disagrees with prompt_version");
  }
}
