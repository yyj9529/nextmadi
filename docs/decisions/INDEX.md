# Decision index (ADR summaries)

One-line summary per ADR. This index is the always-loaded artifact; full ADRs are
read on demand from `docs/decisions/NNN-*.md` only when a task touches that decision.
This keeps the always-load context small (see `docs/harness.md` "Context loading
policy").

Maintenance rule: when a new ADR is added or its status changes, add or update its
line here. One line each — if a summary needs more than one line, it is too long.

| ADR | Status | Decision (one line) |
|-----|--------|---------------------|
| 001 | Accepted | Voice uses a split pipeline (Whisper STT + Claude LLM + OpenAI TTS), not the OpenAI Realtime API. |
| 002 | Accepted | No streaks; a cumulative expression bookshelf that only grows, with milestone celebrations. |
| 003 | Accepted | Eval is three tiers — S07 analysis (T1), S12 roleplay (T2), golden dataset + dashboard (T3). |
| 004 | Accepted | Launch phase reallocated to planning 3w, dev 5w, prep 3w, within the 24-week roadmap. |
| 005 | Accepted | Conventional stack — Spring Boot + Next.js + RDS PostgreSQL on AWS; Supabase rejected. |
| 006 | Accepted | v1 ships all three features (Save Phrase + Guided Roleplay + Character System) together at W12. |
| 007 | Accepted | Single-source-of-truth doc structure; public repo layout plus Notion for private content. |
| 008 | Proposed | CLAUDE.md is the single authored source; AGENTS.md is Codex's entrypoint (pointer or generated), kept in mechanical sync. |
| 009 | Proposed | Each eval case runs N trials (start N=3) to measure variance; per-trial scores are stored. |
| 010 | Accepted | Auth handoff is BFF: browser → Next.js → Spring Boot with signed internal token; openapi.yaml is the internal contract. |
| 011 | Accepted | `ai_request_logs` stores one row per attempt, not per call, so retried attempts are billed to cost and countable as failures. |

## Related

- `docs/decisions/` — full ADR bodies (on-demand).
- `docs/harness.md` — context loading policy that loads this index, not full ADRs.
- `CLAUDE.md` — session entry sequence.
