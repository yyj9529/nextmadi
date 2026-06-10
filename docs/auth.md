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

## Related

- `architecture.md` — Authentication and authorization section.
- ADR-005 — stack decision (NextAuth + Spring Boot split).
- Auth.js docs — session strategies (verified 2026-06-05).
