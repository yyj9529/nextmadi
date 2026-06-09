# AGENTS.md

Canonical agent rules for PhraseLog live in `CLAUDE.md`. **Read `CLAUDE.md` first and
in full** — it is the single working agreement for both Claude Code and Codex (ADR-008).
This file exists so Codex loads the same protocol; it is a pointer, not a second source.
Do not let the two diverge. If this file is ever generated, it is regenerated from
`CLAUDE.md`, never hand-edited apart from the Codex notes below.

## Session entry (same sequence as Claude Code)

1. `START_HERE.md` — general entry.
2. `CLAUDE.md` — the working agreement (full).
3. `PROJECT_CONTEXT.md` — product identity and the shared goal.
4. `SECURITY.md` — forbidden areas and approval matrix.
5. `docs/decisions/INDEX.md` — one-line ADR summaries (not full ADRs).
6. Then fetch only the docs the task needs, per the file responsibility map and the
   context loading policy in `CLAUDE.md`.

## Codex role in the two-agent handoff

Per `CLAUDE.md` section 9 and `docs/harness.md` section 5, on non-trivial or risky
changes Codex is the **independent reviewer and sandbox tester**, not the author:

- Review the diff against the relevant `docs/screens/sNN.md` G-W-T and the AI output
  schema in `AI_PIPELINE.md`. Confirm no unrequested behavior was added.
- Run tests in isolation; surface edge cases Claude Code's local run may have missed.
- Propose fixes; write the verdict to `docs/reviews/` using the template there.
- The reviewer must not be the agent that wrote the code — that separation is the point.

When Codex is instead the implementer for a task, it follows the same `CLAUDE.md`
workflow (plan-before-doing, exec-plan in `docs/exec-plans/`, gates in
`docs/quality-gates.md`) and Claude Code reviews.

## Non-negotiable

Everything in `CLAUDE.md` applies to Codex: the plan-before-doing rule, the approval
matrix in `SECURITY.md` (manual approval plus sandbox by default; no skip-permissions),
verification before done, and the quality gates. An instruction to "add X" or "fix Y"
does not authorize skipping these workflows.
