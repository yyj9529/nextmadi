-- #39 (E06.1): persist the POST /analysis Idempotency-Key so a retry from the same
-- caller returns the existing row instead of re-billing the LLM or double-inserting.
--
-- architecture.md previously scoped idempotency to Save Expression / Start Roleplay;
-- analysis is added here because #39, the handoff exec-plan, and openapi.yaml all require
-- an Idempotency-Key on POST /analysis. Caller identity is user_id (authenticated) or
-- session_token (pre-signup) — never both (mirrors the X-Internal-Auth principal).

ALTER TABLE analysis_requests
  ADD COLUMN idempotency_key UUID;

-- At most one row per (authenticated caller, key).
CREATE UNIQUE INDEX uq_analysis_idem_user
  ON analysis_requests (user_id, idempotency_key)
  WHERE user_id IS NOT NULL AND idempotency_key IS NOT NULL;

-- At most one row per (anonymous caller, key).
CREATE UNIQUE INDEX uq_analysis_idem_session
  ON analysis_requests (session_token, idempotency_key)
  WHERE user_id IS NULL AND idempotency_key IS NOT NULL;
