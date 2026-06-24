# Decision Backlog - Resolve Before The Blocked Story Starts

Last updated: 2026-06-25 (S08 virtualization resolved)
Status: P1/P2 open except resolved items below. Owner decides; AI agents do not resolve these unilaterally.

Every "TBD before W4" scattered across the specs, consolidated. Each item lists
the source doc and the story it blocks. Convert each row into a `type:spike`
issue and link it as a blocker on the story. A decision is done when the source
doc is updated and, where applicable, `openapi.yaml` / `data-model.md` reflect
it.

Priority key: **P0** blocks an entire epic or multiple stories. **P1** blocks one
story. **P2** can be decided during implementation without rework risk.

## P0 - All Resolved 2026-06-10

| # | Decision | Resolution |
|---|---|---|
| 1 | Auth handoff | **BFF** with signed internal token (user_id-bound JWS), cookie hardening, CSRF at Next.js layer. `openapi.yaml` redefined as internal contract. ADR-010. |
| 2 | S02 voice submission | **Two-step**: `POST /transcriptions` (STT) -> user confirms/edits -> `POST /analysis` (text-only JSON). S12 turns stay one-shot multipart. |
| 3 | Review interval initial | **Immediate due + base interval 1**: `next_review_at = now()`, `current_interval_days = 1`. Interval 0 rejected because 0 x 2 = 0 creates permanently-due cards. |

## S03 Auth P1 - Resolved 2026-06-11

| # | Decision | Resolution |
|---|---|---|
| 4 | Account linking policy (same email, different provider) | **No automatic same-email OAuth linking while signed out.** Existing active email + unlinked Google/Kakao identity shows account-linking guidance; email magic-link can sign in by verified mailbox ownership. Source: `docs/screens/s03.md`, `docs/data-model.md`. |
| 5 | Legal docs paths + actual ToS/Privacy Policy content | **Paths are `/terms` and `/privacy`.** Before W12 launch, both pages need owner-approved actual content; non-production placeholders may exist only as route scaffolding and are not shippable. Source: `docs/screens/s03.md`, `docs/PRD.md`. |
| 15 | SMTP provider for email magic link | **Amazon SES SMTP via Auth.js Nodemailer provider.** SES setup requires verified sender identity, SMTP credentials, and production access before public launch. Source: `docs/architecture.md`. |

## P1 - Decide Before The Blocked Story Enters A Sprint

| # | Decision | Source | Blocks |
|---|---|---|---|
| 6 | Whether `abandoned` roleplay sessions count toward the daily cap of 2. | `docs/screens/s12.md`; `docs/data-model.md` | S12 session lifecycle story |
| 7 | S12 entry gate destination when user has zero saved expressions. | `docs/screens/s12.md` | S12 entry story |
| 8 | S07 saved-then-soft-deleted revisit behavior: re-create / un-delete / restore CTA. | `docs/screens/s07.md`; `docs/screens/s12b.md` | S07 save story; S12b save story |
| 9 | S03b coach voice samples: ship in v1 or defer; if ship, fix the sample phrase text. | `docs/screens/s03b.md` | S03b story; TTS cost |
| 10 | S04 daily-cap blocked UI: inline message vs modal. | `docs/screens/s04.md`; `docs/screens/s05a.md` | S04 story; S05a story |
| 11 | Bookshelf milestone trigger + animation. | `docs/screens/s04.md` | S04 story |
| 12 | S06 retry idempotency: confirm client re-sends the same `Idempotency-Key` and backend dedupes. | `docs/screens/s06.md`; `docs/api/openapi.yaml` | S06 story; `/analysis` backend story |
| 13 | `anonymous_analysis_usage` retention window (default 30 days; confirm). | `docs/data-model.md` | Cleanup job story |
| 14 | `tts_audio_cache` expiry: no expiry vs 90 days. | `docs/data-model.md` | TTS cache story; jobs story |

## P2 - Decide During Implementation

| # | Decision | Source | Blocks |
|---|---|---|---|
| 16 | S01 example tap: pre-fill `/try` vs plain navigation. | `docs/screens/s01.md` | S01 story |
| 17 | S11 empty nickname: allow-and-clear vs require 1 char. | `docs/screens/s11.md` | S11 story |
| 19 | STT confidence retry threshold; tune after transcription model and real audio are verified. | `docs/AI_PIPELINE.md` | Post-launch tuning |

## P2 - Resolved During Implementation

| # | Decision | Resolution |
|---|---|---|
| 18 | S08 list virtualization library. | **No external library.** S08 uses dependency-free fixed-row windowing in the Next.js screen (`getVirtualWindow` + `LibraryExperience`) to keep the 500+ item case within the existing dependency policy. Source updated: `docs/screens/s08.md`. |

## Not Decisions, But Unticketed Work

- Scheduled jobs implementation (see `docs/architecture.md` "Scheduled jobs").
- Seed data: `coach_profiles` 3 rows + `landing_examples` content authoring.
- Coach illustration assets for Mia, David, and Sarah.
- AWS provisioning: VPC, ALB, ACM cert, domain, RDS, S3 buckets.
- Slack webhook provisioning for alerts.
- ToS / Privacy Policy authoring for `/terms` and `/privacy` before W12 launch.
