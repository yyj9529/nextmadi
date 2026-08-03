# Data Model

Source of truth for PhraseLog v1 database schema. PostgreSQL on AWS RDS (ADR-005). Behavioral details (when a row is created, what triggers an update, validation rules) live in the corresponding `docs/screens/sNN.md`; this document defines structure only.

## Conventions

- All primary keys: `UUID` generated via `gen_random_uuid()` (except `anonymous_analysis_usage` which uses a natural composite key).
- All timestamps: `timestamptz`, stored as UTC.
- Soft delete: applied to `users` (14-day grace) and `expressions` (user-initiated removal). Other tables hard-delete via `ON DELETE CASCADE` or `ON DELETE SET NULL`.
- Money fields: `NUMERIC(10, 6)` for USD (fractional cents needed for AI API costs).
- JSONB column internal structure is defined in `AI_PIPELINE.md`, not here.
- pgvector extension is reserved for RAG (W21+); not used in v1.
- IP addresses: PostgreSQL `INET` type (supports IPv4 and IPv6 without string handling overhead).
- Polymorphic relationships are avoided. Where a row can originate from one of several sources, separate nullable FK columns plus a CHECK constraint are used instead of a single polymorphic column.

## Entities overview

```
users ──┬─< user_auth_identities             (multi-provider auth: Google / Kakao / email)
        ├─< analysis_requests ──┐
        │                       │ (source link)
        │                       ▼
        ├─< expressions ──< expression_variants
        │       ▲       (selected_variant_id)
        │       │
        │       └─< review_cards ──< review_attempts
        │
        └─< practice_sessions ──< practice_turns
                      ▲
                      │ (source link, alternative to analysis_requests)
                      └─ feeds into expressions when user saves from S12b

coach_profiles            seed data; referenced by users and practice_sessions
landing_examples          seed pool for S01 random sampling
anonymous_analysis_usage  IP-based rate limit for pre-signup S02 calls
tts_audio_cache           content-hashed cache; referenced by expression_variants and practice_turns
ai_request_logs           independent observability table; logs every AI/STT/TTS call
practice_turn_requests    idempotency/correlation records for S12 turn submissions
```

## Tables

### users

```sql
CREATE TABLE users (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email                 VARCHAR(255) UNIQUE NOT NULL,
  display_name          VARCHAR(100),
  selected_coach_id     UUID REFERENCES coach_profiles(id),
  is_onboarded          BOOLEAN NOT NULL DEFAULT false,  -- set true after S03b coach selection
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  scheduled_deletion_at TIMESTAMPTZ,                     -- 14-day grace; NULL if active
  deleted_at            TIMESTAMPTZ                      -- hard deletion timestamp; NULL if active
);

CREATE UNIQUE INDEX idx_users_email_active ON users(email) WHERE deleted_at IS NULL;
```

`auth_provider` is no longer a column on `users`; provider information lives in `user_auth_identities` (one row per linked provider). This schema permits a single user to have multiple identities without account duplication, but it does not authorize automatic same-email OAuth linking. S03 is the behavioral source of truth for unauthenticated callback conflicts.

`is_onboarded` gates the one-time S03b coach selection. Behavior: `docs/screens/s03b.md`.

14-day grace flow: account deletion request sets `scheduled_deletion_at = now() + interval '14 days'`. A daily job hard-deletes rows where `scheduled_deletion_at < now()`. The user can cancel by clearing `scheduled_deletion_at` via Settings. Behavior: `docs/screens/s11.md`.

### user_auth_identities

```sql
CREATE TABLE user_auth_identities (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider         VARCHAR(20) NOT NULL,  -- 'google' | 'kakao' | 'email'
  provider_user_id VARCHAR(255) NOT NULL,
  provider_email   VARCHAR(255),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (provider, provider_user_id)
);

CREATE INDEX idx_auth_identities_user ON user_auth_identities(user_id);
```

NextAuth-standard Account table pattern. One row per (user, provider) link. A single user can have multiple identities after an explicit link flow or verified email magic-link sign-in. v1 does not auto-link an unlinked Google/Kakao identity by `provider_email` while the user is signed out. The `(provider, provider_user_id)` unique constraint ensures the same external account cannot link to two PhraseLog users.

`provider_email` is the email returned by the provider at link time and may differ from `users.email` (the display/contact email).

### coach_profiles

```sql
CREATE TABLE coach_profiles (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slug                VARCHAR(20) UNIQUE NOT NULL,  -- 'mia' | 'david' | 'sarah'
  display_name        VARCHAR(50) NOT NULL,
  persona_summary     TEXT NOT NULL,                -- shown on S03b coach card
  tts_voice_id        VARCHAR(50) NOT NULL,         -- OpenAI TTS voice identifier
  prompt_template_ref VARCHAR(100) NOT NULL,        -- e.g. 'prompts/roleplay/mia/v1.md'
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Seed-only table. v1 ships three rows (Mia, David, Sarah). User chooses one at S03b (PRD §5.9). AI auto-matching is not in v1 (deferred to v1.1+, PRD §4.3).

### landing_examples

```sql
CREATE TABLE landing_examples (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  korean_text  TEXT NOT NULL,
  is_active    BOOLEAN NOT NULL DEFAULT true,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_landing_active ON landing_examples(is_active) WHERE is_active = true;
```

Seed pool for S01 landing page. v1 query: `SELECT * FROM landing_examples WHERE is_active = true ORDER BY random() LIMIT 3`. Seeded with 12 desire-framed situation examples in `V007__seed_landing_examples.sql`. Rotation logic (e.g., CTR-based activation) is deferred to v1.1+ — v1 keeps all seeded rows active.

### analysis_requests

```sql
CREATE TABLE analysis_requests (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID REFERENCES users(id) ON DELETE CASCADE,   -- nullable for pre-signup (S02)
  session_token     VARCHAR(64),                                   -- pre-signup attribution
  ip_address        INET,                                          -- recorded for pre-signup rate-limit audit
  input_text        TEXT NOT NULL CHECK (length(input_text) <= 500),
  output_json       JSONB NOT NULL,                                -- structure in AI_PIPELINE.md
  prompt_version    VARCHAR(20) NOT NULL,
  ai_request_log_id UUID REFERENCES ai_request_logs(id),
  idempotency_key   UUID,                                          -- POST /analysis retry dedup (#39, V002)
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_analysis_user_date ON analysis_requests(user_id, created_at DESC);
CREATE INDEX idx_analysis_session_token ON analysis_requests(session_token) WHERE user_id IS NULL;

-- At most one row per caller per Idempotency-Key (caller = user_id when authenticated,
-- session_token when pre-signup). A retry that hits an existing key returns that row
-- instead of re-billing the LLM or double-inserting.
CREATE UNIQUE INDEX uq_analysis_idem_user
  ON analysis_requests(user_id, idempotency_key)
  WHERE user_id IS NOT NULL AND idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX uq_analysis_idem_session
  ON analysis_requests(session_token, idempotency_key)
  WHERE user_id IS NULL AND idempotency_key IS NOT NULL;
```

Both `user_id` and `session_token` allow the pre-signup (S02) → signup → claim flow (PRD §5.2). On signup, pending rows with matching `session_token` are claimed by updating `user_id` and clearing `session_token`.

`input_text` is hard-capped at 500 characters at the database level (PRD §4.3, decided via PPT v2.2 slide 7).

### anonymous_analysis_usage

```sql
CREATE TABLE anonymous_analysis_usage (
  ip_address  INET    NOT NULL,
  usage_date  DATE    NOT NULL,
  count       INTEGER NOT NULL DEFAULT 0,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (ip_address, usage_date)
);

CREATE INDEX idx_anon_usage_date ON anonymous_analysis_usage(usage_date);
```

Per-IP daily counter for pre-signup S02 analysis calls. Enforced at the application layer using an UPSERT pattern (`INSERT ... ON CONFLICT (ip_address, usage_date) DO UPDATE SET count = count + 1`). When `count >= 2`, the request is rejected with a signup prompt (PRD §5.1).

A daily job deletes rows where `usage_date < current_date - interval '30 days'` to bound table size. Retention window TBD.

### expressions

```sql
CREATE TABLE expressions (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id              UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source_type          VARCHAR(20) NOT NULL CHECK (source_type IN ('analysis', 'roleplay_result')),
  analysis_request_id  UUID REFERENCES analysis_requests(id)  ON DELETE SET NULL,
  practice_session_id  UUID REFERENCES practice_sessions(id) ON DELETE SET NULL,
  original_situation   TEXT NOT NULL,
  selected_variant_id  UUID,  -- FK declared after expression_variants exists; ON DELETE SET NULL
  roleplay_result_index INTEGER,  -- 0-based index into practice_sessions.result_json recommendations
  roleplay_save_idempotency_key UUID,  -- Idempotency-Key for S12b save-expression retries
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at           TIMESTAMPTZ,                          -- user-initiated soft delete; NULL if active

  CONSTRAINT chk_expressions_roleplay_result_index CHECK (
    roleplay_result_index IS NULL OR roleplay_result_index BETWEEN 0 AND 2
  ),
  CONSTRAINT chk_expressions_source CHECK (
    (source_type = 'analysis'        AND analysis_request_id IS NOT NULL AND practice_session_id IS NULL)
    OR
    (source_type = 'roleplay_result' AND practice_session_id IS NOT NULL AND analysis_request_id IS NULL)
  )
);

CREATE INDEX idx_expressions_user_date ON expressions(user_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_expressions_roleplay_save_idem
  ON expressions(user_id, practice_session_id, roleplay_save_idempotency_key)
  WHERE source_type = 'roleplay_result' AND roleplay_save_idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX uq_expressions_roleplay_result_index_active
  ON expressions(user_id, practice_session_id, roleplay_result_index)
  WHERE source_type = 'roleplay_result' AND deleted_at IS NULL AND roleplay_result_index IS NOT NULL;
```

This table is the **parent "situation grouping"**, not the English expression itself. A single user-described situation produces 3 candidate English variants. The situation context (Korean original, source provenance, soft-delete state) lives here; the actual English texts and per-variant data live in `expression_variants`.

`source_type` together with the CHECK constraint enforces that exactly one of `analysis_request_id` or `practice_session_id` is set, depending on origin. This replaces polymorphic `source_request_id` from earlier drafts — the polymorphic pattern is removed because it cannot be FK-enforced at the database level.

`selected_variant_id` points to the currently preferred variant (default: variant_order = 1). Used by S04 recent expressions, S08 library cards, S10 review front, and S12b result references. The FK is declared after `expression_variants` is created (see below) to avoid circular dependency at table-creation time.

For `source_type = 'roleplay_result'`, `roleplay_result_index` stores the 0-based recommendation chosen from `practice_sessions.result_json.recommended_expressions`; the saved `selected_variant_id` points to the copied variant with `variant_order = roleplay_result_index + 1`. `roleplay_save_idempotency_key` deduplicates `POST /practice/sessions/{id}/save-expression` retries, while the active partial index prevents saving the same recommendation twice for one session unless the earlier expression is soft-deleted.

**`deleted_at` and ADR-002 — the important distinction**

ADR-002 ("cumulative bookshelf, no streak") forbids *absence-based decay* — the system never removes or hides expressions because the user stopped using the app. User-initiated explicit deletion is a different operation: when the user confirms removal on S09, the row is soft-deleted. The bookshelf count and library exclude soft-deleted rows. This is consistent with ADR-002 because the bookshelf still doesn't decay from absence; only the user can remove items.

After `expression_variants` is created, declare the back-reference:

```sql
ALTER TABLE expressions
  ADD CONSTRAINT fk_expressions_selected_variant
  FOREIGN KEY (selected_variant_id)
  REFERENCES expression_variants(id)
  ON DELETE SET NULL;
```

### expression_variants

```sql
CREATE TABLE expression_variants (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  expression_id        UUID NOT NULL REFERENCES expressions(id) ON DELETE CASCADE,
  variant_order        INTEGER NOT NULL CHECK (variant_order BETWEEN 1 AND 3),
  tone_label           VARCHAR(50),           -- AI-generated per variant; NOT a fixed enum
  english_text         TEXT NOT NULL,
  ipa                  TEXT,
  korean_pronunciation TEXT,
  pronunciation_tip    TEXT,
  cultural_tip         TEXT,
  tts_audio_cache_id   UUID REFERENCES tts_audio_cache(id) ON DELETE SET NULL,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

  UNIQUE (expression_id, variant_order)
);

CREATE INDEX idx_variants_expression ON expression_variants(expression_id);
CREATE INDEX idx_variants_search ON expression_variants
  USING gin(to_tsvector('simple', english_text));
```

Three rows per parent `expressions` row (one per analysis variant). The `(expression_id, variant_order)` unique constraint enforces no duplicate variant slots.

`tone_label` is dynamically generated by the AI per variant. The label can be any short Korean phrase (정중한, 단호한, 사랑스러운, 리더십 있는, etc.). The database does not enforce an enum — categorization is the LLM's responsibility.

`tts_audio_cache_id` is set when TTS audio is first generated and cached for this exact variant text. Multiple variants with identical English text + voice will share the same `tts_audio_cache` row (content-hashed).

The GIN index on `english_text` supports library keyword search (PRD §5.5). For Korean situation search the GIN index lives on `expressions.original_situation` (added below — note: not in the earlier table because `expressions` no longer carries the English text):

```sql
CREATE INDEX idx_expressions_situation_search ON expressions
  USING gin(to_tsvector('simple', original_situation))
  WHERE deleted_at IS NULL;
```

### review_cards

```sql
CREATE TABLE review_cards (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id               UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expression_id         UUID NOT NULL REFERENCES expressions(id) ON DELETE CASCADE,
  next_review_at        TIMESTAMPTZ NOT NULL,
  last_reviewed_at      TIMESTAMPTZ,
  last_rating           VARCHAR(10) CHECK (last_rating IN ('hard', 'good', 'easy')),
  current_interval_days INTEGER NOT NULL,
  removed_from_queue_at TIMESTAMPTZ,                              -- user removed from queue without deleting
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, expression_id)
);

CREATE INDEX idx_review_due ON review_cards(user_id, next_review_at)
  WHERE removed_from_queue_at IS NULL;
```

One row per (user, expression), created when an expression is saved. Stores the **latest** state only — historical attempts go in `review_attempts`.

`removed_from_queue_at` supports the S09 "복습 큐에서 제거" action: the user wants to keep the expression in the library but stop being prompted to review it.

`current_interval_days` initial value: **decided 2026-06-10** (PRD Open
Question #5). New cards are created with `next_review_at = now()` and
`current_interval_days = 1` in the `POST /expressions` save transaction; the
column keeps no schema-level default so the application value is explicit.
Rationale: immediate first due keeps the review tab non-empty on day one, while
interval base 1 keeps the S10 multiplication rules sound. Interval 0 was
rejected because 0 x 2 = 0 would create permanently-due cards. Re-adding a
removed card via `POST /review/{id}/re-add-to-queue` resets to the same values.

`last_rating` values map to S10 UI buttons: `hard` = 어려움, `good` = 기억남, `easy` = 완벽.

### review_attempts

```sql
CREATE TABLE review_attempts (
  id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id                UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  review_card_id         UUID NOT NULL REFERENCES review_cards(id) ON DELETE CASCADE,
  expression_id          UUID NOT NULL REFERENCES expressions(id) ON DELETE CASCADE,
  rating                 VARCHAR(10) NOT NULL CHECK (rating IN ('hard', 'good', 'easy')),
  previous_interval_days INTEGER NOT NULL,
  next_interval_days     INTEGER NOT NULL,
  reviewed_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_attempts_user_date ON review_attempts(user_id, reviewed_at DESC);
CREATE INDEX idx_attempts_card ON review_attempts(review_card_id, reviewed_at DESC);
```

History of every review action. Append-only — never updated. Each S10 rating click writes one row here (history) and updates the corresponding `review_cards` row (latest state).

`expression_id` is reachable via JOIN through `review_card_id` but is denormalized here for analytics queries that filter by expression directly (no join). Acknowledged denormalization; the cost is one extra column and the benefit is index-friendly queries for "how many times did the user review expression X across all sessions."

This table enables the analytics ADR-002 anticipates ("absence-of-streak users may still want to see their review patterns over time"). It also provides the data foundation for upgrading from the v1 simple interval model (×1 / ×2 / ×3) to an adaptive algorithm like SM-2 in v1.1+ without losing historical context.

### practice_sessions

```sql
CREATE TABLE practice_sessions (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expression_id UUID REFERENCES expressions(id) ON DELETE SET NULL,
  coach_id      UUID NOT NULL REFERENCES coach_profiles(id),
  status        VARCHAR(20) NOT NULL CHECK (status IN ('active', 'completed', 'abandoned')),
  planned_turns INTEGER NOT NULL CHECK (planned_turns BETWEEN 3 AND 10),
  started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at      TIMESTAMPTZ,
  result_json   JSONB                  -- Sonnet RESULT output; structure in AI_PIPELINE.md
);

CREATE INDEX idx_sessions_user_date ON practice_sessions(user_id, started_at DESC);
CREATE INDEX idx_sessions_daily_count ON practice_sessions(user_id, started_at);
```

`expression_id` is `ON DELETE SET NULL` so historical sessions remain analyzable when their source expression is soft-deleted at the application layer (the FK only fires on hard delete, which is currently never).

Daily limit enforcement (PRD §5.8, 2 sessions per user per day) runs at the application layer:

```sql
SELECT count(*) FROM practice_sessions
WHERE user_id = ? AND started_at::date = current_date AND status != 'abandoned';
```

`abandoned` status does **not** count toward the limit (confirmed in #59), which is why the query filters `status != 'abandoned'`. See `docs/screens/s12.md` US3-AC2.

The S11 "오늘의 사용량" display reads from the same query. No separate daily-usage table is required.

### practice_turns

```sql
CREATE TABLE practice_turns (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id         UUID NOT NULL REFERENCES practice_sessions(id) ON DELETE CASCADE,
  turn_number        INTEGER NOT NULL,
  speaker            VARCHAR(10) NOT NULL CHECK (speaker IN ('user', 'coach')),
  text_content       TEXT,
  tts_audio_cache_id UUID REFERENCES tts_audio_cache(id) ON DELETE SET NULL,
  stt_confidence     NUMERIC(4, 3),          -- 0.000-1.000, only set for user voice turns
  feedback_shown     BOOLEAN NOT NULL DEFAULT false,
  feedback_content   JSONB,                  -- structure in AI_PIPELINE.md
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (session_id, turn_number)
);

CREATE INDEX idx_turns_session ON practice_turns(session_id, turn_number);
```

One row per utterance (user or coach). `turn_number` is a sequential integer per session — the first coach utterance is turn 1, the first user utterance is turn 2, and so on.

`tts_audio_cache_id` replaces the earlier `audio_s3_key` column; audio is now centrally cached in `tts_audio_cache` and referenced from here.

`feedback_content` is populated only on user-speaker rows where Haiku decided to show a feedback card. Coach-speaker rows have `feedback_content = NULL`.

### practice_turn_requests

```sql
CREATE TABLE practice_turn_requests (
  id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id             UUID NOT NULL REFERENCES practice_sessions(id) ON DELETE CASCADE,
  idempotency_key        UUID NOT NULL,
  request_correlation_id UUID NOT NULL,
  status                 VARCHAR(20) NOT NULL CHECK (status IN ('processing', 'completed', 'no_turn', 'failed')),
  user_turn_id           UUID REFERENCES practice_turns(id) ON DELETE SET NULL,
  coach_turn_id          UUID REFERENCES practice_turns(id) ON DELETE SET NULL,
  retry_prompt           TEXT,
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

  UNIQUE (session_id, idempotency_key)
);

CREATE INDEX idx_practice_turn_requests_correlation
  ON practice_turn_requests(request_correlation_id);

CREATE INDEX idx_practice_turn_requests_session_status
  ON practice_turn_requests(session_id, status);
```

Lightweight idempotency table for `POST /practice/sessions/{id}/turns`. It links a
client retry key to the stored user/coach turn pair, or to a no-turn retry response
when STT was blank or low-confidence. It intentionally stores no raw audio,
transcript, prompt, or model output text; cost and latency aggregation happens via
`request_correlation_id` in `ai_request_logs`.

### tts_audio_cache

```sql
CREATE TABLE tts_audio_cache (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  text_hash     VARCHAR(64)  NOT NULL,             -- sha256(text_content)
  text_content  TEXT         NOT NULL,
  voice_id      VARCHAR(50)  NOT NULL,
  model_name    VARCHAR(50)  NOT NULL,
  audio_s3_key  VARCHAR(255) NOT NULL,
  duration_ms   INTEGER,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  expires_at    TIMESTAMPTZ,                       -- NULL = no expiry

  UNIQUE (text_hash, voice_id, model_name)
);

CREATE INDEX idx_tts_cache_lookup ON tts_audio_cache(text_hash, voice_id, model_name);
CREATE INDEX idx_tts_cache_expiry ON tts_audio_cache(expires_at) WHERE expires_at IS NOT NULL;
```

Content-keyed TTS audio cache. The unique constraint on `(text_hash, voice_id, model_name)` ensures one cache row per logical audio output. The same English text spoken in Mia's voice vs David's voice produces two separate rows (different `voice_id`).

When a screen needs TTS:
1. Compute `sha256(text)` for the lookup
2. `SELECT` against `(text_hash, voice_id, model_name)` — cache hit returns `audio_s3_key`
3. Cache miss → call OpenAI TTS → `INSERT` row → `UPDATE` the referencing row (`expression_variants.tts_audio_cache_id` or `practice_turns.tts_audio_cache_id`)

`text_content` is denormalized for debugging (the hash alone is opaque) and to support cache audits without recomputing hashes.

`expires_at` default policy: **TBD in W1-3**. Two candidates: no expiry (audio is small, S3 storage cheap) vs 90-day expiry (clean unused entries). The choice affects a daily cleanup job design.

### ai_request_logs

```sql
CREATE TABLE ai_request_logs (
  id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id                UUID REFERENCES users(id) ON DELETE SET NULL,  -- nullable for pre-signup
  feature_name           VARCHAR(50) NOT NULL,
  model_name             VARCHAR(50) NOT NULL,
  prompt_version         VARCHAR(20),                                    -- NULL for non-LLM calls (STT, TTS)
  input_tokens           INTEGER,
  output_tokens          INTEGER,
  latency_ms             INTEGER NOT NULL,
  estimated_cost_usd     NUMERIC(10, 6),
  status                 VARCHAR(20) NOT NULL,                           -- 'success' | 'error' | 'timeout' | 'cache_hit'
  error_code             VARCHAR(50),
  request_correlation_id UUID,                                           -- groups STT + LLM + TTS in one pipeline run
  attempt_group_id       UUID NOT NULL,                                  -- groups the attempts of one logical call
  attempt_number         SMALLINT NOT NULL DEFAULT 1,                    -- 1-based; 2+ means this row is a retry
  is_final_attempt       BOOLEAN NOT NULL DEFAULT true,                  -- the attempt whose outcome the caller saw
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_logs_feature_date ON ai_request_logs(feature_name, created_at DESC);
CREATE INDEX idx_logs_correlation ON ai_request_logs(request_correlation_id);
CREATE INDEX idx_logs_user_date ON ai_request_logs(user_id, created_at DESC) WHERE user_id IS NOT NULL;
CREATE INDEX idx_logs_status_date ON ai_request_logs(status, created_at DESC) WHERE status != 'success';
CREATE INDEX idx_logs_attempt_group ON ai_request_logs(attempt_group_id, attempt_number);
```

Core observability table. **One row per attempt**, not per logical call: a call that is
retried under the `AI_PIPELINE.md` fallback policy writes one row per attempt, all sharing
an `attempt_group_id`. Both successes and failures are written. PRD section 6 Cross-cutting
requirements depend on this table.

The per-attempt grain exists because retries consume billed tokens. Folding them into one
row made failed attempts invisible to cost sums and made the retry rate unmeasurable in
SQL. See ADR-011.

`feature_name` enumerated values (defined in `AI_PIPELINE.md`):

- `s07_analysis`
- `roleplay_session_init`
- `roleplay_turn_response`
- `roleplay_turn_feedback`
- `roleplay_result`
- `stt_transcription`
- `tts_synthesis`

`request_correlation_id` groups all calls produced by one user action. Example: a single S12 user turn produces four log groups that share a correlation id — `stt_transcription`, `roleplay_turn_response`, `roleplay_turn_feedback`, `tts_synthesis`. This enables end-to-end latency and cost roll-ups per turn.

Two levels of grouping, outer to inner:

| Column | Groups | Cardinality |
| --- | --- | --- |
| `request_correlation_id` | one user action | 1 action → N logical calls |
| `attempt_group_id` | one logical call | 1 call → 1..3 attempt rows |

Retries exist only on the Claude path (`AI_PIPELINE.md` fallback policy). STT and TTS calls
never retry, so their groups always hold exactly one row.

`attempt_number` is 1-based and dense within a group. `is_final_attempt` is true on exactly
one row per group — the attempt whose outcome the caller and the end user actually saw.
Queries that mean "how many requests" filter `WHERE is_final_attempt`; queries that mean
"how much did we spend" or "how often do we retry" must not filter at all.

`prompt_version` is the version of the loaded prompt file. The constraint reminder appended
on a schema-validation retry does not change it — that a row carried the reminder is derived
from `attempt_number > 1` plus the preceding attempt's `error_code`, not stored.

Per-attempt values: `latency_ms`, `input_tokens`, `output_tokens`, and `estimated_cost_usd`
each describe **that one attempt**, never a roll-up. Inter-attempt backoff is excluded from
every row's `latency_ms`. It is not stored: the backoff schedule is deterministic from the
first attempt's `error_code` and the attempt number (`AI_PIPELINE.md` fallback policy), and a
non-final row is written after its backoff elapses, so `created_at` gaps do not measure it.

`estimated_cost_usd` distinguishes NULL from zero: NULL means the cost is unknown (the
provider returned no usage, or the attempt timed out with no response), while `0` means a
known-free call. Only `cache_hit` rows carry a known `0`. Cost sums are therefore a lower
bound, and the share of NULL-cost rows is itself worth watching.

The partial index `idx_logs_status_date` accelerates the most common production diagnostic query ("recent failures across all features"). Under the per-attempt grain it also surfaces retried-then-recovered failures, which the `architecture.md` degradation alert depends on.

`idx_logs_attempt_group` serves per-call roll-ups and final-attempt lookup (for example
`analysis_requests.ai_request_log_id`, which must resolve to the final attempt).

## Schema dependency order

Tables must be created in this order due to FK constraints:

1. `users`
2. `coach_profiles`
3. `landing_examples`
4. `anonymous_analysis_usage`
5. `tts_audio_cache`
6. `ai_request_logs`
7. `user_auth_identities`
8. `analysis_requests`
9. `practice_sessions`
10. `expressions` (without `selected_variant_id` FK initially)
11. `expression_variants`
12. `ALTER TABLE expressions` to add `selected_variant_id` FK
13. `review_cards`
14. `review_attempts`
15. `practice_turns`
16. `practice_turn_requests`

Migration tool (Flyway recommended for Spring Boot) handles this ordering automatically when versioned migration files are named sequentially.

## Open questions affecting schema

Resolve before W4:

1. ~~**`review_cards.current_interval_days` initial value**~~ — Resolved 2026-06-10: `next_review_at = now()`, `current_interval_days = 1`. See `review_cards` section.
2. ~~**Abandoned `practice_sessions` and daily limit counting**~~ — Resolved in #59: `abandoned` rows do **not** count toward the 2-per-day cap; the daily-count query filters `status != 'abandoned'`. See `docs/screens/s12.md` US3-AC2.
3. **`coach_profiles` cascade behavior** — coach rows are seed data and v1 will not delete them. Cascade rules for `users.selected_coach_id` and `practice_sessions.coach_id` lack explicit `ON DELETE` action.
4. **`anonymous_analysis_usage` cleanup window** — default is 30 days. Confirm or change retention.
5. **`tts_audio_cache.expires_at` policy** — no expiry vs 90-day expiry. Affects daily cleanup job design.

## Deferred to v1.1+

- **`eval_cases` / `eval_runs` tables** — ADR-003 Tier 3 (golden dataset + dashboard) is post-launch. v1 ships Tier 1 S07 mini eval as JSON files (`eval/s07-analysis/*.json`). When Tier 3 starts, add these tables.
- **`practice_turns.message_index`** — current `turn_number` per row is sufficient for v1 (one row = one utterance). If a future feature requires sub-turn message ordering (e.g., showing user's transcript and feedback as separate timeline events), this column can be added.

## Related

- ADR-005 — Stack including RDS PostgreSQL.
- ADR-002 — Cumulative bookshelf; motivates the distinction between user-initiated soft delete and absence-based decay on `expressions`.
- ADR-001 — Pipeline whose calls populate `ai_request_logs`.
- ADR-003 — Eval system; Tier 3 will add `eval_*` tables post-launch.
- PRD §6 Cross-cutting requirements — defines `ai_request_logs` as required observability.
- AI_PIPELINE.md — JSONB column internal structures, `feature_name` enumeration, TTS cache lookup pattern.
- docs/screens/s02.md — pre-signup analysis flow and `anonymous_analysis_usage` enforcement.
- docs/screens/s03b.md — `users.is_onboarded` gating.
- docs/screens/s07.md — save flow creating expressions + expression_variants rows.
- docs/screens/s09.md — soft delete on expressions, `removed_from_queue_at` semantics.
- docs/screens/s10.md — review interval behavior and `review_attempts` writes.
- docs/screens/s11.md — account deletion grace flow.
- docs/screens/s12.md — practice session lifecycle, `practice_turns` writes, daily limit semantics.
