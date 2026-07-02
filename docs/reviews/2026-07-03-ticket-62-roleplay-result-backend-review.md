# Review Readiness - Roleplay Result Backend (#62)

- Target: `feat-62-roleplay-result-backend` working diff
- Reviewer: Codex
- Author: Codex
- Review status: implementation self-check; independent review is still required before merge
- Date: 2026-07-03
- Verdict: **PASS for backend core, with handoff caveat.** The Spring backend now supports cached S12b result generation and roleplay-result expression saves. Next.js BFF/S12b mock-to-real wiring remains out of scope for this backend ticket.

## Gate 1 - Spec Compliance

- [x] `roleplay_result_v1` requires exactly 3 `recommended_expressions`.
- [x] `POST /practice/sessions/{id}/result` requires an authenticated user, returns 404 for missing/not-owned, 409 for non-completed sessions, reuses cached `result_json`, and stores only successful schema-valid LLM output.
- [x] `GET /practice/sessions/{id}` exposes nullable `result_json`.
- [x] `POST /practice/sessions/{id}/save-expression` requires authenticated user, `Idempotency-Key`, cached result, completed owned session, and `recommended_expression_index` 0..2.
- [x] Save creates a `roleplay_result` expression with 3 variants, selected order `index + 1`, source practice session, idempotency metadata, and an immediately due review card.
- [x] OpenAPI, AI pipeline docs, prompt, data model, schema, and migration were updated.

Findings: No blocking spec gaps found in the backend scope. Frontend/BFF wiring is a follow-up and was not browser-tested here.

## Gate 2 - Code Quality

- [x] Repository methods keep ownership checks scoped by `user_id`.
- [x] Provider timeout/failure leaves `practice_sessions.result_json` null, allowing retry.
- [x] Duplicate idempotency and active same-index saves are protected by partial unique indexes and service-level re-reads.
- [x] Tests cover schema validation, service behavior, controller status mapping, repository persistence, migration structure, and Testcontainers-backed unique indexes.
- [x] No secrets or raw credential material added.

Verification:

- `cd backend && .\gradlew.bat test --tests "com.phraselog.practice.*"` - pass
- `cd backend && .\gradlew.bat test --tests "com.phraselog.expression.*"` - pass
- `cd backend && .\gradlew.bat test --tests "com.phraselog.ai.client.service.JsonSchemaValidatorTests"` - pass
- `cd backend && .\gradlew.bat cleanTest test --rerun-tasks` - pass
- `cd backend && .\gradlew.bat spotlessJavaCheck` - pass
- `git diff --check` - pass, with CRLF normalization warnings on existing docs/prompts

## Notes For The Owner

This adds migration `V006__roleplay_result_expression_idempotency.sql`; run it only through the approved migration path. The implementation does not call live Anthropic during tests and does not implement the Next.js S12b real-data wiring.
