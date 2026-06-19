package com.phraselog.ai.logging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.phraselog.ai.logging.dto.AiFeature;
import com.phraselog.ai.logging.dto.AiRequestLogEntry;
import com.phraselog.ai.logging.dto.AiRequestStatus;
import com.phraselog.ai.logging.repository.AiRequestLogStore;
import com.phraselog.ai.logging.repository.NoOpAiRequestLogStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiRequestLoggerTests {

  private static AiRequestLogEntry sampleEntry() {
    return AiRequestLogEntry.builder()
        .feature(AiFeature.S07_ANALYSIS)
        .modelName("claude-sonnet-4-6")
        .status(AiRequestStatus.SUCCESS)
        .latencyMs(1234)
        .requestCorrelationId(UUID.randomUUID())
        .build();
  }

  @Test
  void delegatesToStore() {
    List<AiRequestLogEntry> saved = new ArrayList<>();
    AiRequestLogger logger = new AiRequestLogger(saved::add);

    AiRequestLogEntry entry = sampleEntry();
    logger.log(entry);

    assertThat(saved).containsExactly(entry);
  }

  @Test
  void swallowsStorePersistenceFailure() {
    AiRequestLogStore failing =
        entry -> {
          throw new RuntimeException("db down");
        };
    AiRequestLogger logger = new AiRequestLogger(failing);

    // Observability must never break the AI call path.
    assertThatCode(() -> logger.log(sampleEntry())).doesNotThrowAnyException();
  }

  @Test
  void ignoresNullEntryWithoutTouchingStore() {
    List<AiRequestLogEntry> saved = new ArrayList<>();
    AiRequestLogger logger = new AiRequestLogger(saved::add);

    assertThatCode(() -> logger.log(null)).doesNotThrowAnyException();
    assertThat(saved).isEmpty();
  }

  @Test
  void noOpStoreNeverThrows() {
    AiRequestLogger logger = new AiRequestLogger(new NoOpAiRequestLogStore());

    assertThatCode(() -> logger.log(sampleEntry())).doesNotThrowAnyException();
  }
}
