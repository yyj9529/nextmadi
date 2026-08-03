-- ADR-011: ai_request_logs stores one row per attempt, not per logical call.
-- Retries under the AI_PIPELINE.md fallback policy are separately billed requests; folding
-- them into a single row hid their tokens from cost sums and made the retry rate
-- unmeasurable in SQL.

ALTER TABLE ai_request_logs
  ADD COLUMN attempt_group_id UUID,
  ADD COLUMN attempt_number SMALLINT NOT NULL DEFAULT 1,
  ADD COLUMN is_final_attempt BOOLEAN NOT NULL DEFAULT true;

-- Every pre-ADR-011 row was one logical call, so each is its own single-attempt group.
-- Reusing id keeps the group id unique and non-null without a lookup or a second pass.
UPDATE ai_request_logs SET attempt_group_id = id WHERE attempt_group_id IS NULL;

ALTER TABLE ai_request_logs ALTER COLUMN attempt_group_id SET NOT NULL;

ALTER TABLE ai_request_logs
  ADD CONSTRAINT chk_ai_request_logs_attempt_number CHECK (attempt_number >= 1);

-- Serves per-call cost roll-ups and final-attempt lookup (analysis_requests.ai_request_log_id).
CREATE INDEX idx_logs_attempt_group ON ai_request_logs(attempt_group_id, attempt_number);
