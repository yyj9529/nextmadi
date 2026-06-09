# ADR-006: v1 ships as integrated release, not sequential sprints

Date: 2026-05-13
Status: Accepted

## Context

The initial plan structured v1 as three sequential sprints: Sprint 1 (Save Phrase), Sprint 2 (Guided Roleplay), Sprint 3 (Character System). Each sprint would deliver and launch independently before moving to the next.

PhraseLog's core structure is the complete loop: practice an expression beforehand, use it in the real situation, review afterward. Save Phrase, Guided Roleplay, and Character System together support this loop. Sprint 1 alone supports only the "save" fragment.

## Decision

Drop sequential sprints. Build all three features (Save Phrase + Guided Roleplay + Character System) in a single 12-week launch phase, shipped together at W12 as v1.

This is the structure ADR-004 schedules.

## Why

Each feature alone is a fragment of the loop, not a usable product on its own. Save Phrase without Roleplay is a vocab list; Roleplay without saved expressions is a chatbot; the Character System without the other two has nothing to attach to. Shipping any single fragment as a launchable product would either (a) ship as incomplete, or (b) require redesigning fragments to function standalone, which they aren't.

If v1 is to be PhraseLog — the loop, not a fragment — the three features ship together.

## Consequences

Higher risk concentrated at W12 launch. If integration fails, all three features fail together. Mitigated by the W9~11 launch prep buffer (ADR-004) and beta testing.

No early public launch — but early validation is still required. Private validation during planning and development (prototype reviews, manual scenario tests, structured user interviews) substitutes for production signal, though not as a full replacement. Production data itself does not exist until W12. The specific validation schedule lives in the PRD; this ADR only commits to rejecting sequential public launches, not to skipping validation entirely.

If W6 mid-development shows the integrated scope is unachievable in 5 weeks of development, the response is scope reduction within v1 (defer some features to v1.1) — not reversion to sequential sprints. The integrated-launch decision is stable.

## Why not

- **Sequential sprints (Sprint 1 → 2 → 3).** Each sprint alone supports only a fragment of the loop. Shipping a fragment as Sprint 1 would either launch as an incomplete product or require redesigning fragments to function standalone, which they aren't.

## Related

- ADR-004: 12-week launch phase schedule.
- ADR-003: Eval Tier 2 (S12 roleplay) needs real user data, which only appears after the integrated launch.
- Memory #11: 24-week roadmap (12 weeks to launch + 12 weeks operation).
