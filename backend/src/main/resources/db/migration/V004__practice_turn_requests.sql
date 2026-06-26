CREATE TABLE practice_turn_requests (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id UUID NOT NULL REFERENCES practice_sessions(id) ON DELETE CASCADE,
  idempotency_key UUID NOT NULL,
  request_correlation_id UUID NOT NULL,
  status VARCHAR(20) NOT NULL,
  user_turn_id UUID REFERENCES practice_turns(id) ON DELETE SET NULL,
  coach_turn_id UUID REFERENCES practice_turns(id) ON DELETE SET NULL,
  retry_prompt TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_practice_turn_requests_status
    CHECK (status IN ('processing', 'completed', 'no_turn', 'failed')),
  CONSTRAINT uq_practice_turn_requests_session_key UNIQUE (session_id, idempotency_key)
);

CREATE INDEX idx_practice_turn_requests_correlation
  ON practice_turn_requests(request_correlation_id);

CREATE INDEX idx_practice_turn_requests_session_status
  ON practice_turn_requests(session_id, status);
