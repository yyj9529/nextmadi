# PhraseLog — Context for AI Collaborators

PhraseLog is an AI communication coach for Korean immigrants in the U.S.
Not a generic English-learning app. Not a real-time translation tool.

Core promise: "Turn what I couldn't say today into something I can say next time."

## Status (September 2026)

Implemented. Next.js frontend lives in `src/`, Spring Boot backend in `backend/`; all S01~S12b
screens, auth, and the AI pipeline exist in code. ADRs 001~007 and 010~011 accepted;
008~009 proposed. Current focus: expanding the eval system (`eval/`, `docs/EVAL_PLAN.md`).

Schedule baseline: 12-week launch phase per ADR-004; exec-plans in `docs/exec-plans/` track actual progress.

## Read in this order

1. **`PROJECT_CONTEXT.md`** — product identity, MVP loop, primary user pain
2. **`docs/decisions/001-split-pipeline-over-realtime-api.md`** — voice pipeline architecture
3. **`docs/decisions/003-eval-system-s07-s12-separation.md`** — eval system structure
4. **`docs/decisions/004-reallocate-launch-phase-weeks.md`** — 12-week schedule (3w planning / 5w dev / 3w prep)
5. **`docs/decisions/005-spring-boot-nextjs-rds-stack.md`** — tech stack on AWS
6. **`docs/decisions/006-v1-integrated-launch.md`** — integrated v1 launch strategy
7. **`docs/decisions/002-cumulative-bookshelf-over-streak.md`** — UX/motivation philosophy (read last; product-philosophy decision)

## Do not suggest

These have been explicitly considered and rejected. Re-proposing them without new information wastes the project owner's time:

- **Real-time translation** as an MVP feature — contradicts product positioning
- **OpenAI Realtime API** — see ADR-001 Why not
- **Supabase-only architecture** — see ADR-005 Why not
- **Streak mechanics** for engagement — see ADR-002 Why not
- **Full eval dashboard before launch** — Tier 3 is post-launch by design, see ADR-003
- **Sequential sprint releases** (ship Save Phrase first, then Roleplay, then Character) — see ADR-006 Why not
- **Generic edtech patterns** by default — PhraseLog diverges from typical Duolingo-style design; check assumptions before applying them

## When making new architectural decisions

Follow the ADR writing principles applied to ADRs 001~011. Summary:

- Decisions justified by product / user / engineering logic — not by experience level, learning goals, or portfolio language
- Specific numbers (thresholds, counts, page lengths, milestone values) only when explicitly agreed with the project owner; otherwise mark "TBD in planning"
- Alternatives sections list only options actually considered in conversation, not industry-standard boilerplate
- 300~500 words per ADR, Drew DeVault / sourcehut style — avoid enterprise compliance document patterns (no oversized Verification Plan sections, no "Operational Data Collection" tables for things that don't change the decision)
- Memory-sourced data is not automatically verified — confirm sources before citing numbers as facts

Full ADR writing rules live in `CLAUDE.md` ("ADR style"). One-line ADR summaries live in `docs/decisions/INDEX.md`.

## When uncertain

Ask the project owner. PhraseLog has specific design constraints that contradict typical edtech and SaaS patterns; do not infer from general industry norms. The owner has done extensive user research (Threads survey of Korean immigrants + offline interviews) and many design choices come from that research rather than from industry defaults.

## Related context outside this repo

- **PPT v2.1** — 31-slide storyboard with screen layouts; reference by section title (e.g., "Friction Audit", "Character System") rather than slide number, as numbers may change in future PPT revisions
- **Notion `Research Notes`** — raw user research data, owner's private working notes
