# ADR-010: BFF Auth Handoff Between Next.js and Spring Boot

Date: 2026-06-10
Status: Accepted

## Context

The stack splits responsibilities across two services (ADR-005): NextAuth on
Next.js owns login (Google, Kakao, email magic link), while Spring Boot owns the
API. The original architecture text assumed Spring Boot would verify the
NextAuth session token with a shared signing secret. That does not work:
Auth.js's default session is an encrypted JWE, not a signed JWT, so the backend
cannot validate it by signature. A handoff mechanism had to be chosen before W4,
since every authenticated endpoint depends on it.

## Decision

Adopt a BFF (Backend-for-Frontend) handoff. The browser never calls Spring Boot.
All API traffic flows browser -> Next.js route handler -> Spring Boot. The route
handler validates the NextAuth session server-side, then calls Spring Boot with a
short-lived signed (JWS) internal token in the `X-Internal-Auth` header, carrying
the authenticated `user_id` or the anonymous `session_token` for pre-signup S02
calls as claims. Spring Boot verifies the signature and expiry, then performs
resource-level authorization as usual.

Two hardening requirements are part of the decision. First, the internal
credential must bind user identity cryptographically; a static key plus a
plaintext `X-User-Id` header is rejected because any key holder could impersonate
any user, and Vercel egress IPs are not fixed, so network allowlisting cannot
compensate. Second, cookie-authenticated BFFs need CSRF defenses at the Next.js
layer: `SameSite=Lax`, `__Host-` prefix, and Origin checks on state-changing
handlers.

Consequence for contracts: `docs/api/openapi.yaml` is the internal Next.js ->
Spring Boot contract (`internalAuth` scheme). The browser-facing surface is the
set of Next.js route handlers mirroring it.

## Consequences

v1 has exactly one client: the Next.js app. A browser-facing token system is
surface area without a consumer. BFF keeps every token out of the browser,
eliminates CORS configuration for product traffic, and removes token storage,
refresh, and revocation design from the five-week development critical path
(ADR-004).

The cost is one proxy hop per request, which is small against a product whose
latency budget is dominated by LLM calls measured in seconds. Voice uploads now
pass through the BFF, so implementation must verify Vercel request-body limits
against real browser recordings before W4.

## Alternatives Considered

- **Backend-owned token (browser-held JWT).** The earlier tentative lean.
  Requires issuance, refresh, browser storage, and CORS to serve a second client
  that does not exist. Remains the upgrade path if a native app or external
  consumer appears.
- **Database session lookup.** Adds a DB read per request and couples Spring Boot
  to NextAuth's adapter schema.
- **Provider token verification.** Most moving parts for no added benefit.

## Related

- `docs/auth.md` - problem statement and option analysis.
- `docs/architecture.md` - implementation-level auth flow.
- ADR-005 - the stack split that created the handoff need.
- ADR-004 - five-week development window this decision protects.
