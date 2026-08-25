# PhraseLog AI development harness

Status: Active. Compound Engineering is installed (compound-engineering@compound-engineering-plugin
v3.15.0, user scope, verified 2026-06-30); its 26 `/ce-*` commands are available. Project-native
commands (`/ticket-start`, `/spec-check`, `/ui-verify`, `spec-reviewer`) coexist with the CE spine —
role split recorded in section 7.1. Supersedes the v1 harness draft. Skill descriptions were verified
against the EveryInc/compound-engineering-plugin and obra/superpowers repos on 2026-05-30; CE install
and command inventory re-verified 2026-06-30.

This is the operational guide for how agents work on PhraseLog. The composition
rationale and the cross-validation that produced it are recorded in
`docs/harness-investigation.md`. Specific sub-decisions are ADR-008 (AGENTS.md
canonical) and ADR-009 (eval trial repetition).

## 1. Composition

- **Spine — Compound Engineering (installed).** Multi-harness (Claude Code, Codex,
  Cursor), matching the CC + Codex environment. Commands by role:
  strategy `/ce-strategy`; planning-phase doc review `/ce-doc-review` + reviewer
  agents; feature loop `/ce-brainstorm` → `/ce-plan` → `/ce-work` → `/ce-simplify-code`
  → `/ce-code-review`; AI quality `/ce-optimize`; debugging `/ce-debug`; knowledge
  capture `/ce-compound` (+ `/ce-compound-refresh`); operation `/ce-product-pulse`
  (post-launch only).
- **Project-native commands (kept alongside the spine).** `/ticket-start` (git + issue
  + reference-file loading at ticket entry — no CE equivalent); `/spec-check` (gate 1,
  spec compliance against `docs/screens/sNN.md` — complements CE `/ce-code-review`,
  which is gate 2, code quality); `/ui-verify` (browser evidence via Playwright MCP);
  `spec-reviewer` (read-only independent reviewer for the section 5 handoff).
- **Browser evidence — `/ui-verify`, not `/ce-test-browser`.** CE's browser command
  requires the separate `agent-browser` CLI and carries no project guardrails.
  `/ui-verify` runs on the already-available Playwright MCP and bakes in the
  required guardrails: no PII / real user text in screenshots or logs (`SECURITY.md`),
  mobile-width check (390x844), and per-screen state reproduction (empty / error /
  normal) from `docs/screens/sNN.md`. `/ce-test-browser` is therefore not adopted.
- **Principles — Superpowers (absorbed, not installed).** TDD for behavior changes;
  two-gate review (spec compliance, then code quality). Not installed because its
  "1% chance a skill applies, you must invoke it" rule over-proceduralizes simple
  tasks and collides with the CE spine.
- **Deferred — gstack.** Auto-edits source. Use only after a stable staging URL,
  `/qa-only` first, on a clean branch, never production, no auto-merge.

## 2. Design principles

1. One spine, no competing loops.
2. The harness packages methodology; `CLAUDE.md` / `AGENTS.md` still govern.
3. Fit-to-task over popularity.
4. Comprehension over raw velocity.
5. Read-only and no-auto-merge by default.

## 3. Layered structure

- **Layer 1 — always loaded.** `CLAUDE.md`, `START_HERE.md`, `SECURITY.md`, current
  sprint goal, recent ADR summaries, `docs/solutions/README.md` (mistake ledger table
  only — the per-pattern notes stay on demand).
- **Layer 2 — canonical project artifacts.** `PROJECT_CONTEXT.md`, `PRD.md`,
  `docs/architecture.md`, `docs/data-model.md`, `docs/AI_PIPELINE.md`,
  `docs/screens/sNN.md`, `docs/api/openapi.yaml`, `docs/decisions/`.
- **Layer 3 — execution artifacts.** `docs/exec-plans/`, `docs/reviews/`,
  `docs/solutions/`, `eval/runs/`.
- **Layer 4 — verification.** Unit/integration tests, S07 mini eval, browser checks,
  `ai_request_logs`, `.github/workflows/eval.yml`.
- **Layer 5 — future observability.** `/ce-product-pulse`, Langfuse/Braintrust/custom
  dashboard, human annotation, S12 roleplay eval.

## 4. Context loading policy

The policy is canonical in `CLAUDE.md` "Context loading policy" (and therefore in
`AGENTS.md` per ADR-008). It was drafted here and has since been adopted there; the
lists are deliberately not restated in this file, because a second copy drifts from
the first.

Reason: context is a finite resource; high-signal-at-start plus fetch-on-demand keeps
the working context in its effective range rather than filling the window.

This file is itself load-on-demand. It is pulled by `/ticket-start` (which follows
section 7 for the cycle order), and by `CLAUDE.md` section 9 / `AGENTS.md` when a
risky change needs the section 5 handoff.

## 5. Cross-verification (selective)

For risky changes only — AI pipeline, auth, DB migration, payment, roleplay state —
run a manual handoff: Claude Code implements and writes tests, Codex reviews the diff
independently (different model, different blind spot), Claude Code does the local final
check and merge judgment. Not every task; not automated; no parallel-agent pipeline.
Requires ADR-008 so Codex reviews against the same rules.

## 6. Error response contract

Backend errors carry a fix hint, so an agent debugging from a log knows what to change:

```json
{
  "error_code": "s07_schema_validation_failed",
  "user_message": "분석 결과를 만드는 중 문제가 생겼어요. 다시 시도해주세요.",
  "developer_hint": "LLM response missed variants[2].cultural_tip. Check prompt version s07-v2.",
  "retryable": true,
  "request_correlation_id": "…"
}
```

`user_message` is Korean and user-safe; `developer_hint` names the likely fix; the
correlation id ties the error to its `ai_request_logs` rows.

## 7. Development workflow

- **Planning (whenever a spec, ADR, or exec-plan is drafted):** `/ce-doc-review` on the
  draft before it is treated as stable. The trigger is the artifact, not the calendar —
  a screen spec or ADR written during W4+ implementation still passes through it.
- **Feature (W4–8):** `/ticket-start <issue>` (load git + issue + reference files) →
  `/ce-brainstorm` → write exec-plan (`docs/exec-plans/`) → `/ce-plan` → failing test
  (TDD) → `/ce-work` → `/ce-simplify-code` → two-gate review: `/spec-check sNN` (gate 1,
  spec compliance) then `/ce-code-review` (gate 2, code quality) → `/ui-verify <url>` if
  UI → `/ce-optimize` + S07/S12 eval if AI → quality gate (`docs/quality-gates.md`) →
  `/ce-compound` (durable note in `docs/solutions/`) + session log to
  `%USERPROFILE%\Desktop\dev-logs\`. Risky change → section 5 handoff with `spec-reviewer`.
- **Operate (W13+):** weekly `/ce-product-pulse 7d`; periodic `/ce-compound-refresh`;
  consider Langfuse and gstack `/qa-only` per their triggers.

### 7.1 Task routing

Which files to pull for which task, and how to invoke the tools. Always-load files
(`CLAUDE.md`/`AGENTS.md`, `START_HERE.md`, `PROJECT_CONTEXT.md`, `SECURITY.md`,
`docs/decisions/INDEX.md`, `docs/solutions/README.md`) load automatically — do not
attach them. In Claude Code,
`@path` attaches a file's contents; Codex reads repo files by path and `AGENTS.md`
automatically. Prompts are written naturally (Korean in practice); only the `@`-paths
and the command are shown here.

| Task | Load on demand | Produces / updates | How to invoke |
|------|----------------|--------------------|---------------|
| Plan a screen (new spec / ADR) | relevant PRD scope | `sNN.md`, maybe an ADR | CC: `@PRD.md` → draft `@docs/screens/s07.md`, then `/ce-doc-review` |
| Implement a screen (W4–8) | `sNN.md`, `openapi.yaml`, `data-model.md` | exec-plan, tests, review, browser evidence | CC: `/ticket-start <issue>`, plan with `@docs/exec-plans/exec-plan-template.md`, implement against `@docs/screens/s07.md @docs/api/openapi.yaml @data-model.md`, then `/spec-check s07` → `/ce-code-review` → `/ui-verify <url>` |
| S07 / analysis change | `AI_PIPELINE.md`, `prompts/s07/`, `EVAL_PLAN.md` | `eval/runs/`, S07 gate | CC: edit `@AI_PIPELINE.md @prompts/s07/v2.md`, then `/ce-optimize` against `@EVAL_PLAN.md` |
| S12 / roleplay | `s12`, `s12b`, `AI_PIPELINE.md`, `data-model.md` | exec-plan | CC: implement turn logic against `@docs/screens/s12.md @docs/screens/s12b.md` |
| Backend / DB / auth | `architecture.md`, `data-model.md`, `openapi.yaml` | migration, error contract, review | CC: write migration against `@data-model.md @architecture.md`; risky → request approval |
| Bug fix (W4+) | relevant `sNN.md`, logs, recent diff | `solutions/` | CC: paste log + `@docs/screens/s08.md`, then `/ce-debug` |
| Record a decision | relevant docs, `harness.md` | new ADR + `INDEX.md` line | CC: write ADR in the `@docs/decisions/007-documentation-structure.md` shape, add one `INDEX.md` line |
| Cross-verify a risky change | (SECURITY auto) | verdict in `docs/reviews/` | After CC implements → independent reviewer (Codex, or the `spec-reviewer` agent) reviews the diff against `@docs/screens/s07.md` using `@docs/reviews/review-template.md` |
| Operate (W13+) | `ai_request_logs` | `docs/pulse-reports/` | CC: `/ce-product-pulse 7d` |

For screen work, `docs/screens/sNN.md` is the source of truth — pull it, not the full
`PRD.md`. The cross-verify row is the team handoff: the reviewer is the tool that did
not write the code (section 5), and it already shares the goal and rules via the
always-load set.

## 8. What each cycle leaves behind

Exec-plan + retro (`docs/exec-plans/`), failing-then-passing tests, two-gate review
trail (`docs/reviews/`), eval run with per-trial scores and process metadata
(`eval/runs/`), and — when the cycle hit a mistake worth keeping — an updated mistake
ledger (`docs/solutions/README.md`) plus a pattern note beside it. Eval runs store
metadata and summarized rationale only — never raw user text (see `SECURITY.md`).

The learning note is not produced on every cycle. It is produced when a mistake
recurs: 1st occurrence is logged in the dev-log only, 2nd earns a note in
`docs/solutions/`, 3rd must be blocked automatically (hook, test, lint rule, or CI
gate). The ledger holds the recurrence counts that drive that escalation. Earlier
wording here required one note per cycle; that produced zero notes across 24 tickets,
so the trigger is now recurrence, not cadence.

Process metadata recorded per eval run (reason: regression and variance tracing, not
presentation):

```json
{
  "task_id": "s07_001",
  "prompt_version": "s07-v2",
  "model_name": "claude-sonnet-…",
  "trials": 3,
  "score_mean": {"naturalness": 4.0, "accuracy": 4.7, "cultural": 4.0, "tone_match": 4.7},
  "score_variance": {"naturalness": 0.2},
  "harness_path": "ce-plan -> ce-work -> ce-code-review -> ce-optimize",
  "outcome": "pass"
}
```

## 9. Open items

1. `STRATEGY.md` vs `PROJECT_CONTEXT.md` canonical source — RESOLVED 2026-06-30:
   `PROJECT_CONTEXT.md` stays the single canonical product-identity source. CE
   `/ce-strategy` is not used (it would create/maintain a competing `STRATEGY.md`). If
   strategy work is needed, fold it into `PROJECT_CONTEXT.md` rather than spawning a
   second file.
2. CE install confirmed 2026-06-30 (v3.15.0); 26 `/ce-*` commands present. Still
   confirm Codex-side availability of the same commands (multi-harness) via `/help` in
   Codex; the plugin README skill table is not exhaustive (see investigation log).
3. ADR-008 mechanism — confirm Codex AGENTS.md read behavior before choosing pointer
   vs generated.
4. gstack `/qa` auto-edit behavior — confirm against the skill file before any use.
5. ADR-009 N and variance threshold — confirm after first multi-trial run.

## Related

- ADR-008 — AGENTS.md canonical.
- ADR-009 — eval trial repetition.
- `docs/quality-gates.md`, `SECURITY.md`, `EVAL_PLAN.md`, `docs/harness-investigation.md`.
