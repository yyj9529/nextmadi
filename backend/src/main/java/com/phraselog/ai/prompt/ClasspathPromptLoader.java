package com.phraselog.ai.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads prompt files from the classpath, where they are bundled at build time (the backend {@code
 * processResources} step copies repo-root {@code prompts/**}). Front-matter is parsed with
 * SnakeYAML — already on the Spring Boot classpath, no new dependency.
 *
 * <p>No caching in this version (issue #28): each {@link #load(PromptRef)} reads and parses afresh.
 * Prompt files are small and static per deploy; a {@code (path, version)} cache can be added later
 * if a hot path needs it.
 *
 * <p>This component has no DataSource or provider dependency, so it does not perturb the no-DB
 * application-context test.
 */
@Component
public class ClasspathPromptLoader implements PromptLoader {

  /**
   * A line that is exactly {@code ---} (ignoring trailing whitespace) opens/closes front-matter.
   */
  private static final Pattern FENCE = Pattern.compile("^---\\s*$");

  /**
   * Trailing {@code v<digits>} of a {@code prompt_version} label, e.g. the {@code v1} in s07-v1.
   */
  private static final Pattern TRAILING_VERSION = Pattern.compile("v(\\d+)$");

  @Override
  public PromptDefinition load(PromptRef ref) {
    String raw = readResource(ref);
    String[] split = splitFrontMatter(ref, raw);
    String frontMatter = split[0];
    String body = stripLeadingBlankLines(split[1]);

    Map<String, Object> fm = parseFrontMatter(ref, frontMatter);

    String promptVersion = required(ref, fm, "prompt_version");
    assertVersionMatches(ref, promptVersion);

    return new PromptDefinition(
        required(ref, fm, "feature"),
        promptVersion,
        required(ref, fm, "model"),
        required(ref, fm, "output_schema"),
        asString(fm.get("created")),
        asString(fm.get("notes")),
        body);
  }

  private static String readResource(PromptRef ref) {
    Resource resource = new ClassPathResource(ref.resourcePath());
    if (!resource.exists()) {
      throw new PromptNotFoundException(ref);
    }
    try (InputStream in = resource.getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new PromptParseException(ref, "could not read resource: " + e.getMessage());
    }
  }

  /**
   * Splits a leading {@code ---}-fenced front-matter block from the body. Returns {@code
   * [frontMatter, body]}. The file must begin with a {@code ---} line and have a matching closing
   * {@code ---} line; otherwise this fails fast rather than sending a half-stripped body to a
   * model.
   */
  private static String[] splitFrontMatter(PromptRef ref, String raw) {
    String[] lines = raw.split("\n", -1);
    if (lines.length == 0 || !FENCE.matcher(stripCr(lines[0])).matches()) {
      throw new PromptParseException(ref, "file must begin with a '---' front-matter fence");
    }
    for (int i = 1; i < lines.length; i++) {
      if (FENCE.matcher(stripCr(lines[i])).matches()) {
        String frontMatter = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, i));
        String body = String.join("\n", java.util.Arrays.copyOfRange(lines, i + 1, lines.length));
        return new String[] {frontMatter, body};
      }
    }
    throw new PromptParseException(ref, "front-matter is missing its closing '---' fence");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseFrontMatter(PromptRef ref, String frontMatter) {
    Object parsed = new Yaml().load(frontMatter);
    if (!(parsed instanceof Map)) {
      throw new PromptParseException(ref, "front-matter is not a YAML mapping");
    }
    return (Map<String, Object>) parsed;
  }

  /**
   * Fail-fast bump-both guard (issue #28, decision D3): the filename version {@code N} must equal
   * the trailing {@code v{N}} of {@code prompt_version}. The {@code prompt_version} string itself
   * is used verbatim downstream; only its trailing digit is cross-checked here.
   */
  private static void assertVersionMatches(PromptRef ref, String promptVersion) {
    Matcher m = TRAILING_VERSION.matcher(promptVersion);
    if (!m.find()) {
      throw new PromptParseException(
          ref, "prompt_version '" + promptVersion + "' does not end in a 'v<N>' version");
    }
    int labelVersion = Integer.parseInt(m.group(1));
    if (labelVersion != ref.version()) {
      throw new PromptParseException(
          ref,
          "filename version v"
              + ref.version()
              + " disagrees with prompt_version '"
              + promptVersion
              + "' (bump both on a content change)");
    }
  }

  private static String required(PromptRef ref, Map<String, Object> fm, String key) {
    String value = asString(fm.get(key));
    if (value == null || value.isBlank()) {
      throw new PromptParseException(ref, "missing required front-matter field: " + key);
    }
    return value;
  }

  /**
   * Renders a front-matter scalar as a string. SnakeYAML resolves an unquoted {@code created:
   * 2026-06-04} to a {@link Date}; normalise that back to an ISO date so the label is stable. All
   * other scalars use their natural string form.
   */
  private static String asString(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Date date) {
      return LocalDate.ofInstant(date.toInstant(), ZoneOffset.UTC).toString();
    }
    return value.toString();
  }

  /** Drops leading blank lines so the body begins at its first real content line. */
  private static String stripLeadingBlankLines(String body) {
    return body.replaceFirst("^(?:[ \\t]*\\R)+", "");
  }

  private static String stripCr(String line) {
    return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
  }
}
