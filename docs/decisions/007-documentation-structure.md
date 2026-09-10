# ADR-007: Documentation structure and content separation

Date: 2026-05-16
Status: Accepted

## Context

Without explicit structure, project documentation drifts. The same requirement living in two locations diverges over time. AI-assisted development quality drops when specs are scattered and not referenced by predictable path (ADR-004). Personal context risks getting committed to a public repository regardless of intent. Context window limits compound these failure modes — a single 30K+ document is unreliable as input to AI sessions because relevant sections get missed.

## Decision

Public repo:

```
/
├── README.md
├── START_HERE.md
├── PROJECT_CONTEXT.md
├── CLAUDE.md
├── docs/
│   ├── PRD.md                  User Story + 1-2 line acceptance summary
│   ├── architecture.md         full system architecture
│   ├── AI_PIPELINE.md          STT, LLM routing, TTS, logging
│   ├── EVAL_PLAN.md            eval strategy
│   ├── data-model.md           DB schema + ERD
│   ├── ops-cost-report.md      W17+
│   ├── launch-validation.md    W9+
│   ├── decisions/              ADRs
│   ├── screens/                per-screen specs (full G-W-T + UI)
│   ├── api/                    OpenAPI contracts
│   ├── journal/                weekly notes, optional
│   └── notes/                  raw learning material, optional
├── eval/
├── prompts/
└── .github/workflows/
```

Private workspace (Notion): personal career material, raw user research with identifying information, weekly delta log.

Three operational rules:

1. **G-W-T placement.** `docs/screens/sNN.md` is the single source of truth. PRD contains only User Story plus 1-2 line acceptance summary per feature.
2. **Screen spec granularity at W1-3.** Full specs for S02, S05a, S07, S08, S10, S12, S12b. Slim specs (User Story + 3-5 key behaviors) for the remaining seven screens. Slim specs upgrade to full just-in-time before coding that screen.
3. **Post-launch doc timing.** `ops-cost-report.md` and `launch-validation.md` are not created until their underlying data exists.

## Why

Single source of truth removes drift entirely. One location, one edit, no synchronization needed. In solo development, synchronization across duplicate documents is the operation most likely to be skipped.

Predictable file locations are the operational requirement behind ADR-004's AI-assisted development DoD. "Implement S07 per `docs/screens/s07.md` with API contract per `docs/api/analysis.yaml`" depends on this structure existing.

Co-locating acceptance criteria with UI implementation in one screen spec reduces context switching during development. A developer coding S07 opens one file and finds G-W-T, layout, and state handling together rather than triangulating across multiple documents.

Notion separation is a privacy requirement. Personal context cannot live in public commits — git history makes that irreversible. Filesystem boundaries enforce the public/private split mechanically, not by per-commit discipline.

Post-launch documents written pre-launch contain estimates, not measurements. `ops-cost-report.md` at W3 records assumed per-request costs; at W17 with production data, it records measured costs. The pre-launch version is overhead with no information value. Deferring authorship to when data exists prevents both wasted effort and the temptation to rely on estimates as if they were measurements.

Just-in-time slim-to-full upgrading of screen specs prevents spec aging. A spec written at W1 for code written at W7 may conflict with later decisions; slim specs avoid pre-committing to details that are likely to change.

## Consequences

PRD stays around 10K characters and serves as scope reference, not implementation guide.

Screen specs become the primary working documents during W4-8 development.

Notion is required infrastructure for the project, not optional.

If duplication appears across documents, the rule is to delete from the non-authoritative location, not to synchronize both. Drift is the warning sign that structure is failing.

`journal/` and `notes/` are not required to be populated. Empty is acceptable — forced entries to satisfy a perceived schedule create noise without information value.

## Why not

- **All G-W-T in PRD.** Returns PRD to 30K+; the size undermines the precision goal that motivated this decision.
- **All documentation in GitHub.** Private content cannot live in public commits; git history is permanent. No technical workaround exists.
- **No structure, write as needed.** Contradicts ADR-004's W1-3 DoD. AI-assisted velocity assumption breaks when specs are scattered or absent.
- **All screen specs full at W1-3.** Spec aging affects deferred screens; W4 entry deadline becomes harder to hit when planning expands to all fourteen screens at full depth.

## Amendment (2026-05-31)

The harness work (ADR-008, ADR-009, `docs/harness.md`) extends the structure above with
operational artifacts. Under `docs/`: `exec-plans/` (implementation plans and retros),
`reviews/` (two-gate review records) — both handoff artifacts described in
`docs/harness.md` section 9 — `solutions/` (durable learnings from `/ce-compound`), and
`decisions/INDEX.md` (one-line ADR summaries, always-loaded). At the repo root:
`AGENTS.md` (Codex protocol pointer, ADR-008) and `SECURITY.md` (always-load boundaries).
These extend, not replace, the structure; the single-source-of-truth rule applies to
them unchanged. `docs/harness.md` is the canonical reference for why these exist.

## Amendment (2026-09-10)

Applied this ADR's own privacy rule to its own text. The public tree no longer plans a
portfolio case study, and the private-workspace contents are now named by category
rather than itemized.

The original wording listed specific career artifacts and planned a public document
whose purpose was presentational rather than operational. That is the exact personal
context this ADR says cannot live in public commits, and CLAUDE.md separately forbids
portfolio framing in technical docs. The boundary was correct; this ADR was on the wrong
side of it.

Nothing about the public/private split itself changes. Post-launch write-ups of measured
cost and launch validation are unaffected — they document the system, and their timing
rule stands.

## Related

- ADR-004: AI-assisted development requires precise specs. This ADR operationalizes that DoD as filesystem structure.
- PROJECT_CONTEXT.md "Public vs private context": existing principle made operational here.
