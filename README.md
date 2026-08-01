# PhraseLog

AI English coaching for Korean immigrants in the US. Built around one conviction — that responding with confidence in real conversations matters more than producing textbook-perfect English.

## Status

Planning phase. v1 implementation begins week 4 of the 12-week build cycle. No deployed product yet.

What exists now: the planning document set — PRD, ADRs, screen specs, data model, AI pipeline, API contract, architecture, working agreement, and the agent harness docs (harness, quality gates, security, decision index). What does not exist yet: the codebase, deployed services, or eval results from real users.

Target v1 launch: week 12. Target v1.1+ (operations and refinement): weeks 13–24.

## What the product does

User loop:

1. **Describe a situation in Korean** — "친구가 약속에 늦었는데 화내지 않고 표현하고 싶어요."
2. **AI returns 3 English variants** — each with tone, IPA, Korean phonetic guide, and a cultural tip.
3. **Save to the library** — expressions accumulate as a personal bookshelf.
4. **Practice via roleplay** — a chosen AI coach (Mia / David / Sarah) leads a 3–10 turn conversation using the saved expression.
5. **Review via active recall** — Korean situation appears first; user attempts English from memory; rating schedules the next review.

Closed loop: save → practice → review. No streak shaming. No absence-based decay. Documented in ADR-002.

## Stack

| Layer | Technology |
|-------|-----------|
| Frontend | Next.js (Vercel, PWA-installable) |
| Backend | Spring Boot 3.x on Java 21 (AWS EC2) |
| Database | PostgreSQL on AWS RDS |
| Storage | S3 (audio cache via content-hash) |
| Auth | NextAuth (Google, Kakao, email magic-link) |
| LLM | Anthropic Claude — model routing in `docs/AI_PIPELINE.md` |
| STT / TTS | OpenAI Whisper / TTS-1 |
| Observability | CloudWatch + per-call logging in `ai_request_logs` |

Stack rationale: ADR-005. Pipeline rationale: ADR-001.

## Documentation hierarchy

For AI collaborators (Claude Code, Codex, ChatGPT review sessions): start with `CLAUDE.md` (Codex reads `AGENTS.md`, which points there). It defines the working agreement.

Always loaded (every session):
- `CLAUDE.md` / `AGENTS.md` — working agreement, language norms, workflow patterns
- `START_HERE.md` — general orientation pointer
- `PROJECT_CONTEXT.md` — product identity, target user pain, positioning
- `SECURITY.md` — forbidden areas and approval matrix
- `docs/decisions/INDEX.md` — one-line ADR summaries

Read on demand per task:
- `docs/PRD.md` — v1 scope, 14 screens, open questions
- `docs/architecture.md` — system architecture, deploy, networking, observability
- `docs/data-model.md` — database schema, indexes, FK relationships
- `docs/AI_PIPELINE.md` — STT → LLM → TTS routing, JSON schemas, cost, latency
- `docs/EVAL_PLAN.md` — eval strategy and tiers
- `docs/api/openapi.yaml` — REST API contract
- `docs/screens/sNN.md` — per-screen User Stories, G-W-T, UI states, edge cases
- `docs/decisions/NNN-title.md` — full Architecture Decision Records
- `docs/harness.md`, `docs/quality-gates.md` — agent harness operation and done criteria

Eval and prompts:
- `eval/s07-analysis/` — Tier 1 mini eval cases and judge prompt
- `prompts/{feature}/v{N}.md` — versioned LLM prompts

## Project structure

```
phraselog/
├── README.md               (this file)
├── CLAUDE.md               working agreement (both tools)
├── AGENTS.md               Codex pointer → CLAUDE.md
├── START_HERE.md           general entry guidance
├── PROJECT_CONTEXT.md      product identity and target user
├── SECURITY.md             always-load security boundaries
├── docs/
│   ├── PRD.md              v1 scope and screens
│   ├── architecture.md
│   ├── data-model.md
│   ├── AI_PIPELINE.md
│   ├── EVAL_PLAN.md
│   ├── harness.md          agent harness operation + task routing
│   ├── quality-gates.md    per-feature done criteria
│   ├── harness-investigation.md
│   ├── decisions/          ADRs (INDEX.md + 001 onward)
│   ├── api/openapi.yaml    REST contract
│   ├── screens/            sNN.md per screen
│   ├── exec-plans/         implementation plans (handoff)
│   ├── reviews/            two-gate review records (handoff)
│   └── solutions/          durable learnings (/ce-compound)
├── eval/
│   └── s07-analysis/       Tier 1 eval cases
├── prompts/                versioned LLM prompts
└── .github/workflows/      CI/CD
```

Note: as of W1–3, only the documentation tree exists. Source code (`frontend/`, `backend/`) appears starting W4.

## Local development

Will be filled in starting W4 when the codebase exists. Expected setup at that point:

- Node 20+ and pnpm for the frontend
- JDK 21 and Gradle for the backend
- PostgreSQL 16 locally via Docker
- AWS credentials for S3 (optional during local dev — TTS cache reads can fall back to direct API calls)
- Environment variables loaded from a `.env.local` that is never committed

Setup script (`scripts/dev-setup.sh`) will be added with the first backend commit.

## Project decisions

Each non-trivial architectural decision lives in `docs/decisions/`:

| ADR | Topic |
|-----|-------|
| 001 | Split AI pipeline (Whisper + Claude + OpenAI TTS) over a single Realtime API |
| 002 | Cumulative bookshelf, no streak system |
| 003 | Eval system in three tiers (S07 mini, S12 roleplay, golden dataset) |
| 004 | 24-week roadmap split into 12-week build and 12-week operate |
| 005 | Stack choice (Spring Boot + Next.js + RDS PostgreSQL) |
| 006 | Loop coherence — save, practice, review as inseparable v1 components |
| 007 | Documentation structure and division of responsibility |
| 008 | AGENTS.md as the canonical agent-rules source for both tools (proposed) |
| 009 | Eval trial repetition for variance measurement (proposed) |
| 010 | BFF auth handoff — browser to Next.js to Spring Boot with a signed internal token |
| 011 | `ai_request_logs` records one row per attempt, so retries are billed and countable |

Each ADR is 300–500 words, follows Drew DeVault's sourcehut style.

## Who is this for

- **AI collaborators** — every Claude Code or Codex session starts by reading `CLAUDE.md`. The repo is structured to be re-entrant: any session can pick up where another left off by reading the relevant doc per the file responsibility map in `CLAUDE.md`.
- **Future maintainer** — the documentation is dense enough that the project can be paused and resumed without context loss. This is the explicit reason for the documentation volume during the planning phase.
- **The owner himself** — 이우주, six months from now, returning to debug something at 2am.

## License

Solo development project. License model not decided. Documentation is currently visible for AI collaboration; this may change before launch.

## Contact

이우주 — contact details intentionally omitted from the repo. Reach the owner through whatever channel led you here.
