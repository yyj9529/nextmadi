# Architecture

Source of truth for PhraseLog v1 system architecture: frontend, backend, persistence, external services, networking, deployment, and observability. AI pipeline specifics (STT, LLM routing, TTS) live in `AI_PIPELINE.md`. Database schema lives in `data-model.md`. Per-screen behavior lives in `docs/screens/sNN.md`. This document defines the system shape that hosts all of them.

## System overview

```
                    ┌──────────────────────────────────────────────────┐
                    │  Client (browser, PWA-installable)               │
                    │  ┌─────────────┐   ┌─────────────────────────┐  │
                    │  │ Next.js UI  │   │ NextAuth session client │  │
                    │  └─────────────┘   └─────────────────────────┘  │
                    └────────────────┬─────────────────────────────────┘
                                     │ HTTPS
                                     ▼
                    ┌────────────────────────────────────────────────┐
                    │  Next.js on Vercel                             │
                    │  ┌────────────────┐  ┌──────────────────────┐  │
                    │  │ Pages / app dir│  │ NextAuth route handler│  │
                    │  └────────────────┘  └──────────────────────┘  │
                    └────────────────┬───────────────────────────────┘
                                     │ HTTPS (BFF: signed internal token — see docs/auth.md)
                                     ▼
                    ┌────────────────────────────────────────────────┐
                    │  Spring Boot on AWS EC2                        │
                    │  ┌──────────┐  ┌──────────┐  ┌──────────────┐ │
                    │  │Controller│→ │ Service  │→ │ Repository    │ │
                    │  └──────────┘  └──────────┘  │ (JPA/QueryDSL)│ │
                    │                              └───────┬───────┘ │
                    │  AI orchestration calls              │         │
                    │      │                               │         │
                    └──────┼───────────────────────────────┼─────────┘
                           │                               │
        ┌──────────────────┼──────────────────┐            │
        ▼                  ▼                  ▼            ▼
   ┌──────────┐     ┌──────────┐     ┌──────────────┐  ┌─────────────┐
   │ Whisper  │     │  Claude  │     │  OpenAI TTS  │  │ RDS Postgres│
   │ (OpenAI) │     │(Anthropic)│    │              │  │ + S3 (audio) │
   └──────────┘     └──────────┘     └──────────────┘  └─────────────┘
```

OAuth identity providers (Google, Kakao) are invoked by NextAuth on the Next.js side and not by the Spring Boot backend directly. Email magic-link is also a NextAuth provider.

## Frontend layer

### Next.js on Vercel

Frontend framework. App-router-based pages map directly to the screen IDs in `docs/screens/`:

| URL                                 | Screen   |
|-------------------------------------|----------|
| `/`                                 | S01 Landing |
| `/try`                              | S02 Try without login |
| `/login`                            | S03 Login |
| `/welcome/coach`                    | S03b Coach selection |
| `/home`                             | S04 Home |
| `/save/result/:analysis_request_id` | S07 Analysis result |
| `/library`                          | S08 Library |
| `/expression/:expression_id`        | S09 Expression detail |
| `/review`                           | S10 Review |
| `/settings`                         | S11 Settings |
| `/practice/:session_id`             | S12 Roleplay |
| `/practice/:session_id/result`      | S12b Roleplay result |

Server components are used for initial data fetches (the `화면 진입시 실행되는 쿼리` in the screen-queries document). Client components handle interactive state (sessionStorage for `pending_save`, mic recording, etc.).

Vercel auto-deploys on push to `main`. Hobby tier covers the v1 **non-commercial** private-validation phase only — Vercel's Fair Use restricts Hobby to non-commercial/personal use (verified 2026-06-05, vercel.com/docs/limits/fair-use-guidelines). Switch to Pro the moment any commercial use begins: paid beta, ads, payments, or serving paying customers (per PRD §4.3).

### PWA scope (v1)

- **Manifest** for installability on iOS and Android home screens
- **Service worker** for cached UI shell — first-paint without network round trip on revisit
- **sessionStorage** for `pending_save` and `draft_input` (5-minute scratch persistence)

Explicitly **not** in v1 PWA scope:

- Offline mode for AI features — every AI call requires network; cached UI shell exists only to render faster
- Push notifications — deferred to v1.5+ (PRD §4.3, PPT v2.2 slide 17)
- Background sync — no use case in v1

### NextAuth on Next.js

OAuth (Google, Kakao) and email magic-link flow lives entirely on the Next.js side. The browser never calls Spring Boot directly (BFF, ADR-010): Next.js route handlers validate the NextAuth session server-side and proxy to Spring Boot with a signed internal token. The NextAuth session cookie itself is never sent to Spring Boot.

## Backend layer

### Spring Boot on EC2

REST API server. Three-layer organization (Controller → Service → Repository) by feature domain:

```
src/main/java/com/phraselog/
├── auth/         — internal JWS token verification (BFF, see docs/auth.md), user_auth_identities linking
├── analysis/     — S07 analysis (calls AI_PIPELINE.md s07_analysis)
├── expression/   — Save / Library / Detail / soft delete
├── review/       — review_cards updates, review_attempts history
├── practice/     — Roleplay sessions, turn handling, result generation
├── coach/        — coach_profiles seed and selection
├── usage/        — anonymous_analysis_usage UPSERT and daily caps
├── tts/          — tts_audio_cache lookup and population
├── logging/      — ai_request_logs writer (shared by all AI calls)
└── common/       — config, error handlers, JSON schema validation
```

### REST API conventions

- All endpoints under `/api/v1/...`
- Resource-oriented routes (`/api/v1/expressions/:id`, `/api/v1/practice/:session_id/turns`)
- JSON request/response, snake_case field names
- Pagination via `limit` + `cursor` (next_cursor returned in response), not page numbers
- All authenticated endpoints require a valid `X-Internal-Auth` JWS token minted by the Next.js BFF (docs/auth.md, ADR-010)
- Idempotency for unsafe operations via `Idempotency-Key` header (Save Expression, Start Roleplay Session — prevents duplicate rows on retry)
- API contracts live in `docs/api/*.yaml` (OpenAPI 3.x)

### Spring Boot configuration

- **Java 21 + Spring Boot 3.x** (LTS combination current as of 2026)
- **Tomcat** as embedded servlet container
- **HikariCP** for RDS connection pool — sized for v1 EC2 instance (start at maximumPoolSize=10, tune from CloudWatch RDS connection metrics)
- **JPA + Hibernate** for ORM, **QueryDSL** for type-safe complex queries (S04 home aggregations, S08 library search, S10 review queue)
- **Flyway** for schema migrations — versioned SQL files under `src/main/resources/db/migration/`, executed at app startup

## Persistence layer

### RDS PostgreSQL

Schema source of truth: `data-model.md`. Connection from EC2 via VPC private subnet (RDS not publicly accessible).

Instance sizing for v1: `db.t4g.small` or equivalent. Single-AZ to start, multi-AZ when uptime requirements appear.

Automated daily backups via RDS snapshot, retention 7 days. Point-in-time recovery enabled.

### S3 (audio cache)

Single bucket `phraselog-audio-{env}` with prefix structure:

```
tts/{voice_id}/{sha256(text)}.mp3
```

Audio access from client via signed URLs (presigned GET, 7-day expiry). Spring Boot generates signed URLs at TTS playback request time; raw bucket is not publicly accessible.

Lifecycle policy: objects with no read access in 180 days are transitioned to Glacier (cost optimization). TBD before launch.

### Migration strategy

Flyway. Migration files versioned: `V001__init.sql`, `V002__add_user_auth_identities.sql`, etc. Applied automatically on Spring Boot startup. Schema order follows the dependency list in `data-model.md`.

Rollback strategy: forward-only migrations (no DOWN scripts). Recovery via RDS point-in-time restore or compensating migration.

## External services

| Service          | Purpose                       | SLA assumption        |
|------------------|-------------------------------|-----------------------|
| Anthropic API    | Sonnet 4.6, Haiku 4.5         | 99.9% target          |
| OpenAI API       | Whisper STT, TTS              | 99.9% target          |
| Google OAuth     | Authentication                | 99.9% baseline        |
| Kakao OAuth      | Authentication                | 99% baseline (lower)  |
| SMTP (email)     | Magic-link delivery           | provider-dependent    |

Failure handling and retry policies for AI/STT/TTS calls live in `AI_PIPELINE.md`. Auth provider failures route the user to the alternative provider screen.

## Authentication and authorization

**Decided: BFF (Backend-for-Frontend) handoff — see `docs/auth.md` and ADR-010.**
The browser never calls Spring Boot directly. All API traffic flows
browser -> Next.js route handler -> Spring Boot.

### Flow

1. User initiates login on `/login` (S03)
2. NextAuth handles OAuth dance with chosen provider (Google / Kakao), or sends magic link (email)
3. On callback, NextAuth creates or finds a `users` row and links the provider via `user_auth_identities` (provider, provider_user_id)
4. NextAuth issues its default encrypted (JWE) session, stored in an HTTP-only cookie (`__Host-` prefix, `Secure`, `SameSite=Lax`)
5. Browser calls Next.js route handlers (`/api/*`). The handler validates the NextAuth session server-side and resolves authenticated `user_id` or anonymous `session_token`
6. The handler calls Spring Boot over HTTPS with a short-lived signed (JWS) internal token in the `X-Internal-Auth` header. The token binds `user_id` or anonymous `session_token` as claims, signed with `INTERNAL_AUTH_SECRET` shared only between Next.js server and Spring Boot
7. Spring Boot verifies the JWS signature and expiry, extracts claims, and performs resource-level authorization (row ownership checks) as usual

Why not network-level trust: Vercel function egress IPs are not fixed, so an IP
allowlist in front of the ALB cannot substitute for the signed token. Verify
current Vercel egress behavior against official docs at W4 before relying on
any allowlist.

### CSRF and cookie security

The BFF authenticates the browser via cookies, so CSRF defenses apply to the
Next.js layer: NextAuth's built-in CSRF protection covers its own routes;
custom route handlers use `SameSite=Lax` plus an Origin/Referer check for
state-changing requests. Spring Boot needs no browser-cookie CSRF handling
because it never sees a browser cookie.

### Session lifetime

- **NextAuth session (browser -> Next.js):** encrypted JWE cookie; lifetime configured in NextAuth (target 7 days), sliding renewal on activity.
- **Internal token (Next.js -> Spring Boot):** minted per request, short-lived (minutes). No refresh flow, no revocation list; expiry bounds exposure.
- Logout: client session cookie cleared by NextAuth; internal tokens expire on their own.

### Consequence for the API contract

`docs/api/openapi.yaml` describes the internal Next.js BFF -> Spring Boot
contract, not a browser-facing API. Its security scheme is the internal token,
not a browser-held token. Browser-facing paths are Next.js route handlers
mirroring these operations.

### Pending save flow (PRD section 5.2)

Pre-signup users on S07 who click Save:

1. Client stores `analysis_request_id` + variant selection in sessionStorage under key `pending_save`
2. Client redirects to `/login`
3. After successful auth + S03b coach selection, the client reads `pending_save` from sessionStorage
4. Client posts the save request to the Next.js route handler
5. Next.js calls Spring Boot with `X-Internal-Auth` claims for the authenticated `user_id` and the original anonymous `session_token`
6. Backend resolves `analysis_request_id` by matching either `user_id` (if already claimed) or `session_token` (anonymous -> claim now)
7. sessionStorage `pending_save` is cleared on success

## Networking and security

### VPC topology

- Single VPC, two availability zones (for future multi-AZ RDS)
- Public subnets: EC2 (Spring Boot) instances + ALB
- Private subnets: RDS, no public route
- Security group rules: EC2 ↔ RDS port 5432 only; ALB ↔ EC2 port 8080 only; internet ↔ ALB ports 80/443

### TLS

- Vercel automatically issues TLS for Next.js
- ALB terminates TLS for Spring Boot, uses ACM-issued certificate
- All client ↔ server traffic HTTPS
- S3 signed URLs use HTTPS only

### Secrets

- Stored in **AWS Secrets Manager**: Anthropic API key, OpenAI API key, database credentials, OAuth client secrets, and the internal-token signing secret (`INTERNAL_AUTH_SECRET`, shared Next.js -> Spring Boot)
- Spring Boot reads secrets at startup via AWS SDK using IAM role attached to EC2 instance
- Vercel environment variables: NextAuth secret (`AUTH_SECRET`), OAuth client IDs and secrets, backend base URL, and the internal-token signing secret (`INTERNAL_AUTH_SECRET`, shared Next.js -> Spring Boot)
- No secrets in source code or `.env` files committed to git

### CORS

- Product traffic avoids browser-to-Spring CORS because the browser calls only the Next.js origin.
- Spring Boot accepts server-to-server requests from the Next.js BFF carrying `X-Internal-Auth`.
- If a future browser-facing or external API is added, define a new CORS policy with a new ADR.

## Deployment topology

### Environments

| Environment | Frontend       | Backend                  | DB                        |
|-------------|----------------|--------------------------|---------------------------|
| Local dev   | `next dev`     | Spring Boot via Gradle   | Local Postgres (Docker)   |
| Production  | Vercel         | EC2 (t4g.small or up)    | RDS db.t4g.small          |

No separate staging environment in v1. Add when test cycles outgrow local dev (likely v1.1+).

### Deploy mechanism

- **Frontend**: `git push origin main` → Vercel webhook → automatic build + deploy. Preview deploys for every PR.
- **Backend (v1)**: GitHub Actions builds JAR, uploads to S3 artifact bucket, single EC2 instance pulls and restarts via systemd. Brief downtime during restart (a few seconds) — acceptable at v1 traffic. Exact workflow: `.github/workflows/backend-deploy.yml` (forthcoming).
- **Backend evolution (v1.1+)**: When uptime requirements exceed v1 tolerance, evolve to two-instance ALB target group with rolling restart. Not built upfront — added when actual downtime complaints or contractual SLAs appear.

The v1 choice is deliberate: solo-developer MVPs gain little from zero-downtime deploy infrastructure before there is a user base whose experience degrades during restarts. Pre-building the rolling-restart pattern is the kind of pre-spec'd operational sophistication that delays shipping without measurable benefit at v1 scale.

### Rollback

- Frontend: Vercel keeps history; rollback via dashboard or `vercel rollback` CLI
- Backend: previous JAR remains in S3 artifact bucket; redeploy by referencing previous artifact version

## Scheduled jobs

Consolidates the daily jobs that `data-model.md` references individually. All
run as Spring `@Scheduled` tasks inside the existing backend process. At v1
single-instance scale, a separate scheduler adds operational surface without
clear benefit. If the backend scales past one instance, add `@SchedulerLock`
(ShedLock) or move to EventBridge; not a v1 concern.

| Job | Action | Source of truth |
|---|---|---|
| Account hard-delete | Hard-delete `users` rows where `scheduled_deletion_at < now()` (cascades per FK rules). | `data-model.md` users; `docs/screens/s11.md` |
| Anonymous usage cleanup | Delete `anonymous_analysis_usage` rows older than the retention window (default 30 days, retention TBD). | `data-model.md` anonymous_analysis_usage |
| TTS cache expiry | Delete expired `tts_audio_cache` rows and corresponding S3 objects only if the expiry policy lands on the 90-day option. No-expiry option means no job. | `data-model.md` tts_audio_cache |

Operational requirements for every job:

- Idempotent: safe to re-run after a missed or duplicate trigger.
- Logged: start, row counts affected, duration, failure cause -> CloudWatch Logs.
- Scheduled at low-traffic UTC hours; exact times decided at implementation.
- Failure does not page in v1; daily jobs tolerate a one-day delay. Add a CloudWatch alarm on two consecutive failures.

## Observability

Three layers:

1. **Application logs** — Spring Boot logback → stdout → CloudWatch Logs (via CloudWatch agent on EC2). Structured JSON with `request_correlation_id` field for tracing user actions across services.
2. **AI observability** — `ai_request_logs` table (see `data-model.md` and `AI_PIPELINE.md`). Queryable from any SQL client for cost/latency/error analysis.
3. **Infrastructure metrics** — CloudWatch metrics for EC2 (CPU, memory), RDS (connections, CPU, storage), S3 (request count, bytes). Default retention.

### Alerts (v1 minimum)

Infrastructure and error rate:

- EC2 CPU > 80% for 5 minutes
- RDS storage > 80% capacity
- RDS CPU > 80% for 5 minutes
- 5xx response rate > 1% over 10 minutes
- `ai_request_logs.status = 'error'` rate > 5% in any 10-minute window per `feature_name`

AI cost (added because runaway prompt loops or abusive user patterns can produce four-figure unexpected bills within hours — this is a documented production failure mode in LLM applications, not a theoretical risk):

- **Daily total AI cost** — sum of `ai_request_logs.estimated_cost_usd` across all rows for current day > $T_daily threshold
- **Per-user daily AI cost** — same sum filtered by `user_id` > $T_per_user threshold (detects single-user runaway or abuse)
- **7-day cost trend deviation** — today's running total > 1.5× 7-day rolling median by 6 PM local time

Thresholds (`$T_daily`, `$T_per_user`) set during W1-3 using `AI_PIPELINE.md` per-action estimates × expected daily volume × 2 safety factor. Tuned from W4-8 actuals.

Alert destination: email + Slack webhook (Slack provisioning TBD).

Sentry or Datadog integration deferred to v1.1+ (CloudWatch + SQL queries against `ai_request_logs` are sufficient at v1 traffic volumes).

## CI/CD

GitHub Actions workflows under `.github/workflows/`:

| Workflow          | Trigger          | Purpose                                  |
|-------------------|------------------|------------------------------------------|
| `lint-test.yml`   | PR opened/push   | Lint (ESLint, Spotless), tests, build    |
| `eval.yml`        | PR touching prompts or eval | Run S07 Tier 1 mini eval, comment results on PR (per ADR-003) |
| `backend-deploy.yml` | Push to main (backend paths) | Build JAR, upload to S3, deploy to EC2 |
| (Vercel)          | Push to main (frontend paths) | Automatic via Vercel integration |

`eval.yml` runs `eval/s07-analysis/` cases per ADR-003 Tier 1, computes pass rate vs baseline, and posts a PR comment summary so prompt or model changes that regress quality are caught before merge.

## Open questions

Resolve before launch:

1. **PWA service worker caching strategy** — exact cache scope (UI shell only? icons/fonts?). Affects bundle size and update behavior.
2. **`INTERNAL_AUTH_SECRET` rotation** — manual annual rotation acceptable for v1 (tokens are minutes-lived, so a leaked old secret ages out fast once rotated). Document the two-secret overlap procedure before first rotation.
3. **S3 lifecycle policy** — 180-day Glacier transition is a default; confirm based on TTS reuse patterns.
4. **Custom domain vs Vercel default** — `phraselog.app` (or similar) requires DNS setup. Vercel-provided URL works for v1.
5. **Slack webhook for alerts** — workspace provisioning needed.
6. **AI cost alert thresholds** — `$T_daily` and `$T_per_user` values. Initial guess from `AI_PIPELINE.md` per-action estimates × expected daily volume × 2 safety factor; finalize during W1-3.

## Related

- ADR-001 — Split AI pipeline. Specifies the external services this architecture hosts.
- ADR-005 — Stack choice rationale (Spring Boot + Next.js + RDS PostgreSQL).
- ADR-007 — Documentation structure. This file is one of the planning-phase source-of-truth documents.
- `AI_PIPELINE.md` — AI stack mechanics; this document explains where the AI stack runs.
- `data-model.md` — Schema for RDS Postgres referenced throughout this document.
- `EVAL_PLAN.md` — Detailed eval system that `eval.yml` workflow runs.
- `docs/api/*.yaml` — REST API contracts hosted by the Spring Boot layer.
- `docs/screens/sNN.md` — Per-screen behavior implemented on this architecture.
- `.github/workflows/` (forthcoming) — Actual CI/CD workflow files.
- PRD §4.2 — Screen map this architecture serves.
- PRD §6 Cross-cutting requirements — Observability requirements satisfied by `ai_request_logs` and CloudWatch.
