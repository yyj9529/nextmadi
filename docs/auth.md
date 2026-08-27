# Auth handoff — decided

Status: **Decided 2026-06-10 — Option 1 (BFF). See ADR-010.** This document
records the problem, the options considered, and the chosen mechanism.
`architecture.md` (Authentication section) is the source of truth for
implementation detail.

## The problem

`architecture.md` (Authentication section) currently states:

> 4. NextAuth issues a JWT session token, stored in HTTP-only cookie
> 6. Spring Boot validates JWT signature (shared secret with NextAuth)

This assumes the NextAuth session token is a **signed (JWS)** JWT that Spring Boot can
verify with a shared secret. But Auth.js (NextAuth v5) does **not** do that by default.

Verified against official docs (2026-06-05):
- Auth.js default session strategy is `"jwt"`, which is an **encrypted JWT (JWE)** stored
  in an HttpOnly cookie, encrypted with `AUTH_SECRET`. Source:
  https://authjs.dev/concepts/session-strategies and https://authjs.dev/reference/core
- A JWE is **encrypted**, not merely signed. Spring Boot cannot "verify the signature
  with a shared secret" — it would have to **decrypt** the JWE first, then validate.
- Auth.js JWT is oriented to "same app" use; for a separate backend API, an explicit
  handoff mechanism is recommended.

So the current design will not work as written. We must pick one mechanism.

## Options (pick one before W4)

1. **BFF (Backend-for-Frontend).** The browser never calls Spring Boot directly. Next.js
   route handlers hold the NextAuth session, verify it server-side, and proxy calls to
   Spring Boot over a trusted server-to-server channel. Simplest auth model; adds one
   network hop and requires securing the Next.js→Spring Boot link.
2. **Backend-owned token.** After NextAuth login, mint a separate **signed (JWS)** token
   (e.g., HS256/RS256) that the browser sends to Spring Boot; Spring Boot verifies that
   token's signature. Standard SPA+API pattern; you build a token-issue/refresh path.
3. **Database session lookup.** Use a NextAuth database adapter; Spring Boot reads the
   session table by the session id in the cookie. Revocable; adds a DB read per request.
4. **Provider token verification.** Verify Google/Kakao provider tokens at the backend.
   More moving parts; usually unnecessary if 1 or 2 covers it.

## Decision (2026-06-10)

**Option 1 (BFF)**, superseding the earlier tentative lean toward Option 2.
Full rationale lives in ADR-010. Summary: v1 has exactly one client, the
Next.js app, so a browser-facing token system is surface area without a
consumer. BFF keeps tokens out of the browser, eliminates product CORS, and
removes token storage/refresh design from the v1 critical path. Option 2 remains
the documented upgrade path if a native app or external API consumer appears;
that would need a new ADR.

### Hardening requirements

- Next.js -> Spring Boot requests carry a short-lived signed (JWS) internal
  token in `X-Internal-Auth`, binding `user_id` or anonymous S02
  `session_token` as claims.
- A static key plus plaintext `X-User-Id` header is rejected because any key
  holder could impersonate any user.
- Network allowlisting is not a substitute for the signed token. Vercel function
  egress IP behavior must be verified against official docs at W4 before
  relying on any allowlist.
- Cookie security on the NextAuth session: `__Host-` prefix, `Secure`,
  `HttpOnly`, `SameSite=Lax`.
- CSRF protection lives at the Next.js layer: NextAuth built-ins for its own
  routes, Origin/Referer checks for custom state-changing route handlers.
- Spring Boot still performs resource-level authorization on every request. The
  internal token authenticates caller/user context; it does not replace row
  ownership checks.

### Contract consequence

`docs/api/openapi.yaml` is the internal Next.js BFF -> Spring Boot contract.
Its security scheme is `internalAuth`, carried in `X-Internal-Auth`, not a
browser-facing token scheme.

## Implementation (2026-06-18, #20)

The token-issue/verify pair is implemented (exec-plan
`docs/exec-plans/2026-06-18-internal-auth-jws.md`). Finalized parameters:

- **Algorithm: HS256.** Symmetric, matching the single shared `INTERNAL_AUTH_SECRET`.
  The verifier pins HS256 and rejects `alg=none` and asymmetric algorithms (algorithm-
  confusion guard) — it never trusts the token's own `alg` header.
- **TTL: 120s; clock-skew leeway: 30s.** Minted per request, used for one server-to-
  server hop. The ticket left the exact value to implementation (architecture.md says
  "minutes"); 120s is the chosen value.
- **Claims:** exactly one of `user_id` / `session_token`, plus `iat` and `exp`. No
  `iss`/`aud` in v1 (single known issuer/audience). The exactly-one-of rule is enforced
  on both mint (TS) and verify (Java) sides.
- **Key rotation:** the verifier accepts an ordered list of secrets (tries each), so the
  documented annual two-secret overlap (architecture.md rotation note) is a config change,
  not a redeploy. v1 ships with one secret configured.
- **Failure surface:** every verification failure maps to the existing 401
  `internal_auth_invalid` error contract; the specific reason is not leaked to the caller.
- **Code:** backend `com.phraselog.auth` (filter + verifier); Next.js mint utility
  `src/lib/internal-auth.ts`. Wiring the mint utility into real route handlers is deferred
  to the NextAuth/BFF ticket (those layers do not exist yet).

## Email magic link — adapter over the BFF (2026-08-28, #19)

Auth.js's email provider requires an **Adapter**, which normally means giving Next.js a
database connection. ADR-010 says the database is Spring Boot's, and #18 already settled
that Spring owns user and identity persistence. Those two facts collide.

**Decision: implement the adapter in Next.js and route its storage calls to Spring over
`X-Internal-Auth`**, rather than installing `@auth/pg-adapter` and pointing Vercel at RDS.

Why not the pg adapter: it would put database credentials in Vercel, open a Vercel→RDS
network path that does not exist today, and create a second writer for `users`. That is
an ADR-sized change to the data-ownership boundary, not an implementation detail.

The cost is real and bounded: we hand-implement part of the adapter interface instead of
importing it. Bounded, because under `session.strategy: "jwt"` the session-table half of
the interface is never called. Six methods are implemented —
`createVerificationToken`, `useVerificationToken`, `getUserByEmail` (asserted at config
build by `@auth/core/lib/utils/assert.js` for email providers), plus `createUser`,
`updateUser` (both reached in the email branch of `handle-login.js`) and `getUser`.
`linkAccount` is **not** implemented; it belongs to the oauth and webauthn branches.

**Every unimplemented method throws, naming itself.** No `return null`, no silent no-op —
a stub returning a plausible value turns a missing capability into a successful-looking
login.

If the adapter ever needs session storage, that is the signal this approach has outlived
its fit; revisit ADR-010 rather than widening the adapter.

### Endpoint authority

The five `/auth/email/*` operations are the only endpoints whose caller has no user yet.
They are authorized by a dedicated `session_token` value, `__email_provisioning__`,
rather than a `user_id`. The OAuth provisioning token does not open them and this token
does not open OAuth provisioning, so a leak on one side does not carry the other's
authority.

Lookup is split from resolve for the same reason. Auth.js calls `getUserByEmail` while
the link is merely being *sent* — before anyone has clicked anything — so a combined
create-or-find endpoint would mint an account for every address typed into the S03 form.
See `docs/api/openapi.yaml` for the per-operation contract.

### Same-email divergence from OAuth

`/auth/email/identity` links an address to an existing user where
`/auth/oauth/identity` raises `account_link_required`. This is deliberate: a provider's
claimed email is a claim, a clicked magic link is proof of mailbox control (s03.md,
same-email edge case). `OAuthIdentityService` is untouched by the email path.

### Token storage and rollback

`verification_tokens` (V009) stores `sha256(rawToken + AUTH_SECRET)`, not the value in
the emailed link — a database leak yields no usable links. Consume is delete-and-return
in one transaction, so a replayed link authenticates once. Expiry is judged only by
Auth.js, which owns the `Verification` error; the backend returns an expired row once and
purges it.

Rollback path for V009 is documented in the migration header: `DROP TABLE
verification_tokens;` as a compensating migration, run after deploying an app build
without the email provider. The table is standalone with no foreign keys in either
direction and holds only single-use rows that expire within 24 hours, so the only loss is
magic links already in flight.

## Related

- `architecture.md` — Authentication and authorization section.
- ADR-005 — stack decision (NextAuth + Spring Boot split).
- Auth.js docs — session strategies (verified 2026-06-05).
