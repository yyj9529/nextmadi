# PhraseLog — Product Requirements Document (v1)

Last updated: 2026-05-13
Status: Draft, planning phase (W1~3). Items marked TBD finalize before W4.

## 1. Overview

PhraseLog v1 is an AI communication coach for Korean immigrants in the United States — users who can transact in English but struggle in emotionally precise or culturally nuanced moments. It ships as a single integrated release at W12 (ADR-006).

This document specifies v1 scope, success criteria, and risks. For product identity, user pain, and positioning, see PROJECT_CONTEXT.md. For per-screen acceptance criteria, edge cases, and API references, see docs/screens/sNN.md.

## 2. Product goals

v1 must achieve all of the following:

1. **Turn regret into saved expressions** — each failed moment becomes a reusable artifact, not a one-time answer.
2. **Deliver value before signup** — visitors experience core AI output (S07) without an account.
3. **Establish the complete loop end-to-end** — Save → Review → Roleplay work as one flow (ADR-006).
4. **Build trust through emotional safety** — no mechanic in v1 punishes absence or amplifies self-blame (ADR-002).
5. **Measure AI behavior from day one** — versioned prompts, structured output, cost/latency logging operational from the first endpoint.

## 3. Success criteria

### 3.1 Launch criteria (must be true at W12)

- Complete loop runs end-to-end: Input → AI analysis → Save → Library → Review → Roleplay
- S07 returns the specified output structure (3 expressions with tone label, IPA, Korean pronunciation, pronunciation tip, cultural tip) on representative inputs
- S07 mini eval (Tier 1) baseline recorded
- `ai_request_logs` writes on every AI call
- Authentication works for Google + Kakao + email
- Private validation cycles surface no unresolved blockers
- Terms of Service and Privacy Policy are owner-approved, published at `/terms` and `/privacy`, and linked from S03; non-production placeholders are not launchable
- Seed data is loaded: `coach_profiles` (Mia/David/Sarah) and `landing_examples`

Additional criteria TBD by W3.

### 3.2 Post-launch metrics

Tracked from launch to inform v1.1. Specific thresholds will be benchmarked against early production data, not pre-set against assumed industry numbers.

- **Product**: try-without-login completion, save rate, signup-after-save rate, expressions per user, review completion, roleplay completion, D7/D30 return
- **AI quality**: S07 mini eval pass rate, JSON schema failure rate, LLM/STT/TTS latency, cost per analysis and per roleplay session

**JSON schema failure rate** is the share of logical LLM calls where at least one attempt
failed schema validation — counted across all attempts, not only the final one, so calls that
a retry recovered still count as failures. This is the metric's point: a prompt that needs a
second attempt half the time is degrading, even at a 0% user-visible error rate. It is
measurable only because `ai_request_logs` stores one row per attempt (ADR-011); the earlier
one-row-per-call contract made recovered failures invisible.

```sql
SELECT count(DISTINCT attempt_group_id)
         FILTER (WHERE error_code = 'schema_validation_failed')::float
       / count(DISTINCT attempt_group_id) AS schema_failure_rate
FROM ai_request_logs
WHERE prompt_version IS NOT NULL;   -- LLM calls only
```

Latency is per attempt and excludes retry backoff. Cost per analysis and per roleplay session
sums every attempt row, since retries are billed.

## 4. MVP scope

### 4.1 Core loop

```
Describe situation → AI generates 3 English expressions
  → Save to library → Review later → Practice via guided roleplay
```

Each stage must work for v1 to ship (ADR-006).

### 4.2 Screen map (14 screens)

| ID | Screen | Role in the loop |
|----|--------|------------------|
| S01 | Landing | Entry |
| S02 | Try without login | First AI experience |
| S03 | Login / signup | Account (Google / Kakao / email) |
| S03b | Coach selection | Mia / David / Sarah |
| S04 | Home | Main hub |
| S05a | Text input modal | Capture situation |
| S06 | Loading modal | Processing state |
| S07 | Analysis result | Core AI output |
| S08 | Library | Saved expressions |
| S09 | Expression detail | Single expression view |
| S10 | Review | Active recall |
| S11 | Settings | Account / preferences |
| S12 | Guided roleplay | Practice with coach |
| S12b | Roleplay result | Session summary |

Per-screen specs live in docs/screens/sNN.md.

### 4.3 In v1 / Deferred

**In v1**: Try-without-login → AI analysis → forced signup at save → pending save restored; AI analysis (S07); Save and library (S08); Expression detail (S09); Review with active recall (S10); Guided roleplay with selected coach (S12, S12b); Direct coach selection, no AI matching (S03b); Google + Kakao + email login.

**Deferred to v1.1+**: Memo / note on expressions; AI coach auto-matching; Monetization (free / Pro plan separation); Advanced library filters and search; Complex review interval algorithms such as full SM-2 — v1 uses simplified ×1 / ×2 / ×3 multipliers (see docs/screens/s10.md); CTR-based rotation of landing examples — v1 ships random sampling from a fixed seed pool (see data-model.md `landing_examples`); Apple social login — v1 ships Google + Kakao + email only.

## 5. Feature requirements (User Stories)

Acceptance criteria, edge cases, and APIs for each feature live in the corresponding docs/screens/sNN.md.

### 5.1 Try without login

> As a first-time visitor, I want to try the AI analysis without signing up, so that I can experience the core value before creating an account.

Screens: S01 → S02 → S07.

### 5.2 Login & pending save

> As a user who tried PhraseLog without an account and now wants to save, I want to sign up (Google, Kakao, or email) and have my unsaved expression preserved, so that I do not lose what I just generated.

Screens: S07 (save attempt) → S03 → S07 (restored). ADR-005.

### 5.3 AI analysis result (S07) — core

> As a Korean immigrant user, I want to describe a situation in Korean and receive natural English expressions with tone, IPA, Korean pronunciation, pronunciation tip, and cultural tip, so that I can use them next time.

S07 is the unit measured by the S07 mini eval (ADR-003). If S07 fails, the product has no foundation. ADR-001 (pipeline), ADR-003 (eval).

### 5.4 Save expression

> As a user, I want my saved expressions to persist across sessions and devices and appear in my growing library, so that they accumulate as a long-term resource without any absence-penalty mechanic.

ADR-002 (cumulative bookshelf, no streak).

### 5.5 Library

> As a user with saved expressions, I want to browse by recency and search by keyword in Korean or English, so that I can find expressions when needed.

v1 ships chronological list + basic keyword search. Advanced filtering deferred (4.3).

### 5.6 Expression detail

> As a user, I want to view the full detail of one saved expression (original situation, all 3 variants, pronunciation, cultural tip), so that I can refresh my memory.

v1 note: Memo feature excluded (4.3).

### 5.7 Review

> As a user, I want to actively recall English from a Korean situation, evaluate my recall on a 3-button scale (어려움 / 기억남 / 완벽), and have the next review scheduled automatically, so that difficult expressions return sooner.

v1 uses simplified interval model (×1 / ×2 / ×3). Full SM-2 deferred. ADR-002.

### 5.8 Guided roleplay

> As a user, I want to practice a saved expression in a simulated conversation with my coach (voice or text), with brief in-turn feedback and a structured session-end summary, so that the expression becomes usable in real situations.

Split pipeline (ADR-001). Daily limit: 2 sessions. Turn count 3–10, decided by complexity on first call. ADR-003 Tier 2 eval is post-launch.

### 5.9 Coach selection

> As a new user, I want to choose between coach personas (Mia, David, Sarah) by comparing their style, so that practice matches my preferred coaching tone.

v1 note: AI-based auto-matching deferred (4.3).

### 5.10 Settings

> As a user, I want to manage account preferences (profile, coach change, usage status, logout, account deletion with 14-day grace period), so that the app fits how I use it.

## 6. Cross-cutting requirements

Every production AI feature in v1 must implement: structured JSON output with backend schema validation, versioned prompts committed to the repo, cost and latency logging to `ai_request_logs` on every call (success or failure), fallback behavior for schema validation failures and API timeouts/5xx, and model identification in logs (`model_name`, `prompt_version`). See AI_PIPELINE.md.

Eval is tiered per ADR-003: Tier 1 S07 mini eval (W1~3 DoD, regression guard during dev), Tier 2 S12 roleplay eval (post-launch, event-triggered after ~50 real inputs), Tier 3 golden dataset and dashboard (later). See EVAL_PLAN.md.

Data model entities: `users`, `coach_profiles`, `analysis_requests`, `expressions`, `review_cards`, `practice_sessions`, `practice_turns`, `ai_request_logs`. The `ai_request_logs` table is core to production observability. See data-model.md.

API contracts live in docs/api/*.yaml.

## 7. Out of scope (v1)

Real-time translation. Free-form AI conversation as the primary interface. Generic vocabulary or grammar instruction. Multi-language beyond Korean → English. Monetization. Multi-agent autonomous systems. Memo on saved expressions. AI coach auto-matching. Items listed in START_HERE.md "Do not suggest." Revisit only via new ADR.

## 8. Risks and scope-cut rules

### 8.1 Risks

- **Integration risk at W12** (ADR-006). Mitigated by W9~11 prep buffer (ADR-004) and private validation.
- **AI-assisted velocity assumption** (ADR-004). Mid-development velocity check at W6.
- **S07 prompt quality** drives most perceived product value. Mini eval (Tier 1) is the primary regression guard.
- **Audio API device-specific issues** tend to surface late. W9~11 buffer is partly for this.

### 8.2 Scope-cut rules

If W6 shows integrated scope is unachievable, **reduce depth, not loop stages**. Pre-approved fallbacks:

- **Roleplay** → fixed 3-turn flow instead of dynamic 3–10
- **TTS** → on-demand generation only, no pre-caching
- **Character system** → static prompt-personality differentiation only, no progression mechanics
- **Library** → time-ordered list only, no search

Removing a loop stage (Save, Review, or Roleplay entirely) requires a supersedes ADR for ADR-006.

## 9. Open questions

Resolve by W3 unless noted.

1. **v1.1+ deferral list** — ongoing classification before W4 (4.3)
2. **Additional launch criteria** beyond the core set (3.1)
3. **Success metric thresholds** — pre-launch baseline vs post-launch (3.2)
4. **Private validation participants** — count and recruitment method
5. ~~**`current_interval` initial value for newly saved review cards**~~ — Resolved 2026-06-10: immediate first due (`next_review_at = now()`) with `current_interval_days = 1` (data-model.md, docs/screens/s10.md)

## 10. Related documents

- START_HERE.md — entry point for AI collaborators
- PROJECT_CONTEXT.md — product identity, user pain, positioning
- docs/decisions/001-010 — ADRs
- data-model.md — DB schema and ERD
- AI_PIPELINE.md — pipeline detailed spec
- EVAL_PLAN.md — eval system spec
- docs/api/*.yaml — API contracts
- docs/screens/sNN.md — per-screen specs
