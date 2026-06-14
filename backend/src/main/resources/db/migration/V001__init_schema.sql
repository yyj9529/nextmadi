CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email VARCHAR(255) NOT NULL,
  display_name VARCHAR(100),
  selected_coach_id UUID,
  is_onboarded BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  scheduled_deletion_at TIMESTAMPTZ,
  deleted_at TIMESTAMPTZ,
  CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE UNIQUE INDEX idx_users_email_active ON users(email) WHERE deleted_at IS NULL;

CREATE TABLE coach_profiles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slug VARCHAR(20) NOT NULL,
  display_name VARCHAR(50) NOT NULL,
  persona_summary TEXT NOT NULL,
  tts_voice_id VARCHAR(50) NOT NULL,
  prompt_template_ref VARCHAR(100) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_coach_profiles_slug UNIQUE (slug)
);

ALTER TABLE users
  ADD CONSTRAINT fk_users_selected_coach
  FOREIGN KEY (selected_coach_id)
  REFERENCES coach_profiles(id);

CREATE TABLE landing_examples (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  korean_text TEXT NOT NULL,
  is_active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_landing_active ON landing_examples(is_active) WHERE is_active = true;

CREATE TABLE anonymous_analysis_usage (
  ip_address INET NOT NULL,
  usage_date DATE NOT NULL,
  count INTEGER NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT pk_anonymous_analysis_usage PRIMARY KEY (ip_address, usage_date)
);

CREATE INDEX idx_anon_usage_date ON anonymous_analysis_usage(usage_date);

CREATE TABLE tts_audio_cache (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  text_hash VARCHAR(64) NOT NULL,
  text_content TEXT NOT NULL,
  voice_id VARCHAR(50) NOT NULL,
  model_name VARCHAR(50) NOT NULL,
  audio_s3_key VARCHAR(255) NOT NULL,
  duration_ms INTEGER,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ,
  CONSTRAINT uq_tts_audio_cache_key UNIQUE (text_hash, voice_id, model_name)
);

CREATE INDEX idx_tts_cache_lookup ON tts_audio_cache(text_hash, voice_id, model_name);
CREATE INDEX idx_tts_cache_expiry ON tts_audio_cache(expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE ai_request_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  feature_name VARCHAR(50) NOT NULL,
  model_name VARCHAR(50) NOT NULL,
  prompt_version VARCHAR(20),
  input_tokens INTEGER,
  output_tokens INTEGER,
  latency_ms INTEGER NOT NULL,
  estimated_cost_usd NUMERIC(10, 6),
  status VARCHAR(20) NOT NULL,
  error_code VARCHAR(50),
  request_correlation_id UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_ai_request_logs_status CHECK (status IN ('success', 'error', 'timeout', 'cache_hit'))
);

CREATE INDEX idx_logs_feature_date ON ai_request_logs(feature_name, created_at DESC);
CREATE INDEX idx_logs_correlation ON ai_request_logs(request_correlation_id);
CREATE INDEX idx_logs_user_date ON ai_request_logs(user_id, created_at DESC) WHERE user_id IS NOT NULL;
CREATE INDEX idx_logs_status_date ON ai_request_logs(status, created_at DESC) WHERE status != 'success';

CREATE TABLE user_auth_identities (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider VARCHAR(20) NOT NULL,
  provider_user_id VARCHAR(255) NOT NULL,
  provider_email VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_user_auth_identities_provider CHECK (provider IN ('google', 'kakao', 'email')),
  CONSTRAINT uq_auth_identities_provider_user UNIQUE (provider, provider_user_id)
);

CREATE INDEX idx_auth_identities_user ON user_auth_identities(user_id);

CREATE TABLE analysis_requests (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  session_token VARCHAR(64),
  ip_address INET,
  input_text TEXT NOT NULL,
  output_json JSONB NOT NULL,
  prompt_version VARCHAR(20) NOT NULL,
  ai_request_log_id UUID REFERENCES ai_request_logs(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_analysis_input_text_length CHECK (length(input_text) <= 500)
);

CREATE INDEX idx_analysis_user_date ON analysis_requests(user_id, created_at DESC);
CREATE INDEX idx_analysis_session_token ON analysis_requests(session_token) WHERE user_id IS NULL;

CREATE TABLE practice_sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expression_id UUID,
  coach_id UUID NOT NULL REFERENCES coach_profiles(id),
  status VARCHAR(20) NOT NULL,
  planned_turns INTEGER NOT NULL,
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at TIMESTAMPTZ,
  result_json JSONB,
  CONSTRAINT chk_practice_sessions_status CHECK (status IN ('active', 'completed', 'abandoned')),
  CONSTRAINT chk_practice_sessions_planned_turns CHECK (planned_turns BETWEEN 3 AND 10)
);

CREATE INDEX idx_sessions_user_date ON practice_sessions(user_id, started_at DESC);
CREATE INDEX idx_sessions_daily_count ON practice_sessions(user_id, started_at);

CREATE TABLE expressions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source_type VARCHAR(20) NOT NULL,
  analysis_request_id UUID REFERENCES analysis_requests(id) ON DELETE SET NULL,
  practice_session_id UUID REFERENCES practice_sessions(id) ON DELETE SET NULL,
  original_situation TEXT NOT NULL,
  selected_variant_id UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ,
  CONSTRAINT chk_expressions_source_type CHECK (source_type IN ('analysis', 'roleplay_result')),
  CONSTRAINT chk_expressions_source CHECK (
    (source_type = 'analysis' AND analysis_request_id IS NOT NULL AND practice_session_id IS NULL)
    OR
    (source_type = 'roleplay_result' AND practice_session_id IS NOT NULL AND analysis_request_id IS NULL)
  )
);

CREATE INDEX idx_expressions_user_date ON expressions(user_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_expressions_situation_search ON expressions
  USING gin(to_tsvector('simple', original_situation))
  WHERE deleted_at IS NULL;

ALTER TABLE practice_sessions
  ADD CONSTRAINT fk_practice_sessions_expression
  FOREIGN KEY (expression_id)
  REFERENCES expressions(id)
  ON DELETE SET NULL;

CREATE TABLE expression_variants (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  expression_id UUID NOT NULL REFERENCES expressions(id) ON DELETE CASCADE,
  variant_order INTEGER NOT NULL,
  tone_label VARCHAR(50),
  english_text TEXT NOT NULL,
  ipa TEXT,
  korean_pronunciation TEXT,
  pronunciation_tip TEXT,
  cultural_tip TEXT,
  tts_audio_cache_id UUID REFERENCES tts_audio_cache(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_expression_variants_order CHECK (variant_order BETWEEN 1 AND 3),
  CONSTRAINT uq_expression_variants_order UNIQUE (expression_id, variant_order)
);

CREATE INDEX idx_variants_expression ON expression_variants(expression_id);
CREATE INDEX idx_variants_search ON expression_variants
  USING gin(to_tsvector('simple', english_text));

ALTER TABLE expressions
  ADD CONSTRAINT fk_expressions_selected_variant
  FOREIGN KEY (selected_variant_id)
  REFERENCES expression_variants(id)
  ON DELETE SET NULL;

CREATE TABLE review_cards (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expression_id UUID NOT NULL REFERENCES expressions(id) ON DELETE CASCADE,
  next_review_at TIMESTAMPTZ NOT NULL,
  last_reviewed_at TIMESTAMPTZ,
  last_rating VARCHAR(10),
  current_interval_days INTEGER NOT NULL,
  removed_from_queue_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_review_cards_last_rating CHECK (last_rating IN ('hard', 'good', 'easy')),
  CONSTRAINT uq_review_cards_user_expression UNIQUE (user_id, expression_id)
);

CREATE INDEX idx_review_due ON review_cards(user_id, next_review_at)
  WHERE removed_from_queue_at IS NULL;

CREATE TABLE review_attempts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  review_card_id UUID NOT NULL REFERENCES review_cards(id) ON DELETE CASCADE,
  expression_id UUID NOT NULL REFERENCES expressions(id) ON DELETE CASCADE,
  rating VARCHAR(10) NOT NULL,
  previous_interval_days INTEGER NOT NULL,
  next_interval_days INTEGER NOT NULL,
  reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_review_attempts_rating CHECK (rating IN ('hard', 'good', 'easy'))
);

CREATE INDEX idx_attempts_user_date ON review_attempts(user_id, reviewed_at DESC);
CREATE INDEX idx_attempts_card ON review_attempts(review_card_id, reviewed_at DESC);

CREATE TABLE practice_turns (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id UUID NOT NULL REFERENCES practice_sessions(id) ON DELETE CASCADE,
  turn_number INTEGER NOT NULL,
  speaker VARCHAR(10) NOT NULL,
  text_content TEXT,
  tts_audio_cache_id UUID REFERENCES tts_audio_cache(id) ON DELETE SET NULL,
  stt_confidence NUMERIC(4, 3),
  feedback_shown BOOLEAN NOT NULL DEFAULT false,
  feedback_content JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_practice_turns_speaker CHECK (speaker IN ('user', 'coach')),
  CONSTRAINT uq_practice_turns_session_turn UNIQUE (session_id, turn_number)
);

CREATE INDEX idx_turns_session ON practice_turns(session_id, turn_number);
