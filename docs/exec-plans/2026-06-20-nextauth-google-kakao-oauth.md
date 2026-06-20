# Exec plan: NextAuth Google + Kakao OAuth (#18)

Status: **Approved direction 2026-06-20. Owner chose Option B: Spring Boot owns
user/identity persistence. Dependency install still requires explicit approval.**

## Goal

Implement issue #18 (E03.1): real Google + Kakao OAuth login with Auth.js /
NextAuth on the Next.js side. A successful provider callback must resolve a
PhraseLog user, establish the provider identity, and issue a secure NextAuth
session cookie. The browser still never calls Spring Boot directly; later BFF
route handlers will read the NextAuth session and mint `X-Internal-Auth` using
the #20 utility.

## Source specs

- GitHub issue #18: Google/Kakao OAuth app setup, find/create `users` row in
  callback, secure session cookie, logout, and `callback_error` handoff to S03.
- `docs/auth.md` and ADR-010: Auth.js session cookie is encrypted JWE; Spring
  Boot does not consume it. BFF route handlers translate a server-side NextAuth
  session into short-lived `X-Internal-Auth`.
- `docs/screens/s03.md`: provider order, callback routing, callback error state,
  same-email conflict copy, pending-save priority.
- `docs/data-model.md` and `backend/src/main/resources/db/migration/V001__init_schema.sql`:
  `users` and `user_auth_identities` already exist with UUID ids and
  `UNIQUE(provider, provider_user_id)`.
- `docs/architecture.md`: RDS is private, Spring Boot owns the API and DB access
  pattern, NextAuth owns OAuth, and Vercel env holds auth/provider secrets.
- `docs/quality-gates.md` and `SECURITY.md`: auth changes require tests, no secret
  exposure, and owner approval for dependency installs / OAuth settings.

## External docs checked

Verified against official Auth.js docs on 2026-06-20:

- Next.js setup uses `next-auth@beta`, a root `auth.ts`, and
  `app/api/auth/[...nextauth]/route.ts`; for Next.js 16, the docs note
  `proxy.ts` for session renewal instead of older `middleware.ts`.
  Source: https://authjs.dev/getting-started/installation?framework=next-js
- Google callback URL for Next.js is `/api/auth/callback/google`; env names are
  `AUTH_GOOGLE_ID` and `AUTH_GOOGLE_SECRET`.
  Source: https://authjs.dev/getting-started/providers/google
- Kakao callback URL for Next.js is `/api/auth/callback/kakao`; env names are
  `AUTH_KAKAO_ID` and `AUTH_KAKAO_SECRET`.
  Source: https://authjs.dev/getting-started/providers/kakao
- Client components can call `signIn` / `signOut` from `next-auth/react`.
  Source: https://authjs.dev/getting-started/session-management/login
- Auth.js database adapters are optional for cookie sessions but required when
  persisting user/provider information; the official `@auth/pg-adapter` expects
  its own `accounts`, `sessions`, and serial-id `users` schema, which does not
  match PhraseLog's UUID `users` + `user_auth_identities` schema.
  Sources: https://authjs.dev/getting-started/database and
  https://authjs.dev/getting-started/adapters/pg

## Current repo facts

- `package.json` has no `next-auth`, no `pg`, no Auth.js adapter package.
- There is no `auth.ts`, `app/api/auth/[...nextauth]/route.ts`, `proxy.ts`, or
  frontend test runner configured.
- `src/app/(app)/login/LoginExperience.tsx` is mock-only: all provider buttons
  route to `/welcome/coach`.
- `src/app/(app)/settings/SettingsExperience.tsx` logout is mock-only:
  `router.push("/")`.
- `src/lib/internal-auth.ts` from #20 already mints server-only HS256
  `X-Internal-Auth` tokens, but it is not wired to NextAuth or BFF route handlers.
- Spring Boot has established JdbcTemplate persistence patterns, but the Next.js
  layer has no DB access pattern yet.

## Scope boundaries

In scope for #18:

- Google and Kakao OAuth provider setup in NextAuth.
- NextAuth API route and secure session configuration.
- PhraseLog user/identity resolution for Google/Kakao callbacks.
- Login button wiring from mock route to real `signIn("google" | "kakao")`.
- Logout wiring from mock route to real `signOut()`.
- S03 callback error state for OAuth rejection/failure.
- Tests for callback/user resolution and cookie/session config where locally
  testable without live provider credentials.

Out of scope:

- Email magic link / SES SMTP (#19).
- Full S03 UI/pending-save branch coverage (#22), except the OAuth error handoff
  needed by #18.
- Same-email conflict feature completeness (#21), except not auto-linking by email
  must not be violated by #18.
- BFF proxy route handlers for product APIs beyond auth setup; those consume the
  NextAuth session later.
- Live Google/Kakao OAuth verification unless owner provides local/provider
  credentials and performs console setup.

## Decision resolved before implementation

**D1 - Where does OAuth callback persistence happen?**

The issue requires "find/create users row in callback". There are two viable
paths, and they have different operational consequences.

### Option A - Next.js custom Auth.js adapter writes directly to Postgres

Use `pg` from Next.js server code and implement a small PhraseLog-specific
adapter/repository over `users` and `user_auth_identities`.

Pros:
- Closest to Auth.js adapter flow.
- Fewer new backend endpoints.
- Easier local unit tests around `getUserByAccount`, `createUser`, `linkAccount`.

Cons:
- Conflicts with the current architecture if production RDS is private and only
  EC2 can reach it.
- Adds DB credentials to Vercel env, which `docs/architecture.md` does not list.
- Opens a second DB writer surface outside Spring Boot.

### Option B - Spring Boot owns user/identity persistence

NextAuth callback calls a Spring Boot auth-provisioning endpoint over a trusted
server-to-server channel. Spring Boot keeps DB ownership; NextAuth stores only
the resolved `user_id` in its encrypted JWT session.

Pros:
- Matches existing "Spring Boot owns DB/API" architecture and private RDS design.
- Keeps DB credentials out of Vercel.
- Reuses backend JdbcTemplate/test patterns and keeps identity policy near #21.

Cons:
- Requires a new backend endpoint / service-level trust mechanism because #20
  `X-Internal-Auth` normally carries an already-known `user_id` or anonymous
  `session_token`, while OAuth callback is how `user_id` is discovered.
- Slightly larger implementation than a direct adapter.
- Needs careful tests so this endpoint is not accidentally browser-callable.

**Owner decision (2026-06-20): Option B.** Spring Boot owns OAuth callback
user/identity persistence. NextAuth must not write directly to Postgres from
Vercel in #18.

## Proposed decisions

- **D2 - Auth.js package:** add `next-auth@beta` per official Next.js Auth.js docs.
  Owner approval required because this is a dependency install.
- **D3 - Providers:** configure only Google and Kakao in #18. Email provider stays
  in #19.
- **D4 - Session strategy:** use Auth.js JWT session strategy (encrypted JWE by
  default with `AUTH_SECRET`), `maxAge = 7 days`, and `updateAge` low enough to
  provide sliding renewal. Do not use database sessions in #18.
- **D5 - Cookie hardening:** explicitly configure the session cookie with `__Host-`
  name, `httpOnly`, `sameSite: "lax"`, `path: "/"`, and `secure` for production.
  Local dev may need the non-secure fallback that Auth.js expects, but production
  must match the issue acceptance criteria.
- **D6 - Same-email behavior:** do not auto-link a new Google/Kakao account solely
  by matching email while signed out. Return S03 guidance instead. Full matrix
  belongs to #21, but #18 must not violate this rule.
- **D7 - Tests before production code:** write failing tests first for callback
  persistence/resolution and session/cookie config. Frontend route/button changes
  can be covered by typecheck/lint plus focused browser/manual checks once
  credentials exist.

## Implementation units

### U1 - Resolve callback persistence path

Files likely affected:
- If Option A: new `src/lib/auth/phrase-log-adapter.ts`,
  `src/lib/auth/phrase-log-auth-store.ts`, and tests.
- If Option B: new backend `com.phraselog.auth.identity` package, controller /
  service / repository tests, plus a Next.js callback helper.

Test scenarios:
- Existing `(provider, provider_user_id)` returns the existing active user.
- New provider identity creates a new active user and identity row.
- Same external provider account cannot link to two users.
- Same email with different provider while signed out does not auto-link.
- Soft-deleted user/email behavior is explicit and tested.

### U2 - Add NextAuth root config and route handler

Files:
- `auth.ts`
- `src/app/api/auth/[...nextauth]/route.ts`
- optionally `proxy.ts` for session expiry renewal on Next.js 16

Test scenarios:
- Config exports `handlers`, `auth`, `signIn`, and `signOut`.
- Providers include Google and Kakao only.
- Custom pages point error/sign-in failures back to `/login` with a state S03 can
  render.
- Session token includes the PhraseLog `user_id` and exposes only safe session
  fields to the browser.

### U3 - Wire S03 provider buttons to real sign-in

Files:
- `src/app/(app)/login/LoginExperience.tsx`
- possibly a tiny client helper for provider ids / redirect target.

Test scenarios:
- Google button calls `signIn("google", { redirectTo: ... })`.
- Kakao button calls `signIn("kakao", { redirectTo: ... })`.
- OAuth callback error query renders retry/error state instead of silently showing
  the default mock state.
- Email UI remains present but does not pretend #19 is implemented.

### U4 - Wire logout to real sign-out

Files:
- `src/app/(app)/settings/SettingsExperience.tsx`

Test scenarios:
- Logout calls `signOut({ redirectTo: "/" })`.
- It no longer only pushes the route while leaving the session cookie alive.

### U5 - Owner setup checklist

Files:
- `docs/exec-plans/2026-06-20-nextauth-google-kakao-oauth.md`
- optionally `docs/auth.md` if final env names/redirect URIs need a durable note.

Owner actions:
- Create Google OAuth app.
- Create Kakao OAuth app.
- Register local and production redirect URIs:
  - `http://localhost:3000/api/auth/callback/google`
  - `http://localhost:3000/api/auth/callback/kakao`
  - `https://<production-domain>/api/auth/callback/google`
  - `https://<production-domain>/api/auth/callback/kakao`
- Add secrets locally/Vercel without sharing them in chat:
  - `AUTH_SECRET`
  - `AUTH_GOOGLE_ID`
  - `AUTH_GOOGLE_SECRET`
  - `AUTH_KAKAO_ID`
  - `AUTH_KAKAO_SECRET`
  - plus whichever server-to-server persistence secret D1 requires.

## Verification plan

Local, no live OAuth credentials:

- Targeted unit/integration tests for the chosen callback persistence path.
- `bun run typecheck`
- `bun run lint`
- `bun run build`
- Backend tests if Option B adds backend code.

Credential-backed manual smoke:

- Google new signup creates or resolves a PhraseLog user.
- Google re-login resolves the same user.
- Kakao new signup creates or resolves a PhraseLog user. Deferred from this PR
  pending provider-console/email-consent follow-up.
- Kakao re-login resolves the same user. Deferred from this PR pending the same
  follow-up.
- OAuth rejection/error returns to `/login` with the S03 callback error state.
- Logout clears the NextAuth session cookie.
- Cookie attributes are inspected in browser/devtools or response headers.

## Execution note

This PR ships the Google-backed OAuth path first. Kakao remains provider-capable
in the shared OAuth provisioning code, but the S03 Kakao entry point is disabled
until the Kakao developer-console email consent and callback configuration can be
verified end to end.

## Risk notes

- Do not commit OAuth secrets, `AUTH_SECRET`, DB URLs, or provider tokens.
- Do not add a direct Vercel-to-RDS path unless the owner explicitly accepts the
  architecture change.
- Do not weaken #20 internal auth to make callback provisioning easier.
- Do not treat a passing build as live OAuth verification; provider console setup
  and real callback testing are separate.
- Do not auto-link by same email while signed out. That would violate S03 and #21.

## Next action

Begin with failing tests for U1. Before U2, request owner approval to install
`next-auth@beta` and any additional test/support package needed for the Next.js
side.
