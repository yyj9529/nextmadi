# Review: <short title>

File naming: `docs/reviews/YYYY-MM-DD-<slug>-review.md`. Written by the reviewing agent
(the one that did not author the code — usually Codex reviewing Claude Code's work, per
CLAUDE.md section 9). Two gates; both must pass before the owner merges.

- Target: <PR / branch / commit / diff>
- Reviewer: <Claude Code | Codex>
- Author: <the other tool>
- Related exec-plan: `docs/exec-plans/<file>.md`
- Date: YYYY-MM-DD

## Gate 1 — Spec compliance

- [ ] Satisfies the `docs/screens/sNN.md` G-W-T (or the stated acceptance criteria).
- [ ] Honors the AI output schema in `AI_PIPELINE.md` (for AI changes).
- [ ] No unrequested features or scope creep beyond the exec-plan.
- [ ] Error responses follow the contract (`docs/harness.md` section 6).

Findings: <specifics, with file:line where possible>

## Gate 2 — Code quality

- [ ] No needless abstraction; matches the senior-solo "spec on demand" principle.
- [ ] Testable; tests exist and are meaningful (not asserting trivia).
- [ ] Auth, cost, logging, and error handling are safe (`SECURITY.md`).
- [ ] No secret exposure; no raw user text in committed artifacts.
- [ ] Migration reviewed and rollback documented (for DB changes).

Findings: <specifics>

## Verdict

`pass` / `changes required`. If changes required, list them as concrete, ordered items
the author can act on. State the matching gate from `docs/quality-gates.md` and whether
it passed.

## Notes for the owner

Anything needing a human decision before merge (risk, trade-off, irreversible step).
