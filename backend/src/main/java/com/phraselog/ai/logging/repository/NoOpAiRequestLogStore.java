package com.phraselog.ai.logging.repository;

import com.phraselog.ai.logging.dto.AiRequestLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Store used when no {@code DataSource} is available (the scaffold app context test, or a local run
 * started without DB env). It drops the row and warns rather than failing startup, so the absence
 * of a database never blocks the application from loading.
 */
public class NoOpAiRequestLogStore implements AiRequestLogStore {

  private static final Logger log = LoggerFactory.getLogger(NoOpAiRequestLogStore.class);

  @Override
  public void save(AiRequestLogEntry entry) {
    log.warn(
        "No DataSource configured; dropping ai_request_logs entry feature={} status={} correlation={}",
        entry.feature().wireName(),
        entry.status().wireName(),
        entry.requestCorrelationId());
  }
}
