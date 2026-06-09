# Auth handoff — decision required before W4

Status: **Open decision (resolve before W4).** This document exists because
`architecture.md` currently describes a handoff that does not match Auth.js defaults.

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

## Recommended default (confirm at W4)

**Option 2 (backend-owned token)** for v1, because Spring Boot is the API authority and
should verify a token it fully understands (a plain signed JWT), avoiding JWE decryption
in Java. **Option 1 (BFF)** is the simpler fallback if you'd rather not build token
issuance for the MVP. Decide after you've read the tradeoffs — do not lock this in from
this document alone.

## Required architecture.md change once decided

Replace the "shared secret signature" wording with the chosen mechanism. Until then,
`architecture.md` step 6 must say: "Auth handoff mechanism TBD — see docs/auth.md
(resolve before W4)." so no one implements the incorrect shared-secret-verify path.

## Related

- `architecture.md` — Authentication and authorization section.
- ADR-005 — stack decision (NextAuth + Spring Boot split).
- Auth.js docs — session strategies (verified 2026-06-05).
