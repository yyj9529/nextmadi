# Exec plan: <short title>

File naming: `docs/exec-plans/YYYY-MM-DD-<slug>.md` (e.g.
`2026-06-10-s07-ambiguous-emotion.md`). Written by the implementing agent before coding;
the reviewing agent and the owner read it. Plans live here because they otherwise vanish
with the chat session — only code gets committed, so the reasoning must be saved
deliberately.

## Goal
What this change accomplishes, in one or two sentences. Tie to the screen spec or ADR.

## Source specs
Which `docs/screens/sNN.md`, ADRs, `AI_PIPELINE.md` sections, or `data-model.md` tables
this depends on.

## Files expected to change
Best-guess list. It is fine if this shifts during execution — note what actually changed
in "What changed after execution".

## Acceptance criteria
Concrete, checkable. For screens, the G-W-T. For AI changes, the schema and eval bar
from `docs/quality-gates.md`.

## Test plan
Which tests are written first (TDD for behavior changes), what the eval covers, what is
verified in the browser.

## Risk areas
Auth, cost, data loss, schema drift, roleplay-state edge cases — whatever could break.
If this is a risky change, note that the two-agent review (CLAUDE.md section 9) applies.

## Decision log
Choices made during planning and why. Decisions, not pre-specified implementation detail.

## Final outcome
Filled after execution: pass/fail against acceptance criteria, gate result, link to the
review in `docs/reviews/`.

## What changed after execution
Where reality diverged from the plan, and the lesson. If a mistake recurred, bump its
row in the `docs/solutions/README.md` ledger; if it is now at 2 or more, write the
pattern note. Leaving this section empty is how the learning loop dies — 13 of the
first 24 exec-plans had it blank.
