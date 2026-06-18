# Exec plan: BFF internal token — X-Internal-Auth JWS (#20)

Status: **Approved 2026-06-18 — owner confirmed all three gating decisions (scope,
TTL, dependency approval). Ready to implement.**

## Goal

Implement issue #20 (E03.3): the cryptographic handoff that lets Spring Boot trust a
caller identity asserted by the Next.js BFF, per ADR-010. Next.js mints a short-lived
signed (JWS) token per request carrying `user_id` (authenticated) or `session_token`
(anonymous S02), sent in `X-Internal-Auth`. Spring Boot verifies signature + expiry,
extracts the claim, and exposes it to downstream handlers for row-ownership checks. No
refresh, no revocation — short expiry bounds exposure (architecture.md:201).

## Source specs

- GitHub issue #20 — requirements + acceptance criteria (direct backend call w/o valid
  token → 401; expired/forged rejected; auth not weakened to pass tests).
- ADR-010 + `docs/auth.md` — BFF decision; the internal credential must bind identity
  cryptographically (static key + plaintext `X-User-Id` explicitly rejected); Spring
  Boot still does resource-level authz on top.
- `docs/api/openapi.yaml` — `internalAuth` scheme: `apiKey` in header `X-Internal-Auth`,
  signed with `INTERNAL_AUTH_SECRET` shared only between Next.js and Spring Boot;
  `security: [internalAuth: []]` global, `security: []` on the one public landing route.
- `docs/architecture.md` — auth flow (lines 182–183, 201–202, 219–220); secret storage
  (Secrets Manager + Vercel env, lines 241–243); **rotation policy (line 345): manual
  annual rotation, two-secret overlap procedure documented before first rotation.**
- `docs/quality-gates.md` — "Backend / auth / DB change" gate (unit+integration pass,
  error contract, no secret exposure).
- `SECURITY.md` — never log secrets; **any change to JWT handling and any dependency
  install are "stop and ask the owner" items.**

## Key facts established before coding

Verified against the repo, not assumed.

- **The 401 error contract already exists and is test-locked.** `ErrorContractController
  AdviceTests` (lines 62–73) pins the `X-Internal-Auth` failure response exactly:
  status `401`, `error_code = internal_auth_invalid`, `user_message = "로그인이 필요해요."`,
  `developer_hint = "Check X-Internal-Auth signature, expiry, and required claims."`,
  `retryable = false`, plus `request_correlation_id`. The verifier's rejection MUST
  produce this exact body. The clean way to guarantee that is to reuse the existing
  `ApiErrorException` + `GlobalExceptionHandler` path rather than hand-serialize JSON in
  the filter (see D3).
- **Error response is single-sourced** through `ApiErrorException(status, errorCode,
  userMessage, developerHint, retryable)` → `GlobalExceptionHandler` (@RestControllerAdvice)
  → `ApiErrorResponse` (5 snake_case fields + correlation id from MDC).
- **`RequestCorrelationFilter`** (`OncePerRequestFilter`, `@Component`) sets the MDC
  `request_correlation_id` and the `X-Request-Correlation-Id` response header. The auth
  filter must run **after** it so a 401 body still carries the correlation id.
- **No JWS/JOSE library on the backend classpath.** `backend/build.gradle` has Spring
  Web/JDBC/Flyway/AWS, no JJWT or Nimbus. Adding one is part of this ticket → triggers
  the SECURITY.md dependency-approval gate.
- **The Next.js BFF does not exist yet.** `package.json` has only `next`/`react`; no
  `next-auth`, no `jose`, no `route.ts` handlers, no `app/api`. The frontend is entirely
  `src/lib/mock-api.ts`-driven. So the ticket's "Next.js route handler validates NextAuth
  session" half has no host to live in yet — this bounds scope (see Open decision 1).
- **Rotation is already decided** (architecture.md:345): manual annual, two-secret
  overlap. So the verifier should accept more than one valid secret to make overlap a
  config change, not a redeploy-with-downtime event (see D4).
- **The shared-secret model implies symmetric signing** (`INTERNAL_AUTH_SECRET`, one
  shared string, not a key pair) → HMAC, i.e. HS256 (see Assumption A1).

## Design (backend — the implementable core)

New package `com.phraselog.auth` (architecture.md:97 already reserves `auth/` for "internal
JWS token verification (BFF)").

- **`InternalAuthFilter`** (`OncePerRequestFilter`, ordered after
  `RequestCorrelationFilter`) — the gate. Reads `X-Internal-Auth`; on any failure
  (missing / malformed / bad signature / expired / missing-or-ambiguous subject claim)
  produces the locked `internal_auth_invalid` 401. On success, resolves an
  `InternalAuthPrincipal { userId | sessionToken }` and exposes it to downstream
  handlers (request attribute or a small request-scoped holder). Public routes
  (`security: []` — currently `GET /landing/examples`) are excluded by an explicit path
  allowlist that mirrors openapi `security: []`; everything else requires a valid token
  (fail-closed: unknown path → require token).
- **`InternalAuthVerifier`** — pure verification unit (no servlet types): `verify(String
  token) -> InternalAuthClaims`, throwing a typed `InternalAuthException` on any failure
  reason. Verifies signature (HS256), `exp` (with bounded clock-skew leeway), and that
  **exactly one** of `user_id` / `session_token` is present. Unit-tested in isolation by
  forging tokens with the test secret — no servlet, no live calls.
- **`InternalAuthClaims` / `InternalAuthPrincipal`** — typed records for the verified
  subject. Exactly-one-of invariant enforced at construction.
- **`InternalAuthProperties`** — binds `INTERNAL_AUTH_SECRET` (one or more, for rotation
  overlap), TTL leeway. Secret from Secrets Manager in prod, env var in dev, dummy in
  test (so CI never needs a real secret).
- **Config** — register the filter in the chain after `RequestCorrelationFilter`; no
  Spring Security starter is pulled in just for this (a single `OncePerRequestFilter`
  matches the repo's existing filter style and avoids a large dependency + default-chain
  surprises). Revisit if/when real authorization rules need Spring Security.

### Next.js side (minting) — scope-gated, see Open decision 1

- **`mintInternalAuthToken({ userId | sessionToken }) -> string`** — a pure server-only
  utility (signs with `INTERNAL_AUTH_SECRET` via `jose`, sets `exp`). Unit-testable
  without NextAuth. This is the half that can be built now and interop-tested against the
  Java verifier (same secret, round-trip).
- **Wiring** into real route handlers (session → mint → proxy to Spring Boot) is **out of
  scope here** — it requires NextAuth and the BFF proxy layer, neither of which exists.
  Tracked as a downstream dependency, not built in #20.

### D-decisions (proposed; the crypto-lib and JWT-handling ones need owner sign-off)

- **D1 — Backend JOSE library: Nimbus JOSE+JWT** (`com.nimbusds:nimbus-jose-jwt`).
  Rationale: smallest focused JOSE lib, no Spring-Security pull-in, explicit
  `MACVerifier`/`JWSVerifier` API that makes "verify exactly HS256, reject `alg=none` and
  algorithm-confusion" straightforward. Alternative JJWT (`io.jsonwebtoken`) is also
  fine; either is a new dependency → **owner approval required (SECURITY.md).**
- **D2 — Next.js signing library: `jose`.** The de-facto Auth.js-ecosystem JOSE lib,
  edge-runtime safe, symmetric `SignJWT` with HS256. New dependency → owner approval.
- **D3 — The filter renders the 401 through the existing exception path, not by
  hand-writing JSON.** Inject the `@Qualifier("handlerExceptionResolver")
  HandlerExceptionResolver` and call `resolveException(req, resp, null, new
  ApiErrorException(401, "internal_auth_invalid", …))`. This makes
  `GlobalExceptionHandler` the single source of the contract body, so the filter's 401
  automatically matches the locked `ErrorContractControllerAdviceTests` shape instead of
  duplicating it. (Filters run before `@RestControllerAdvice`, so the advice can't catch
  a thrown exception directly — delegating to the resolver is the standard bridge.)
- **D4 — Verifier accepts an ordered list of secrets.** Try each; success on first
  match. Makes the documented annual two-secret overlap (architecture.md:345) a config
  change. v1 ships with one secret configured; the list shape is the only thing built now.
- **D5 — Algorithm pinned, not negotiated.** Verifier accepts **only** HS256; reject
  `alg=none` and any asymmetric `alg` header explicitly (prevents algorithm-confusion).

## Assumptions (explicit — flagged because docs don't pin them)

- **A1 — HS256.** Implied by "shared secret `INTERNAL_AUTH_SECRET`" (symmetric). If the
  owner wants asymmetric (RS256) instead, the secret-storage shape changes; flag now.
- **A2 — Claim set:** exactly one of `user_id` / `session_token`, plus `iat` and `exp`.
  `iss`/`aud` are **not** added in v1 (single known issuer/audience; would be cheap
  hardening but is pre-spec without a stated need). Noted as a possible later tightening.
- **A3 — Clock-skew leeway:** 30s bounded `exp` leeway (confirmed) since Next.js and EC2
  clocks differ slightly.

## Decisions confirmed by owner (2026-06-18)

1. **Scope: backend verifier/filter (full, tested) + pure `mintInternalAuthToken`
   utility + Java↔TS interop round-trip test.** Wiring the mint utility into real route
   handlers is deferred to the NextAuth/BFF ticket (those layers don't exist yet). The
   ticket's acceptance criteria are all backend-verifiable, so this scope satisfies them.
2. **TTL = 120s, skew leeway = 30s.** Written to `docs/auth.md` on implementation.
3. **Dependencies approved:** Nimbus JOSE+JWT (backend) + `jose` (Next.js). HS256 with
   explicit algorithm-confusion guard (D5).

## Files expected to change

- `backend/build.gradle` — add JOSE dependency (D1) — *pending approval*.
- New under `backend/src/main/java/com/phraselog/auth/`: `InternalAuthFilter`,
  `InternalAuthVerifier`, `InternalAuthException`, `InternalAuthClaims`,
  `InternalAuthPrincipal`, `InternalAuthProperties`, filter-registration config.
- `backend/src/main/resources/application.yml` / `application-prod.yml` — secret binding
  (Secrets Manager / env / dummy-in-test).
- New tests under `backend/src/test/java/com/phraselog/auth/`.
- Next.js (scope-gated): `src/lib/internal-auth.ts` (`mintInternalAuthToken`) + test;
  `package.json` adds `jose` — *pending approval & Open decision 1*.
- `docs/auth.md` — record the finalized TTL + leeway + claim set on implementation.

## Tests

Backend unit (forge tokens with the test secret; no live call):
- Valid token with `user_id` → principal resolves; with `session_token` → resolves.
- Missing header → 401 `internal_auth_invalid`.
- Bad signature (wrong secret) → 401.
- Expired token (beyond leeway) → 401; within leeway → accepted.
- `alg=none` / RS256-headed token → 401 (algorithm-confusion guard, D5).
- Both subject claims present, or neither → 401 (exactly-one-of invariant).
- Rotation: token signed by the secondary configured secret → accepted (D4).

Backend integration (MockMvc):
- A protected route without a valid token → 401 body matches the locked contract
  (reuse/extend `ErrorContractControllerAdviceTests` expectations).
- A public route (`security: []`) without a token → not 401.

Interop:
- `mintInternalAuthToken` (TS) output verifies in `InternalAuthVerifier` (Java) with a
  shared test secret — round-trip on the same claim set (catches encoding/alg drift).

Run order: `cd backend && ./gradlew test --tests "com.phraselog.auth.*"`, then full
`./gradlew test` (build file changed).

## Out of scope / hand-off

- NextAuth setup, BFF proxy route handlers, CSRF/Origin checks at the Next.js layer
  (ADR-010 hardening) — separate tickets; this ticket provides the crypto primitive they
  consume.
- Resource-level authorization / row-ownership checks — downstream handlers (#39, #60)
  use the resolved principal; not built here.
- Real secret provisioning, deploy, rotation execution — owner/ops, not this ticket.

## Verification commands

- Start: `git status --short --branch` (fresh branch off `main`).
- Post-change: `cd backend && ./gradlew test`.
- Pre-PR: `git diff --check`; confirm no secret literal committed (`INTERNAL_AUTH_SECRET`
  only referenced as a config key, never a value).

## Risks / notes

- **Algorithm confusion is the classic JWS footgun.** D5 (pin HS256, reject `none`/asym)
  is load-bearing; a verifier that trusts the token's `alg` header is forgeable. Make it
  an explicit test, not an implicit default.
- **Filter-vs-advice ordering.** If the auth filter runs before `RequestCorrelationFilter`,
  401 bodies lose the correlation id; if it hand-serializes JSON, the contract can drift
  from `ApiErrorResponse`. D3 + correct ordering address both.
- **Fail-closed default.** Unknown/unlisted paths must require a token; the public
  allowlist is the only exception and must mirror openapi `security: []` exactly, or a
  new endpoint silently ships unauthenticated.
- Per CLAUDE.md two-agent handoff, this is an auth/JWT-core change: after implementation,
  an independent reviewer (Codex / spec-reviewer) records a verdict in `docs/reviews/`
  before merge; the gate is `docs/quality-gates.md` "Backend / auth / DB change".

## Implementation note (2026-06-18)

Implemented on branch `feat/20-internal-auth-jws`. All decisions above held; no divergence.

- Backend `com.phraselog.auth`: `InternalAuthFilter` (fail-closed, ordered after
  `RequestCorrelationFilter` via `@Order`), `InternalAuthVerifier` (HS256-pinned,
  injectable `Clock`, required `iat`/`exp`, max 120s lifetime, future-`iat` guard,
  secret-list for rotation), `InternalAuthPrincipal` (exactly-one-of invariant),
  `InternalAuthException` (typed reasons, no secret/token in message),
  `InternalAuthProperties`, `InternalAuthConfiguration`. 401 delegates to
  `HandlerExceptionResolver` → existing `GlobalExceptionHandler` (D3), so the contract
  body matches `ErrorContractControllerAdviceTests` with no duplication.
- Dependency pinned: `com.nimbusds:nimbus-jose-jwt:10.9.1` (BOM does not manage it;
  Maven Central latest stable, verified 2026-06-18). Next.js: `jose@6.2.3`.
- Next.js `src/lib/internal-auth.ts` (`mintInternalAuthToken`, `server-only`) + interop
  fixture generator `scripts/mint-interop-fixture.ts`.
- Tests (all green; `./gradlew clean test spotlessCheck` + `bun run typecheck`/`lint`):
  `InternalAuthVerifierTests` (alg-confusion, expiry/leeway, required `iat`, max TTL,
  future `iat`, rotation, exactly-one-of), `InternalAuthFilterIntegrationTests` (full
  chain: no-token 401 with contract body + correlation echo, valid user/session 200,
  expired 401, public-path and actuator not gated), `InternalAuthInteropTests`
  (jose-minted token verified by Nimbus), and `InternalAuthSecretsBindingTests`
  (comma-separated rotation secret binding).
- `docs/auth.md` updated with finalized TTL/leeway/claims/rotation per the ticket.
- **Open follow-up (not this ticket):** wiring mint into NextAuth route handlers + BFF
  proxy + CSRF/Origin checks. Acceptance criteria for #20 are all backend-verifiable and
  covered. Awaiting independent review (`docs/reviews/`) before merge.
