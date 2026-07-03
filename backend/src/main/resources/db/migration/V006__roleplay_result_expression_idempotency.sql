ALTER TABLE expressions
  ADD COLUMN roleplay_result_index INTEGER,
  ADD COLUMN roleplay_save_idempotency_key UUID;

ALTER TABLE expressions
  ADD CONSTRAINT chk_expressions_roleplay_result_index
  CHECK (roleplay_result_index IS NULL OR roleplay_result_index BETWEEN 0 AND 2);

CREATE UNIQUE INDEX uq_expressions_roleplay_save_idem
  ON expressions (user_id, practice_session_id, roleplay_save_idempotency_key)
  WHERE source_type = 'roleplay_result'
    AND roleplay_save_idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX uq_expressions_roleplay_result_index_active
  ON expressions (user_id, practice_session_id, roleplay_result_index)
  WHERE source_type = 'roleplay_result'
    AND deleted_at IS NULL
    AND roleplay_result_index IS NOT NULL;
