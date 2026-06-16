# Exec plan: prompt loader (front-matter parsing + version management)

## Goal

Implement issue #28 (E04.3): a backend module that loads a versioned prompt file
from `prompts/{path}/v{N}.md`, parses its YAML front-matter, strips the
front-matter before the body is sent to a model, and exposes the
`prompt_version` label so the AI pipeline can pass it to #27 logging
(`ai_request_logs.prompt_version`).

Scope is the loader module only. No live provider calls, no Anthropic client, no
new endpoints. This is the direct prerequisite for #26 (Anthropic client), which
will call the loader to obtain the system prompt and the version label.

## Source specs

- GitHub issue #28: path convention, front-matter fields, acceptance criteria
  (load `prompts/s07/v1.md` + strip front-matter; clear error on missing
  version; pass `prompt_version` to logging; document the bump-both rule).
- `docs/AI_PIPELINE.md`: prompt versioning rule (line 67, "versioned prompt file
  under `prompts/{feature}/v{N}.md`"), routing table (lines 98–102) listing the
  real per-feature paths, and line 228 ("`prompt_version` — from the prompt file
  front-matter").
- Existing prompt files: `prompts/s07/v1.md` and `prompts/roleplay/**/v1.md` (8
  files), already written with consistent front-matter.
- #27 logging module: `AiRequestLogEntry.promptVersion` (String) is the field the
  loader's output feeds.

## Key facts established before coding

These were verified against the repo, not assumed. Two of them contradict the
issue's loose wording and must drive the design.

- **`prompt_version` is NOT the filename version.** The filename version is just
  `v1`; the front-matter `prompt_version` is a fully-qualified label:
  `s07-v1`, `roleplay-init-v1`, `roleplay-mia-v1`, etc. The loader must return the
  front-matter `prompt_version` verbatim (that is what flows to logging), and must
  NOT reconstruct it from the filename. A naive "promptVersion == vN" assumption
  would write the wrong label to `ai_request_logs`.
- **The addressing key is the directory path, not the `feature:` value.** Issue
  text says `prompts/{feature}/v{N}.md`, but the real layout keys on a path
  segment: `s07`, and a two-level `roleplay/init`, `roleplay/turn`, etc. The
  front-matter `feature` is a different string (`roleplay_session_init`). The
  loader addresses by relative path + version; `feature` is returned as data, not
  used to locate the file.
- **`feature: roleplay_coach_persona` is not 1:1 with `AiFeature`.** The three
  coach files (mia/david/sarah) share `feature: roleplay_coach_persona`, which is
  not a value in the #27 `AiFeature` enum. So the loader must NOT try to map
  `feature` to `AiFeature` — that mapping is the caller's concern. Loader returns
  the raw front-matter string.
- **`prompts/` lives at the repo root, not under `backend/`.** A deployed Spring
  Boot fat jar cannot read a sibling `../prompts` directory by relative path
  reliably. The files must be on the runtime classpath. See decision D1.
- **No YAML parser is declared in `backend/build.gradle`**, but SnakeYAML is
  already on the classpath transitively (Spring Boot uses it for
  `application.yml`). See decision D2.
- `PhraselogBackendApplicationTests` loads the context with
  `DataSourceAutoConfiguration` excluded. The loader must not require a DataSource,
  so this stays green.

## Decisions (confirmed by owner 2026-06-16)

- **D1 — `prompts/` reaches the runtime classpath via build-time bundling.** A
  Gradle `processResources`/`copy` step copies repo-root `prompts/**` into the
  backend build resources (`build/resources/main/prompts/`), so they ship inside
  the jar and load via `ClassPathResource`. Single source of truth stays at repo
  root; backend is the consumer. **Done criterion includes
  `jar tf build/libs/*.jar | grep prompts/`** to prove the packaged jar contains
  the prompt files.
- **D2 — YAML parser: use the one already on the Spring Boot classpath
  (SnakeYAML).** Zero new dependency. Front-matter is plain key/value plus a
  `notes: >` folded block, which SnakeYAML parses directly into a `Map`. If for
  any reason a new dependency turns out to be required, **stop and ask the owner
  first** — do not add it silently.
- **D3 — filename/version-label consistency is fail-fast, not a warning.** The
  loader extracts the trailing `v{N}` from `prompt_version` (verified consistent
  across all 8 files: `s07-v1`, `roleplay-init-v1`, …) and compares it to the
  filename version `{N}`. On mismatch (e.g. file `v2.md` but
  `prompt_version: s07-v1`) the loader throws — both in tests and at runtime; it
  must NOT silently pass. **The `prompt_version` string itself is always the
  verbatim front-matter value** (never reconstructed from the filename); only the
  trailing version digit is cross-checked.
- **Caching — omitted for v1.** Load and parse on each call. Prefer the simplest,
  most testable implementation first; add `(path, version)` caching later if a
  hot path needs it.

## Design

New package `com.phraselog.ai.prompt` (parallel to `ai.logging`).

- `PromptRef` — small value type identifying a prompt: relative path key (e.g.
  `s07`, `roleplay/init`) + integer version. Resolves to `prompts/{path}/v{N}.md`.
- `PromptDefinition` — immutable record returned by the loader:
  - `feature` (String, raw front-matter), `promptVersion` (String — the label that
    goes to logging), `model` (String), `outputSchema` (String), `created`
    (String/LocalDate), `notes` (String, nullable), and `body` (String — the
    file content with the front-matter block stripped, leading blank line trimmed).
- `PromptLoader` — interface: `PromptDefinition load(PromptRef ref)` (and a
  convenience `load(String path, int version)`).
- `ClasspathPromptLoader` (`@Component`) — reads the classpath resource, splits the
  leading `---`-delimited front-matter from the body, parses front-matter (D2),
  validates required fields are present, and returns `PromptDefinition`. Caches per
  D3.
- `PromptNotFoundException` — thrown with a clear message
  (`"No prompt at prompts/s07/v9.md (path=s07, version=9)"`) when the resource is
  absent. This satisfies the "clear error on non-existent version" criterion.
- `PromptParseException` (or reuse a malformed-front-matter case) — thrown when the
  `---` fences are missing/unbalanced or a required field is absent, so a broken
  prompt fails loudly at load rather than sending a half-stripped body to a model.

**Front-matter / body split rule:** file must begin with a line that is exactly
`---`; the block runs to the next line that is exactly `---`; everything after is
the body. This matches all 9 existing files. The body is sent to the model; the
front-matter is never sent.

**The bump-both rule (issue requirement):** documented as a class-level Javadoc on
`PromptLoader` and a one-line note near `PromptDefinition.promptVersion`: *on any
content change, create a new `v{N+1}.md` file AND set `prompt_version` to the new
label; the loader treats them as independent and logs the front-matter label, so a
stale `prompt_version` would silently mislabel cost rows.* Per D3 (confirmed) the
loader **fails fast**: if the filename version digit and the trailing version in
`prompt_version` disagree (e.g. file `v2.md` but `prompt_version: s07-v1`) it throws
`PromptParseException` rather than emitting a warning, so the mismatch cannot reach
`ai_request_logs`. The `prompt_version` string returned is still the verbatim
front-matter value.

## Files expected to change

- `backend/build.gradle` — D1 resource-bundling step; D2 dependency only if Jackson
  YAML is chosen.
- `backend/src/main/java/com/phraselog/ai/prompt/PromptRef.java`
- `backend/src/main/java/com/phraselog/ai/prompt/PromptDefinition.java`
- `backend/src/main/java/com/phraselog/ai/prompt/PromptLoader.java`
- `backend/src/main/java/com/phraselog/ai/prompt/ClasspathPromptLoader.java`
- `backend/src/main/java/com/phraselog/ai/prompt/PromptNotFoundException.java`
  (+ parse exception)
- `backend/src/test/java/com/phraselog/ai/prompt/ClasspathPromptLoaderTests.java`

## Tests

Pure-JVM unit tests (no DB, no provider), so they run locally and in CI:

- Load `prompts/s07/v1.md`: asserts `promptVersion == "s07-v1"`,
  `model == "claude-sonnet-4-6"`, `outputSchema == "s07_analysis_v1"`, and that the
  body starts with the real first content line and contains **no** `---` fence /
  `feature:` line (front-matter fully stripped).
- Load a roleplay two-level path (`roleplay/init` v1): confirms path-keyed
  addressing works and `feature == "roleplay_session_init"` (path != feature).
- Missing version (`s07` v9): expects `PromptNotFoundException` with a message
  naming the resolved path.
- Malformed front-matter fixture (missing closing `---`): expects the parse
  exception, not a silently un-stripped body.
- Filename/version-label mismatch fixture (file `v2.md` whose `prompt_version`
  ends in `-v1`): expects `PromptParseException` (D3 fail-fast), and confirms the
  loader does NOT fall back to a warning or a passing load.

Acceptance-criteria mapping: AC1 (load s07/v1 + strip) → test 1; AC2 (clear error
on missing version) → test 3; logging integration (`prompt_version` exposed) →
`PromptDefinition.promptVersion` field, consumed later by #26.

## Out of scope / hand-off

- The Anthropic client (#26) consumes `PromptDefinition`; it is not built here.
- Mapping `feature` → `AiFeature` and choosing the model at call time belong to #26.
- No live model call and no eval run — this ticket has no API cost.

## Verification commands

- Required context: `git status --short --branch` (start on a fresh branch off
  `main`, not on `feat/ai-request-logs`).
- Post-change: `cd backend && ./gradlew test` (the new loader tests; full suite if
  the build file changed).
- Pre-PR: `git diff --check`.

## Risks / notes

- D1 is the load-bearing decision. If prompts are not on the classpath, every
  pipeline ticket that loads a prompt breaks at runtime in the deployed jar while
  passing locally. Verify the packaged jar actually contains `prompts/` (e.g.
  `jar tf build/libs/*.jar | grep prompts/`) as part of done.
- Keep the loader free of any DataSource/provider dependency so the existing
  no-DB context test stays green.
- Per CLAUDE.md handoff roles this is a Codex-implemented ticket
  (`ce-plan` → `ce-work` → `ce-code-review`); this plan is the hand-off medium.
  No Fable5 quality review is required for #28.
