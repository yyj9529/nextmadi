package com.phraselog.ai.logging;

/**
 * Persistence boundary for {@code ai_request_logs}.
 *
 * <p>Implementations are selected at wiring time: {@link JdbcAiRequestLogStore} when a {@code
 * DataSource} is present, {@link NoOpAiRequestLogStore} otherwise (e.g. the no-DB app context).
 * Callers should go through {@link AiRequestLogger}, not this interface directly.
 */
public interface AiRequestLogStore {

  /** Inserts exactly one row. May throw if the underlying datastore fails. */
  void save(AiRequestLogEntry entry);
}
