# ADR-004: Reallocate launch-phase weeks — planning 3w, dev 5w, prep 3w

Date: 2026-05-13
Status: Accepted

## Context

Initial 12-week launch plan: W1~2 planning (2w), W3~9 development (7w), W10~11 launch prep (2w), W12 launch. Two factors prompted reconsideration:

1. **AI-assisted development workflow** with Claude Code + Codex. Substantially reduces boilerplate, CRUD, UI scaffolding time — but only when input specifications are precise. Vague prompts produce inconsistent code that needs rework, eating the savings.
2. **LLM-specific components** (prompt engineering, audio integration, model routing, S07 mini eval) benefit from explicit upfront specification before implementation.

The trade-off: more planning, less development. Planning quality multiplies development velocity non-linearly when AI assists the coding.

## Decision

| Phase | Old | New |
|-------|-----|-----|
| Planning | W1~2 | W1~3 |
| Development | W3~9 | W4~8 |
| Launch prep | W10~11 | W9~11 |
| Launch | W12 | W12 |

Total 12 weeks unchanged. Launch date held at W12. Post-launch operation phase (W13~24) unaffected.

## Why

Specifications are how Claude Code and Codex stay useful. "Make S07 screen" produces unreliable output. "Implement S07 per `/docs/screens/s07.md` with API contract per `/docs/api/s07.yaml`, following ADR-003 mini eval expectations" produces reliable output on first try. The extra planning week directly compounds in development.

Launch preparation almost always gets underestimated, especially with audio APIs where device-specific issues surface late. Adding a week here is insurance against a botched launch or post-launch operational outages.

## Definition of Done — Planning Phase (W3 end)

Before W4 development begins, the following exist in the repo:

- PRD covering 14 screens with user scenarios
- DB schema and ERD
- API contracts with request/response examples
- Key architectural ADRs documented
- S07 prompt v2, versioned
- S07 mini eval cases in `eval/s07-analysis/`
- GitHub Projects with Epics defined
- `.claude/CLAUDE.md` referencing PRD and key ADRs

Validation: W4 Day 1 should support consecutive days of development work with no planning blockers.

## Consequences

Risk of planning paralysis if the W3 boundary isn't enforced. The Definition of Done above is the enforcement.

5-week development assumes AI-assisted velocity. If first-attempt code generation success rate is consistently low during early development, either the planning artifacts are insufficient (return to spec work) or the AI assistance assumption was wrong (write a supersedes ADR adjusting the launch date).

If W6 shows significantly less than half the features functional, scope reduction or schedule revision via supersedes ADR. If W11 launch prep is incomplete, push launch to W13~14 rather than ship with unresolved blockers — launching broken is worse than launching late.

Weekly velocity and AI assistance ratio per task type will get tracked in `roadmap.md` "Plan vs Actual" and Notion Daily Log respectively, for retrospective calibration on v1.1+ estimates.

## Why not

- **Keep original 2+7+2.** Doesn't reflect AI-assisted workflow. Vague specs will eat any development savings.
- **Compress total to 11 weeks** (launch at W11). Launch prep is already minimum viable; further compression increases launch-day risk for marginal calendar gain.
- **Insert 1-week PoC in W3.** Audio APIs used are mature with public documentation; PoC code typically gets thrown away. Revisit only if early development surfaces unexpected technical risk.

## Related

- Memory #11 (24-week roadmap, updated to reflect this).
- Memory #12 (ADR writing rules, applied here).
- ADR-001 (Split pipeline): planning quality determines LLM integration specification.
- ADR-003 (Eval split): Tier 1 mini eval is part of the W1~3 Definition of Done.
