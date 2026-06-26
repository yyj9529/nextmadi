-- #59 (E10.2): persist the POST /practice/sessions Idempotency-Key so a retry from the
-- same user returns the existing session instead of re-billing the session-init LLM call
-- or double-inserting a session + opening turn.
--
-- Roleplay requires an authenticated user (no anonymous path), so the caller identity is
-- always user_id — unlike analysis (#39), which also had a session_token branch. One partial
-- unique index on (user_id, idempotency_key) is enough.

ALTER TABLE practice_sessions
  ADD COLUMN idempotency_key UUID;

-- At most one session per (user, key). Partial so historical rows with a NULL key are unconstrained.
CREATE UNIQUE INDEX uq_practice_session_idem_user
  ON practice_sessions (user_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;
