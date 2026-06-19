package com.phraselog.ai.prompt.dto;

import com.phraselog.ai.prompt.service.ClasspathPromptLoader;

/**
 * A loaded prompt: the parsed front-matter metadata plus the model-facing {@code body} with the
 * front-matter block stripped.
 *
 * <p><strong>The body is what goes to the model; the front-matter never does.</strong> {@code body}
 * is the file content after the closing {@code ---} fence, with leading blank lines removed.
 *
 * <p><strong>{@code promptVersion} is the verbatim front-matter label</strong> (e.g. {@code
 * s07-v1}, {@code roleplay-init-v1}) — it is the value that flows to {@code
 * ai_request_logs.prompt_version} (issue #27). It is NOT reconstructed from the filename; the
 * loader returns it exactly as written so cost/version rows are labelled correctly.
 *
 * <p><strong>The bump-both rule.</strong> On any content change to a prompt, create a new {@code
 * v{N+1}.md} file AND set its front-matter {@code prompt_version} to the new label. The two are
 * independent values and the loader logs the front-matter label, so a stale {@code prompt_version}
 * would silently mislabel cost rows. The loader guards against exactly this by failing fast when
 * the filename version and the trailing version of {@code prompt_version} disagree (see {@link
 * ClasspathPromptLoader}).
 *
 * @param feature raw front-matter {@code feature} string (not mapped to any enum here; that is the
 *     caller's concern — e.g. the three coach personas all share {@code roleplay_coach_persona})
 * @param promptVersion verbatim front-matter {@code prompt_version} label, fed to logging
 * @param model front-matter {@code model} identifier
 * @param outputSchema front-matter {@code output_schema} identifier
 * @param created front-matter {@code created} value as written (ISO date), may be null
 * @param notes front-matter {@code notes}, may be null
 * @param body model-facing prompt text, front-matter stripped
 */
public record PromptDefinition(
    String feature,
    String promptVersion,
    String model,
    String outputSchema,
    String created,
    String notes,
    String body) {

  public PromptDefinition {
    if (feature == null || feature.isBlank()) {
      throw new IllegalArgumentException("feature is required");
    }
    if (promptVersion == null || promptVersion.isBlank()) {
      throw new IllegalArgumentException("promptVersion is required");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model is required");
    }
    if (outputSchema == null || outputSchema.isBlank()) {
      throw new IllegalArgumentException("outputSchema is required");
    }
    if (body == null || body.isBlank()) {
      throw new IllegalArgumentException("body is required");
    }
  }
}
