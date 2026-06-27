package com.phraselog.tts.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** {@link JdbcTtsAudioCacheRepository}를 실제 Postgres에 대해 검증한다(#30). Docker 없으면 skip. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcTtsAudioCacheRepositoryIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("phraselog_test")
          .withUsername("phraselog")
          .withPassword("phraselog");

  private static DataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private JdbcTtsAudioCacheRepository repository;

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
    jdbcTemplate.update("DELETE FROM expression_variants");
    jdbcTemplate.update("DELETE FROM expressions");
    jdbcTemplate.update("DELETE FROM analysis_requests");
    jdbcTemplate.update("DELETE FROM tts_audio_cache");
    jdbcTemplate.update("DELETE FROM users");
    repository =
        new JdbcTtsAudioCacheRepository(
            jdbcTemplate, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

  @Test
  void insertThenFindByKeyRoundtrips() {
    TtsAudioCacheRow inserted =
        repository.insertAndLink(
            new InsertTtsCacheCommand(
                "hash-a", "Hello there", "shimmer", "tts-1", "tts/shimmer/hash-a.mp3", 2606, null),
            null);

    assertThat(inserted.id()).isNotNull();

    Optional<TtsAudioCacheRow> found = repository.findByKey("hash-a", "shimmer", "tts-1");
    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(inserted.id());
    assertThat(found.get().audioS3Key()).isEqualTo("tts/shimmer/hash-a.mp3");
    assertThat(found.get().durationMs()).isEqualTo(2606);
  }

  @Test
  void findByKeyDistinguishesVoiceAndModel() {
    repository.insertAndLink(
        new InsertTtsCacheCommand(
            "hash-b", "Hi", "shimmer", "tts-1", "tts/shimmer/hash-b.mp3", 500, null),
        null);

    assertThat(repository.findByKey("hash-b", "shimmer", "tts-1")).isPresent();
    assertThat(repository.findByKey("hash-b", "nova", "tts-1")).isEmpty();
    assertThat(repository.findByKey("hash-b", "shimmer", "tts-1-hd")).isEmpty();
  }

  @Test
  void duplicateContentKeyThrowsDuplicateKeyException() {
    InsertTtsCacheCommand command =
        new InsertTtsCacheCommand(
            "hash-c", "Hi", "shimmer", "tts-1", "tts/shimmer/hash-c.mp3", 500, null);
    repository.insertAndLink(command, null);

    assertThatThrownBy(() -> repository.insertAndLink(command, null))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void nullDurationIsStoredAsNull() {
    TtsAudioCacheRow inserted =
        repository.insertAndLink(
            new InsertTtsCacheCommand(
                "hash-d", "Hi", "shimmer", "tts-1", "tts/shimmer/hash-d.mp3", null, null),
            null);

    assertThat(inserted.durationMs()).isNull();
    assertThat(repository.findByKey("hash-d", "shimmer", "tts-1").get().durationMs()).isNull();
  }

  @Test
  void insertAndLinkUpdatesExpressionVariant() {
    UUID variantId = seedExpressionVariant();

    TtsAudioCacheRow inserted =
        repository.insertAndLink(
            new InsertTtsCacheCommand(
                "hash-e", "Linked", "shimmer", "tts-1", "tts/shimmer/hash-e.mp3", 800, null),
            variantId);

    UUID linkedCacheId =
        jdbcTemplate.queryForObject(
            "SELECT tts_audio_cache_id FROM expression_variants WHERE id = ?",
            UUID.class,
            variantId);
    assertThat(linkedCacheId).isEqualTo(inserted.id());
  }

  /** users → analysis_requests → expressions(analysis) → expression_variants 최소 체인. */
  private UUID seedExpressionVariant() {
    UUID userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, email) VALUES (?, ?)", userId, userId + "@example.com");
    UUID analysisId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO analysis_requests (id, user_id, input_text, output_json, prompt_version)"
            + " VALUES (?, ?, ?, '{}'::jsonb, 'v1')",
        analysisId,
        userId,
        "hello");
    UUID expressionId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO expressions (id, user_id, source_type, analysis_request_id, original_situation)"
            + " VALUES (?, ?, 'analysis', ?, ?)",
        expressionId,
        userId,
        analysisId,
        "ordering coffee");
    UUID variantId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO expression_variants (id, expression_id, variant_order, english_text)"
            + " VALUES (?, ?, 1, ?)",
        variantId,
        expressionId,
        "Could I get a coffee?");
    return variantId;
  }
}
