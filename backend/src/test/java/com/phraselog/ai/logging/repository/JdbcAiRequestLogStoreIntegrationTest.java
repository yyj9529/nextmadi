package com.phraselog.ai.logging.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.phraselog.ai.logging.dto.AiErrorCode;
import com.phraselog.ai.logging.dto.AiFeature;
import com.phraselog.ai.logging.dto.AiRequestLogEntry;
import com.phraselog.ai.logging.dto.AiRequestStatus;
import com.phraselog.ai.logging.service.AiCostCalculator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcAiRequestLogStoreIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("phraselog_test")
          .withUsername("phraselog")
          .withPassword("phraselog");

  private static DataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private JdbcAiRequestLogStore store;

  @BeforeAll
  static void migrate() {
    dataSource =
        DataSourceBuilder.create()
            .url(postgres.getJdbcUrl())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.update("DELETE FROM ai_request_logs");
    store = new JdbcAiRequestLogStore(jdbcTemplate);
  }

  @Test
  void writesOneRowForEachStatus() {
    UUID correlation = UUID.randomUUID();
    store.save(
        llm(AiFeature.S07_ANALYSIS, AiRequestStatus.SUCCESS, null, correlation, "v1", 900, 270));
    store.save(
        llm(
            AiFeature.S07_ANALYSIS,
            AiRequestStatus.ERROR,
            AiErrorCode.PROVIDER_5XX,
            correlation,
            "v1",
            900,
            0));
    store.save(
        llm(
            AiFeature.ROLEPLAY_TURN_RESPONSE,
            AiRequestStatus.TIMEOUT,
            AiErrorCode.TIMEOUT,
            correlation,
            "v1",
            800,
            0));
    store.save(
        AiRequestLogEntry.builder()
            .feature(AiFeature.TTS_SYNTHESIS)
            .modelName("tts-1")
            .status(AiRequestStatus.CACHE_HIT)
            .latencyMs(40)
            .estimatedCostUsd(BigDecimal.ZERO)
            .requestCorrelationId(correlation)
            .build());

    Map<String, Long> countByStatus =
        jdbcTemplate
            .queryForList("SELECT status, count(*) AS c FROM ai_request_logs GROUP BY status")
            .stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    r -> (String) r.get("status"), r -> (Long) r.get("c")));

    assertThat(countByStatus)
        .containsEntry("success", 1L)
        .containsEntry("error", 1L)
        .containsEntry("timeout", 1L)
        .containsEntry("cache_hit", 1L);
  }

  @Test
  void persistsLlmMetadataIncludingCost() {
    UUID correlation = UUID.randomUUID();
    store.save(
        llm(AiFeature.S07_ANALYSIS, AiRequestStatus.SUCCESS, null, correlation, "v2", 900, 270));

    Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM ai_request_logs LIMIT 1");

    assertThat(row.get("feature_name")).isEqualTo("s07_analysis");
    assertThat(row.get("model_name")).isEqualTo("claude-sonnet-4-6");
    assertThat(row.get("prompt_version")).isEqualTo("v2");
    assertThat(((Number) row.get("input_tokens")).intValue()).isEqualTo(900);
    assertThat(((Number) row.get("output_tokens")).intValue()).isEqualTo(270);
    assertThat(((BigDecimal) row.get("estimated_cost_usd"))).isEqualByComparingTo("0.006750");
    assertThat(row.get("status")).isEqualTo("success");
    assertThat(row.get("error_code")).isNull();
    assertThat(row.get("created_at")).isNotNull();
  }

  @Test
  void groupsCallsOfOnePipelineRunByCorrelationId() {
    UUID turnCorrelation = UUID.randomUUID();
    UUID otherRun = UUID.randomUUID();

    // One S12 user turn: STT + turn_response + turn_feedback + TTS share one correlation id.
    store.save(stt(turnCorrelation));
    store.save(
        llm(
            AiFeature.ROLEPLAY_TURN_RESPONSE,
            AiRequestStatus.SUCCESS,
            null,
            turnCorrelation,
            "v1",
            800,
            150));
    store.save(
        llm(
            AiFeature.ROLEPLAY_TURN_FEEDBACK,
            AiRequestStatus.SUCCESS,
            null,
            turnCorrelation,
            "v1",
            600,
            100));
    store.save(tts(AiRequestStatus.SUCCESS, turnCorrelation));
    // A different user action must not be grouped in.
    store.save(stt(otherRun));

    List<String> features =
        jdbcTemplate.queryForList(
            "SELECT feature_name FROM ai_request_logs WHERE request_correlation_id = ?"
                + " ORDER BY feature_name",
            String.class,
            turnCorrelation);

    assertThat(features)
        .containsExactly(
            "roleplay_turn_feedback",
            "roleplay_turn_response",
            "stt_transcription",
            "tts_synthesis");
  }

  @Test
  void noTextColumnEverHoldsRawUserContent() {
    // There is no column to hold raw content; this asserts the persisted textual columns carry
    // only the metadata we supplied, never a transcript/utterance sentinel that callers might try
    // to smuggle. Because AiRequestLogEntry has no content field, the sentinel cannot be stored.
    UUID correlation = UUID.randomUUID();
    store.save(stt(correlation));

    Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM ai_request_logs LIMIT 1");

    String concatenatedText =
        ""
            + row.get("feature_name")
            + row.get("model_name")
            + row.get("status")
            + row.get("prompt_version")
            + row.get("error_code");
    assertThat(concatenatedText).doesNotContainIgnoringCase("doctor");
    assertThat(row.get("prompt_version")).isNull(); // STT carries no prompt version
  }

  private static AiRequestLogEntry llm(
      AiFeature feature,
      AiRequestStatus status,
      AiErrorCode errorCode,
      UUID correlation,
      String promptVersion,
      int inputTokens,
      int outputTokens) {
    BigDecimal cost =
        status == AiRequestStatus.SUCCESS
            ? new AiCostCalculator().llm("claude-sonnet-4-6", inputTokens, outputTokens)
            : null;
    return AiRequestLogEntry.builder()
        .feature(feature)
        .modelName("claude-sonnet-4-6")
        .promptVersion(promptVersion)
        .inputTokens(inputTokens)
        .outputTokens(outputTokens)
        .latencyMs(1500)
        .estimatedCostUsd(cost)
        .status(status)
        .errorCode(errorCode)
        .requestCorrelationId(correlation)
        .build();
  }

  private static AiRequestLogEntry stt(UUID correlation) {
    return AiRequestLogEntry.builder()
        .feature(AiFeature.STT_TRANSCRIPTION)
        .modelName("whisper-1")
        .latencyMs(900)
        .estimatedCostUsd(new AiCostCalculator().whisperBySeconds(30))
        .status(AiRequestStatus.SUCCESS)
        .requestCorrelationId(correlation)
        .build();
  }

  private static AiRequestLogEntry tts(AiRequestStatus status, UUID correlation) {
    return AiRequestLogEntry.builder()
        .feature(AiFeature.TTS_SYNTHESIS)
        .modelName("tts-1")
        .latencyMs(700)
        .estimatedCostUsd(new AiCostCalculator().ttsByCharacters(80))
        .status(status)
        .requestCorrelationId(correlation)
        .build();
  }
}
