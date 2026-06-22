# Exec plan: Pending Save — pre-signup save preservation / attribution (#42)

Status: **In progress.** Branch `feat/42-pending-save`.

## Goal

Implement the pending-save flow so a pre-signup user who taps Save on S07 has the
analysis attributed to their account after they sign up (PRD 5.2, parent epic #44).

- S07 pre-signup Save → write `pending_save` to `sessionStorage` → `/login`.
- After auth (+ S03b coach selection if not onboarded) → detect `pending_save` →
  auto-save without another tap → toast → clear `sessionStorage` → return to the S07
  saved state.
- Backend safely claims the anonymous `analysis_requests` row for the authenticated
  `user_id` only when the original anonymous `session_token` matches.
- Mismatch / expired token → 404 → apology copy + `/home` CTA.
- Permission test proving another user/session cannot hijack a pending save.

## Two decisions taken with the owner (2026-06-21)

1. **Claim contract: `session_token` travels in the `POST /expressions` request body,
   not in the internal JWS.** The internal auth token (`X-Internal-Auth`) is unchanged —
   it still carries exactly one of `user_id` / `session_token` (the XOR invariant in
   `InternalAuthPrincipal` and `InternalAuthVerifier` is a security property other code
   relies on). The claim therefore arrives as: authenticated `user_id` in the verified
   principal **plus** the original anonymous `session_token` as an explicit body field.
   This is the smallest explicit contract change and, crucially, does **not** modify
   internal-auth behavior — so it avoids the `SECURITY.md` "change to internal auth
   handling" approval gate. Extending the JWS to carry both was rejected for that reason.

2. **The BFF reads the original anonymous `session_token` from an httpOnly cookie**,
   server-side, not from JS-reachable storage. `pending_save` stays exactly
   `{ analysis_request_id, selected_variant_order }` as the issue specifies; the token is
   never exposed to the browser bundle. Consequence: the cookie is set by the anonymous
   S02 analysis BFF flow, which is **still mock / unbuilt** (the whole `/try` →
   `POST /analysis` path is mock today). So the *live end-to-end* claim is **blocked**
   until that flow ships and sets the cookie. Everything else — the backend claim
   contract + logic + tests, the typed `pending_save` helper, the redirect logic, the
   BFF route + Spring client — is delivered and unit/integration-tested now.

## Spec conflict resolved

`docs/screens/s07.md` (US3 AC4) says the pending save returns to the **S07 saved state**.
`docs/screens/s03.md` (AC3) said "resume save flow, then `/home`". Issue #42 and S07 win:
after the auto-save the user lands back on the S07 saved state, not `/home`. `s03.md` is
updated to match. (`/home` remains the destination only for the 404 apology path.)

## Scope boundary

In scope: backend claim contract + logic + tests; frontend `pending_save` helper +
post-auth redirect logic + tests; S07 Save wiring (pre-signup write + navigate); BFF
`POST /api/expressions` route + Spring save client; doc updates (openapi, s03).

Out of scope / not touched: S08/S09/S10 read/detail/review endpoints; the anonymous S02
analysis BFF flow and the cookie that sets the anon session token (separate ticket); any
DB migration (the `analysis_requests.user_id` / `session_token` columns and the claim
semantics already exist per `data-model.md`); internal-auth / JWS behavior.

## Backend design

`POST /api/v1/expressions` request body gains optional `session_token` (the original
anonymous claim token). Save stays authenticated-only: a session-token-only principal is
still rejected 401. Resolution order in `ExpressionService.create`:

1. Existing expression for `(analysis_request_id, user_id)` → 409 duplicate (unchanged).
2. Analysis already owned by this `user_id` → proceed (unchanged path).
3. Else, if a `session_token` claim is present → atomic claim:
   `UPDATE analysis_requests SET user_id = ?, session_token = NULL
    WHERE id = ? AND user_id IS NULL AND session_token = ? RETURNING ...`.
   1 row → proceed with the now-owned row. 0 rows (mismatch / expired / already claimed
   / not anonymous) → 404.
4. Else → 404.

The claim `UPDATE` and the expression/variants/review-card inserts run in **one
transaction** (`@Transactional` on the service method, joined by the repository's existing
`@Transactional`), so a failed save rolls the claim back.

Why it is hijack-proof: the `WHERE user_id IS NULL AND session_token = ?` predicate means
a caller with a wrong or guessed token updates 0 rows → 404, and a row already owned by
anyone can never be re-claimed (`user_id IS NULL` fails). Possession of the *matching*
anonymous token is the legitimate anonymous credential by design.

### Files (backend)

- `expression/dto/CreateExpressionRequest.java` — add `session_token`.
- `analysis/repository/AnalysisRepository.java` — add `claimAnonymousAnalysis`.
- `analysis/repository/JdbcAnalysisRepository.java` — `UPDATE ... RETURNING` impl.
- `analysis/repository/UnavailableAnalysisRepository.java` — fail-fast stub.
- `expression/service/ExpressionService.java` — claim resolution + `@Transactional`.

### Tests (backend)

- `ExpressionServiceTests` — claim happy path; already-owned same user; token mismatch →
  404 (hijack guard); anonymous analysis with no claim token → 404; session-token-only
  principal → 401 (existing). Update existing call sites for the new DTO field.
- `JdbcAnalysisRepositoryTests` — real `UPDATE ... RETURNING`: matching token claims and
  clears `session_token`; mismatched token claims nothing; already-owned row not stolen.

## Frontend design

- `src/lib/pending-save.ts` — `PendingSave` type; `writePendingSave` / `readPendingSave`
  (typed parse, returns `null` on malformed) / `clearPendingSave` against
  `sessionStorage["pending_save"]` serialized as the snake_case contract; plus a pure
  `resolvePostAuthDestination({ isOnboarded, pendingSave })` capturing the redirect rule
  (not onboarded → `/welcome/coach`; pending save → S07 saved state + resume; else
  `/home`).
- `src/lib/expression/save-expression.ts` (server-only) — mints `user_id` internal-auth,
  POSTs `/api/v1/expressions` with `Idempotency-Key` and the optional `session_token`
  claim; mirrors `oauth-provisioning.ts`. 409 is treated as success (already saved).
- `src/app/api/expressions/route.ts` — BFF `POST`: Origin check, require authenticated
  session, read anon-session cookie → claim token, call the save client, map Spring 404 →
  404 (apology).
- S07 `ResultActions.tsx` + `page.tsx` — pass `analysisRequestId` + `isAuthenticated`;
  pre-signup Save writes `pending_save` and routes `/login`; authenticated Save calls the
  BFF route.

### Tests (frontend)

- `src/lib/pending-save.test.ts` — round-trip write/read; malformed → `null`; clear; and
  `resolvePostAuthDestination` for the three redirect cases.
- `src/lib/expression/save-expression.test.ts` — mints a `user_id` token and forwards the
  `session_token` claim in the body; 409 surfaces as already-saved.

## Verification

- `cd backend && ./gradlew.bat test --tests "com.phraselog.expression.*"
  --tests "com.phraselog.analysis.*"` then full `./gradlew.bat test`.
- `bun test src/lib/pending-save.test.ts src/lib/expression/save-expression.test.ts`.
- `bun run build` (frontend routes changed).
- `git diff --check`.
- Testcontainers JDBC tests need Docker; if unavailable, report which were skipped — do
  not substitute H2.

## Not done / blocked

- Live browser E2E of the full pre-signup → claim path: blocked on the unbuilt anonymous
  S02 analysis flow that must set the httpOnly anon-session cookie. No browser E2E claimed.
