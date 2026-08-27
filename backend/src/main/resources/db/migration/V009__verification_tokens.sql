-- S03 email magic link (#19). Auth.js requires a persisted verification-token store for
-- the email provider; without one there is no way to tell a link we issued from a link
-- someone fabricated.
--
-- The stored token is NOT the value in the emailed link. Auth.js hashes it as
-- sha256(rawToken + AUTH_SECRET) before handing it to the adapter and sends the raw value
-- in the URL (verified against @auth/core in next-auth 5.0.0-beta.31,
-- lib/actions/signin/send-token.js and lib/actions/callback/index.js, 2026-08-27). A
-- database leak therefore does not yield usable links. Column is sized past the current
-- 64-char hex digest so a hash change upstream does not require a migration.
--
-- Rollback: DROP TABLE verification_tokens;  (indexes go with it)
--
-- Safe to run at any time, unlike most rollbacks here. The table is standalone — no
-- foreign keys point at it and none leave it — and every row is disposable by
-- construction: rows are single-use, purged on consume, and expire within 24 hours.
-- Dropping it invalidates magic links that are already in flight, so those users must
-- request a new one; nothing else is lost. Migrations remain forward-only in production
-- (architecture.md), so this runs as a compensating migration, not an executed DOWN
-- script. Deploy the app without the email provider first — otherwise sign-in attempts
-- fail against a missing table instead of falling back to Google/Kakao.

CREATE TABLE verification_tokens (
  identifier VARCHAR(255) NOT NULL,
  token      VARCHAR(255) NOT NULL,
  expires    TIMESTAMPTZ  NOT NULL,
  created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT pk_verification_tokens PRIMARY KEY (identifier, token)
);

-- Auth.js looks a token up by hash alone; the identifier it passes is compared afterwards.
-- Unique rather than plain so two identifiers can never collide on one token value.
CREATE UNIQUE INDEX uq_verification_tokens_token ON verification_tokens(token);

-- Serves the expired-row purge that runs on consume.
CREATE INDEX idx_verification_tokens_expires ON verification_tokens(expires);

-- Serves the per-identifier outstanding-token cap, which is what stops one form from
-- being used to mailbox-bomb a stranger on our SES quota.
CREATE INDEX idx_verification_tokens_identifier ON verification_tokens(identifier);
