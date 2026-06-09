# Harness investigation log

Date: 2026-05-30 to 2026-05-31
Status: Record of how the harness composition was decided.

A factual record of the research and Claude-vs-ChatGPT cross-validation that produced
`docs/harness.md`, ADR-008, and ADR-009. It exists because the reasoning lived in chat
sessions and would otherwise be lost, and because two of the cross-validation rounds
contained model errors worth remembering. Decisions are in the linked ADRs and harness
doc; this is the why-we-got-there.

## What was investigated

Three agent-harness frameworks, each a different implementation of the same
plan-build-review loop:

- Compound Engineering (EveryInc) — multi-harness plugin; loop plus a learning-capture
  step (`/ce-compound`); 35+ skills.
- Superpowers (obra) — agentic skills framework; TDD and subagent-driven two-stage
  review; auto-triggering skills.
- gstack (Garry Tan) — opinionated Claude Code setup; browser QA, deploy/canary;
  velocity-oriented.

## Cross-validation rounds

### Round 1 — composition

Claude proposed Compound Engineering as the spine, Superpowers principles absorbed,
gstack deferred. ChatGPT agreed on direction and corrected two points, both valid:

- Claude overstated that Superpowers "overrides CLAUDE.md." Its precedence is
  user/CLAUDE.md above skills above base prompt — it overrides the base prompt, not the
  working agreement.
- Claude proposed importing gstack `/qa` for browser testing. Compound Engineering
  already ships `/ce-test-browser`, so the import was premature; and gstack `/qa`
  auto-edits source, which conflicts with the comprehension-over-velocity principle.

### Round 2 — a Claude verification failure

Claude claimed ChatGPT had invented two skills (`/ce-strategy`, `/ce-product-pulse`).
Direct fetch of the repo's `docs/skills/` showed both exist. Claude was wrong.

Cause: Claude treated the plugin component-reference README's skill table as the
complete inventory; the `docs/skills/` directory holds more skills than that table
lists. Absence from a summary document was mistaken for non-existence.

Lesson (recorded for the self-improvement loop): a claim that "X does not exist" is not
verified until the actual file tree is checked. README absence is not evidence of
absence. Both models produced a factual error in this exchange — ChatGPT on Superpowers
precedence wording in an earlier turn, Claude on skill existence here — so neither
model's "does not exist" should be trusted without opening the source.

### Round 3 — comparison against the harness-engineering lecture

The lecture summary aligned with about 80 percent of the existing design (one-spine,
high-signal context, verification-in-loop, revisit assumptions). The value was the
delta, and the two models caught different real gaps:

- Claude caught: the missing `AGENTS.md` (Codex reads it; only `CLAUDE.md` existed) and
  the missing eval Trial concept (single-run scoring ignores LLM non-determinism).
- ChatGPT caught: a single project-wide quality score is wrong for a multi-dimensional
  product (use per-feature gates instead); full eval transcripts risk user privacy
  (store summary metadata only); a concrete error-response contract; a short
  extracted `SECURITY.md`.

Neither model alone covered the full set. The union became the eight add-now items.

## Decisions reached

Settled:

- Compound Engineering is the spine; Superpowers contributes principles only; gstack is
  deferred. (Recorded operationally in `docs/harness.md`.)
- Add now: `AGENTS.md` canonical (ADR-008), context-loading policy, `SECURITY.md`,
  `docs/exec-plans/`, `docs/quality-gates.md`, error-response contract, eval process
  metadata, eval Trial repetition (ADR-009), selective cross-verification.
- Keep: S07/S12 eval separation, `ai_request_logs` observability, `sNN.md` GWT,
  ADR-centered decisions.
- Defer to operate-period: Langfuse / OpenTelemetry / K8s / dashboards, gstack `/qa`,
  parallel-agent and auto-PR pipelines, human-annotation scale-up.

Rejected (recorded so the same feedback does not return):

- Single QUALITY-SCORE threshold — flattens multi-dimensional quality.
- Skip-permissions as default — unacceptable given secrets and cost surface.
- Parallel agents / auto-PR at MVP — velocity tooling that fights comprehension for a
  solo learner pre-codebase.

## Related

- `docs/harness.md` — operational guide and composition.
- ADR-008 — AGENTS.md canonical.
- ADR-009 — eval trial repetition.
- `CLAUDE.md` "3-axis validation" and "self-improvement loop" — the discipline this log
  feeds.
