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

/** Verifies {@link JdbcTtsAudioCacheRepository} against Postgres for ticket #30. */
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
        repository.insert(
            new InsertTtsCacheCommand(
                "hash-a", "Hello there", "shimmer", "tts-1", "tts/shimmer/hash-a.mp3", 2606, null));

    assertThat(inserted.id()).isNotNull();

    Optional<TtsAudioCacheRow> found = repository.findByKey("hash-a", "shimmer", "tts-1");
    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(inserted.id());
    assertThat(found.get().audioS3Key()).isEqualTo("tts/shimmer/hash-a.mp3");
    assertThat(found.get().durationMs()).isEqualTo(2606);
  }

  @Test
  void findByKeyDistinguishesVoiceAndModel() {
    repository.insert(
        new InsertTtsCacheCommand(
            "hash-b", "Hi", "shimmer", "tts-1", "tts/shimmer/hash-b.mp3", 500, null));

    assertThat(repository.findByKey("hash-b", "shimmer", "tts-1")).isPresent();
    assertThat(repository.findByKey("hash-b", "nova", "tts-1")).isEmpty();
    assertThat(repository.findByKey("hash-b", "shimmer", "tts-1-hd")).isEmpty();
  }

  @Test
  void duplicateContentKeyThrowsDuplicateKeyException() {
    InsertTtsCacheCommand command =
        new InsertTtsCacheCommand(
            "hash-c", "Hi", "shimmer", "tts-1", "tts/shimmer/hash-c.mp3", 500, null);
    repository.insert(command);

    assertThatThrownBy(() -> repository.insert(command)).isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void nullDurationIsStoredAsNull() {
    TtsAudioCacheRow inserted =
        repository.insert(
            new InsertTtsCacheCommand(
                "hash-d", "Hi", "shimmer", "tts-1", "tts/shimmer/hash-d.mp3", null, null));

    assertThat(inserted.durationMs()).isNull();
    assertThat(repository.findByKey("hash-d", "shimmer", "tts-1").get().durationMs()).isNull();
  }

  @Test
  void linkVariantUpdatesOwnedMatchingExpressionVariant() {
    SeededVariant seeded = seedExpressionVariant("Linked");
    TtsAudioCacheRow inserted =
        repository.insert(
            new InsertTtsCacheCommand(
                "hash-e", "Linked", "shimmer", "tts-1", "tts/shimmer/hash-e.mp3", 800, null));

    assertThat(repository.isLinkableVariant(seeded.variantId(), seeded.userId(), "Linked"))
        .isTrue();
    assertThat(repository.linkVariant(inserted.id(), seeded.variantId(), seeded.userId(), "Linked"))
        .isTrue();

    UUID linkedCacheId =
        jdbcTemplate.queryForObject(
            "SELECT tts_audio_cache_id FROM expression_variants WHERE id = ?",
            UUID.class,
            seeded.variantId());
    assertThat(linkedCacheId).isEqualTo(inserted.id());
  }

  @Test
  void linkVariantRejectsWrongOwner() {
    SeededVariant seeded = seedExpressionVariant("Owner safe");
    TtsAudioCacheRow inserted =
        repository.insert(
            new InsertTtsCacheCommand(
                "hash-f", "Owner safe", "shimmer", "tts-1", "tts/shimmer/hash-f.mp3", 800, null));

    assertThat(repository.isLinkableVariant(seeded.variantId(), UUID.randomUUID(), "Owner safe"))
        .isFalse();
    assertThat(
            repository.linkVariant(
                inserted.id(), seeded.variantId(), UUID.randomUUID(), "Owner safe"))
        .isFalse();
    assertThat(currentLinkedCacheId(seeded.variantId())).isNull();
  }

  @Test
  void linkVariantRejectsMismatchedText() {
    SeededVariant seeded = seedExpressionVariant("Exact text");
    TtsAudioCacheRow inserted =
        repository.insert(
            new InsertTtsCacheCommand(
                "hash-g", "Other text", "shimmer", "tts-1", "tts/shimmer/hash-g.mp3", 800, null));

    assertThat(repository.isLinkableVariant(seeded.variantId(), seeded.userId(), "Other text"))
        .isFalse();
    assertThat(
            repository.linkVariant(
                inserted.id(), seeded.variantId(), seeded.userId(), "Other text"))
        .isFalse();
    assertThat(currentLinkedCacheId(seeded.variantId())).isNull();
  }

  @Test
  void isLinkableVariantRejectsDeletedExpression() {
    SeededVariant seeded = seedExpressionVariant("Soft deleted");
    jdbcTemplate.update(
        "UPDATE expressions SET deleted_at = NOW() WHERE id = ?", seeded.expressionId());

    assertThat(repository.isLinkableVariant(seeded.variantId(), seeded.userId(), "Soft deleted"))
        .isFalse();
  }

  private UUID currentLinkedCacheId(UUID variantId) {
    return jdbcTemplate.queryForObject(
        "SELECT tts_audio_cache_id FROM expression_variants WHERE id = ?", UUID.class, variantId);
  }

  private SeededVariant seedExpressionVariant(String englishText) {
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
        englishText);
    return new SeededVariant(userId, expressionId, variantId);
  }

  private record SeededVariant(UUID userId, UUID expressionId, UUID variantId) {}
}
