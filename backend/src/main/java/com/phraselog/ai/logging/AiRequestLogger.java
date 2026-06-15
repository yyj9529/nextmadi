package com.phraselog.ai.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Public entry point the AI pipeline (issues #26/#29/#30/#39/#60/#62) calls to record one
 * AI/STT/TTS call. Inject this, build an {@link AiRequestLogEntry}, and call {@link #log}.
 *
 * <p><strong>Failure handling.</strong> A logging failure must never take down the user-facing AI
 * call. {@link #log} therefore swallows any persistence exception and reports it at WARN with the
 * correlation id; it does not propagate. The trade-off — a logging-layer bug could silently lose a
 * cost/latency row rather than surface as a request error — is the intended one for an
 * observability side-channel.
 */
@Component
public class AiRequestLogger {

  private static final Logger log = LoggerFactory.getLogger(AiRequestLogger.class);

  private final AiRequestLogStore store;

  public AiRequestLogger(AiRequestLogStore store) {
    this.store = store;
  }

  /** Records one call. Never throws; persistence errors are logged and swallowed. */
  public void log(AiRequestLogEntry entry) {
    if (entry == null) {
      log.warn("Ignoring null ai_request_logs entry");
      return;
    }
    try {
      store.save(entry);
    } catch (RuntimeException ex) {
      log.warn(
          "Failed to persist ai_request_logs entry feature={} status={} correlation={}: {}",
          entry.feature().wireName(),
          entry.status().wireName(),
          entry.requestCorrelationId(),
          ex.toString());
    }
  }
}
