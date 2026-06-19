# Exec plan: POST /expressions save backend (#41)

Status: **In progress.** Scope is backend core only. Login/BFF pending-save
integration remains blocked by the still-unfinished auth route-handler flow.

## Goal

Implement issue #41's Spring Boot core endpoint:

- `POST /api/v1/expressions`
- Requires an authenticated `user_id` principal.
- Saves one owned S07 analysis into:
  - one `expressions` row (`source_type = analysis`)
  - three `expression_variants` rows
  - one `review_cards` row with `next_review_at = now()` and
    `current_interval_days = 1`
- Sets `expressions.selected_variant_id` from `selected_variant_order`
  (default `1`).
- Duplicate save for the same `analysis_request_id` returns `409` with the
  existing `Expression` response body.

## Source specs

- GitHub issue #41: `POST /expressions`, rows created, duplicate behavior,
  ownership validation.
- `docs/api/openapi.yaml`: `POST /expressions`, `Expression`,
  `ExpressionVariant`, `Idempotency-Key`.
- `docs/data-model.md`: `expressions`, `expression_variants`, `review_cards`.
- `docs/screens/s07.md`: authenticated save and duplicate-save UI behavior.
- `docs/screens/s10.md`: review card is immediately due.
- `docs/auth.md` and ADR-010: Spring Boot receives a verified
  `InternalAuthPrincipal` from `X-Internal-Auth`.

## Scope boundary

This branch does **not** complete the browser-visible login save flow:

- no Next.js route handler wiring;
- no NextAuth session read;
- no pre-signup `pending_save` replay after login;
- no anonymous `session_token` analysis claim.

Reason: current #20 implementation enforces exactly one of `user_id` or
`session_token`, while `architecture.md`'s pending-save section still describes
passing both the authenticated user and original anonymous session. That contract
needs a separate decision before backend code can safely claim anonymous analyses.

For #41 backend core, `POST /expressions` accepts only authenticated `user_id`
analysis rows where `analysis_requests.user_id` matches the caller.

## Design

### Request validation

- Read `InternalAuthPrincipal` from the request attribute, matching the existing
  `AnalysisController` pattern.
- Reject missing principal as `401 internal_auth_invalid`.
- Reject `session_token` principals as `401 internal_auth_invalid`; saving is
  authenticated-only in this scoped implementation.
- Require a UUID `Idempotency-Key` header. The value is validated for contract
  consistency, but duplicate behavior is keyed by `analysis_request_id` because
  the schema has no save idempotency table/column and the product rule says one
  save per analysis.
- Require UUID `analysis_request_id`.
- Default `selected_variant_order` to `1`; accept only `1..3`.

### Save flow

1. Find existing expression by `analysis_request_id` for this user. If present,
   return it with conflict status.
2. Find the analysis row by `analysis_request_id` and authenticated user owner.
   Missing or not owned returns 404.
3. Read exactly three entries from `analysis_requests.output_json.expressions`.
4. Insert the `expressions` parent row.
5. Insert three `expression_variants` rows from the analysis output.
6. Update `expressions.selected_variant_id` to the selected variant's id.
7. Insert one `review_cards` row with immediate due scheduling.
8. Return the full `Expression` response shape.

The repository should run the creation in one Spring transaction.

### Duplicate and idempotency posture

There is no unique index on `expressions.analysis_request_id` in the current schema.
Adding one would be a DB migration and needs owner approval per `SECURITY.md`.
This implementation therefore prevents normal duplicate saves by checking for an
existing expression first and returning `409` with that expression body. True
concurrent duplicate hardening is a follow-up migration decision, not hidden scope.

### Response shape

Return:

- `id`
- `source_type`
- `analysis_request_id`
- `practice_session_id = null`
- `original_situation`
- `selected_variant_id`
- `variants` (three persisted rows)
- `review_card_id`
- `next_review_at`
- `created_at`

## Files expected to change

Backend production:

- `backend/src/main/java/com/phraselog/expression/controller/ExpressionController.java`
- `backend/src/main/java/com/phraselog/expression/service/ExpressionService.java`
- `backend/src/main/java/com/phraselog/expression/repository/ExpressionRepository.java`
- `backend/src/main/java/com/phraselog/expression/repository/JdbcExpressionRepository.java`
- `backend/src/main/java/com/phraselog/expression/repository/UnavailableExpressionRepository.java`
- `backend/src/main/java/com/phraselog/expression/config/ExpressionConfiguration.java`
- `backend/src/main/java/com/phraselog/expression/dto/*`

Backend tests:

- `backend/src/test/java/com/phraselog/expression/service/ExpressionServiceTests.java`
- `backend/src/test/java/com/phraselog/expression/controller/ExpressionControllerTests.java`
- `backend/src/test/java/com/phraselog/expression/repository/JdbcExpressionRepositoryTests.java`

Docs:

- This exec plan.

## Test plan

TDD sequence:

1. `ExpressionServiceTests`
   - authenticated save creates an `ExpressionResponse` with 3 variants and an
     immediately due review card;
   - selected variant defaults to order 1;
   - selected variant order 2 sets `selected_variant_id` to variant 2;
   - duplicate save throws a conflict carrying the existing expression;
   - session-token principal is rejected;
   - missing/not-owned analysis returns 404.
2. `ExpressionControllerTests`
   - `POST /api/v1/expressions` returns 201 on first save;
   - duplicate conflict renders 409 with the existing expression body;
   - missing principal returns 401.
3. `JdbcExpressionRepositoryTests` with Testcontainers PostgreSQL
   - real transaction inserts rows in all three tables;
   - review card has `current_interval_days = 1` and `next_review_at <= now`;
   - duplicate lookup returns existing expression.

## Verification commands

- `git status --short --branch`
- `cd backend && .\gradlew.bat test --tests "com.phraselog.expression.*"`
- `cd backend && .\gradlew.bat test`
- `git diff --check`

If Docker is unavailable, the JDBC Testcontainers tests may be skipped by the
existing `disabledWithoutDocker` posture. Report that clearly; do not replace
PostgreSQL coverage with H2.

## Out of scope / blocked

- Next.js BFF route handlers and browser save wiring.
- Pending-save replay after login.
- Claiming anonymous `session_token` analyses for a newly authenticated user.
- DB migration for `UNIQUE (analysis_request_id)` or save idempotency storage.
- S08/S09/S10 read endpoints.

