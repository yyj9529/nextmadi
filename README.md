# PhraseLog

AI English coaching for Korean immigrants in the US — built around one conviction: that
responding with confidence in a real conversation matters more than producing
textbook-perfect English.

**Stack:** Next.js (App Router) · Spring Boot 3 / Java 21 · PostgreSQL · AWS S3 ·
NextAuth · Anthropic Claude · OpenAI Whisper & TTS

---

## Status

**In active development.** The v1 product loop is implemented end-to-end across the
frontend, backend, and AI pipeline, and runs locally without API keys (mock AI clients +
filesystem audio storage). Not yet publicly deployed — v1 launch is targeted for week 12
of a 12-week cycle.

| Area | State |
|---|---|
| Frontend | 15 pages + 18 BFF API routes (Next.js App Router, PWA-installable) |
| Backend | Spring Boot service, 15 feature packages, 10 Flyway migrations |
| Auth | NextAuth — Google, Kakao, and email magic-link over Amazon SES |
| AI pipeline | Claude analysis + roleplay, OpenAI STT/TTS, schema validation, per-call cost logging |
| Eval | LLM-as-judge harness, 82 cases, N=3 trials, enforced as a CI regression gate |
| Tests | 121 test files across TypeScript and Java |
| CI | `lint-test.yml` (typecheck, lint, unit tests) · `eval.yml` (prompt regression gate) |

## What the product does

1. **Describe a situation in Korean** — "친구가 약속에 늦었는데 화내지 않고 표현하고 싶어요."
   (voice or text; voice goes through Whisper).
2. **Claude returns 3 English variants** — each with tone, IPA, a Korean phonetic guide,
   and a cultural tip.
3. **Save to the library** — expressions accumulate as a personal bookshelf.
4. **Practice via roleplay** — a chosen AI coach (Mia / David / Sarah) runs a 3–10 turn
   conversation that forces the saved expression into use.
5. **Review via active recall** — the Korean situation appears first; the user attempts
   the English from memory; the rating schedules the next review.

Closed loop: save → practice → review. No streaks, no absence-based decay — user research
found that streak-shaming works against this segment's dominant emotional pattern
([ADR-002](docs/decisions/002-cumulative-bookshelf-over-streak.md)).

## Engineering notes

The parts most likely to be interesting to another engineer:

**The AI layer is treated as production infrastructure, not as an API call.**

- **Prompts are versioned artifacts** — `prompts/{feature}/v{N}.md`, referenced by
  `prompt_version` on every request, so output changes are attributable to a diff.
- **Evaluation gates prompt changes in CI.** `eval/s07-analysis/` runs an LLM-as-judge
  over 82 cases with a 4-dimension rubric and N=3 trials per case; a prompt change that
  regresses the aggregate score fails the PR
  ([ADR-009](docs/decisions/009-eval-trial-repetition.md),
  [EVAL_PLAN.md](docs/EVAL_PLAN.md)).
- **Structured output is enforced, not hoped for.** `JsonSchemaValidator` validates every
  model response server-side, with a single constrained retry before failing.
- **Cost and failure are observable per call.** `AiRequestLogger` + `AiCostCalculator`
  write one `ai_request_logs` row per *attempt* — not per call — so retries stay visible
  ([ADR-011](docs/decisions/011-per-attempt-ai-request-logging.md)).
- **Model routing is a config surface.** `FeatureRouting` maps each feature to a model
  tier, so routing is re-evaluated with data rather than rewritten in code.
- **AI is testable offline.** `MockAnthropicClient` / `MockOpenAiTtsClient` /
  `MockOpenAiTranscriptionClient` let the whole stack run and be tested without keys or
  spend.

**Other decisions worth a look:** a BFF layer in Next.js route handlers holding the
signed internal-auth boundary to Spring ([ADR-010](docs/decisions/010-bff-auth-handoff.md));
idempotency keys on analysis, practice sessions, and roleplay results; an
account-deletion grace period rather than an immediate hard delete; per-IP daily quotas
on anonymous analysis and transcription, kept in separate budgets so a bad transcription
cannot burn an analysis attempt.

## Running it locally

```bash
bun install
cp .env.example .env   # mock AI clients are the default; no API keys required
bun run dev
```

```bash
cd backend && ./gradlew bootRun --args='--spring.profiles.active=local'
```

The frontend must run on port 3000 — the Google OAuth callback is registered against
`localhost:3000`, and any other port fails with `redirect_uri_mismatch`. The `local`
Spring profile substitutes mock AI clients and filesystem audio storage, so the loop is
exercisable without provider keys or spend.

Quality gates:

```bash
bun run typecheck && bun run lint && bun run test
```

```bash
cd backend && ./gradlew spotlessCheck test build
```

## Repository map

| Path | Contents |
|---|---|
| `src/` | Next.js app — pages, BFF route handlers, client libraries |
| `backend/` | Spring Boot service — controllers, services, repositories, migrations |
| `prompts/` | Versioned LLM prompts (`{feature}/v{N}.md`) |
| `eval/` | S07 analysis eval harness, case set, judge rubric |
| `docs/` | PRD, architecture, data model, AI pipeline, API contract, screen specs |
| `docs/decisions/` | Architecture Decision Records — start at [`INDEX.md`](docs/decisions/INDEX.md) |
| `docs/solutions/` | Recurring-mistake ledger and the automated guards added for each |

## On the documentation volume

This repository carries an unusually large specification set for a solo project. The
reason is operational: development runs with AI coding agents, and an agent opening a
session has no memory of last week's reasoning. Written specs, ADRs, and explicit quality
gates are what make an agent's output reviewable instead of merely plausible-looking.
`CLAUDE.md` is the working agreement the tooling loads; `docs/harness.md` and
`docs/quality-gates.md` define how a change gets from plan to merge.

`docs/solutions/` is the other half of that: every mistake that recurred got a note, and
by the third recurrence an automated guard — a hook, a test, or a CI check — rather than
an intention to be more careful.

## Author

이우주 — contact details intentionally omitted from the repo. Reach the owner through
whatever channel led you here.
