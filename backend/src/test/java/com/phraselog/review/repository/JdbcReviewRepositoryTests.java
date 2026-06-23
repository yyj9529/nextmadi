package com.phraselog.review.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.phraselog.review.dto.ReviewCardResponse;
import java.time.OffsetDateTime;
import java.util.List;
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
class JdbcReviewRepositoryTests {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("phraselog_test")
          .withUsername("phraselog")
          .withPassword("phraselog");

  private static DataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private JdbcReviewRepository repository;

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
    jdbcTemplate.update("DELETE FROM review_attempts");
    jdbcTemplate.update("DELETE FROM review_cards");
    jdbcTemplate.update("DELETE FROM expression_variants");
    jdbcTemplate.update("DELETE FROM expressions");
    jdbcTemplate.update("DELETE FROM analysis_requests");
    jdbcTemplate.update("DELETE FROM ai_request_logs");
    jdbcTemplate.update("DELETE FROM users");
    repository = new JdbcReviewRepository(jdbcTemplate);
  }

  @Test
  void findDueCardsAppliesAllThreeFilters() {
    UUID userId = insertUser("owner@example.com");
    UUID analysisId = insertAnalysis(userId);
    OffsetDateTime past = OffsetDateTime.now().minusDays(1);
    OffsetDateTime future = OffsetDateTime.now().plusDays(1);

    UUID dueExpr =
        insertExpression(userId, analysisId, "due 상황", "I'd like an appointment.", false);
    UUID dueCard = insertReviewCard(userId, dueExpr, past, 1, null);

    UUID futureExpr = insertExpression(userId, analysisId, "future 상황", "later please", false);
    insertReviewCard(userId, futureExpr, future, 1, null);

    UUID removedExpr = insertExpression(userId, analysisId, "removed 상황", "paused please", false);
    insertReviewCard(userId, removedExpr, past, 1, OffsetDateTime.now());

    UUID deletedExpr = insertExpression(userId, analysisId, "deleted 상황", "gone please", true);
    insertReviewCard(userId, deletedExpr, past, 1, null);

    List<ReviewCardResponse> due = repository.findDueCards(userId, List.of(), 10);

    assertThat(due).extracting(ReviewCardResponse::id).containsExactly(dueCard);
    assertThat(due.get(0).variant().englishText()).isEqualTo("I'd like an appointment.");
    assertThat(due.get(0).originalSituation()).isEqualTo("due 상황");
    assertThat(repository.countDue(userId)).isEqualTo(1);
  }

  @Test
  void excludeIdsDropsLoadedCardsButTotalDueIgnoresThem() {
    UUID userId = insertUser("owner@example.com");
    UUID analysisId = insertAnalysis(userId);
    OffsetDateTime past = OffsetDateTime.now().minusDays(1);
    UUID exprA = insertExpression(userId, analysisId, "상황 A", "text A", false);
    UUID cardA = insertReviewCard(userId, exprA, past, 1, null);
    UUID exprB = insertExpression(userId, analysisId, "상황 B", "text B", false);
    UUID cardB = insertReviewCard(userId, exprB, past.plusMinutes(1), 1, null);

    List<ReviewCardResponse> due = repository.findDueCards(userId, List.of(cardA), 10);

    assertThat(due).extracting(ReviewCardResponse::id).containsExactly(cardB);
    // total_due는 exclude_ids를 무시한 안정적 분모.
    assertThat(repository.countDue(userId)).isEqualTo(2);
  }

  @Test
  void applySubmitAppendsAttemptAndUpdatesCard() {
    UUID userId = insertUser("owner@example.com");
    UUID analysisId = insertAnalysis(userId);
    UUID expr = insertExpression(userId, analysisId, "상황", "text", false);
    UUID card = insertReviewCard(userId, expr, OffsetDateTime.now().minusDays(1), 1, null);
    OffsetDateTime reviewedAt = OffsetDateTime.now();
    OffsetDateTime nextReviewAt = reviewedAt.plusDays(2);

    repository.applySubmit(card, userId, expr, "good", 1, 2, reviewedAt, nextReviewAt);

    Integer attemptCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM review_attempts WHERE review_card_id = ?", Integer.class, card);
    Integer attemptPrevious =
        jdbcTemplate.queryForObject(
            "SELECT previous_interval_days FROM review_attempts WHERE review_card_id = ?",
            Integer.class,
            card);
    Integer attemptNext =
        jdbcTemplate.queryForObject(
            "SELECT next_interval_days FROM review_attempts WHERE review_card_id = ?",
            Integer.class,
            card);
    String lastRating =
        jdbcTemplate.queryForObject(
            "SELECT last_rating FROM review_cards WHERE id = ?", String.class, card);
    Integer interval =
        jdbcTemplate.queryForObject(
            "SELECT current_interval_days FROM review_cards WHERE id = ?", Integer.class, card);

    assertThat(attemptCount).isEqualTo(1);
    assertThat(attemptPrevious).isEqualTo(1);
    assertThat(attemptNext).isEqualTo(2);
    assertThat(lastRating).isEqualTo("good");
    assertThat(interval).isEqualTo(2);
  }

  @Test
  void removeFromQueueExcludesCardThenReAddRestoresIt() {
    UUID userId = insertUser("owner@example.com");
    UUID analysisId = insertAnalysis(userId);
    UUID expr = insertExpression(userId, analysisId, "상황", "text", false);
    UUID card = insertReviewCard(userId, expr, OffsetDateTime.now().minusDays(1), 8, null);

    assertThat(repository.removeFromQueue(card, userId)).isTrue();
    assertThat(repository.findDueCards(userId, List.of(), 10)).isEmpty();

    assertThat(repository.reAddToQueue(card, userId)).isTrue();
    List<ReviewCardResponse> due = repository.findDueCards(userId, List.of(), 10);
    assertThat(due).extracting(ReviewCardResponse::id).containsExactly(card);
    // 재진입은 새 저장과 동일하게 interval=1로 리셋.
    assertThat(due.get(0).currentIntervalDays()).isEqualTo(1);

    // 이미 큐에 있으면 재진입은 false → 호출자가 409로 매핑.
    assertThat(repository.reAddToQueue(card, userId)).isFalse();
  }

  @Test
  void removeFromQueueReturnsFalseWhenNotOwned() {
    UUID userId = insertUser("owner@example.com");
    UUID otherUserId = insertUser("other@example.com");
    UUID analysisId = insertAnalysis(userId);
    UUID expr = insertExpression(userId, analysisId, "상황", "text", false);
    UUID card = insertReviewCard(userId, expr, OffsetDateTime.now().minusDays(1), 1, null);

    assertThat(repository.removeFromQueue(card, otherUserId)).isFalse();
    assertThat(repository.findCardForUser(card, otherUserId)).isEmpty();
    assertThat(repository.findCardForUser(card, userId)).isPresent();
  }

  private UUID insertExpression(
      UUID userId, UUID analysisId, String situation, String selectedEnglish, boolean deleted) {
    UUID expressionId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    jdbcTemplate.update(
        "INSERT INTO expressions"
            + " (id, user_id, source_type, analysis_request_id, original_situation, created_at,"
            + " deleted_at)"
            + " VALUES (?, ?, 'analysis', ?, ?, ?, ?)",
        expressionId,
        userId,
        analysisId,
        situation,
        now,
        deleted ? now : null);
    UUID variantId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO expression_variants"
            + " (id, expression_id, variant_order, tone_label, english_text, ipa,"
            + " korean_pronunciation, pronunciation_tip, cultural_tip, created_at)"
            + " VALUES (?, ?, 1, '정중한', ?, '/a/', '아이드', 'Keep it short.', 'Polite.', ?)",
        variantId,
        expressionId,
        selectedEnglish,
        now);
    jdbcTemplate.update(
        "UPDATE expressions SET selected_variant_id = ? WHERE id = ?", variantId, expressionId);
    return expressionId;
  }

  private UUID insertReviewCard(
      UUID userId,
      UUID expressionId,
      OffsetDateTime nextReviewAt,
      int currentIntervalDays,
      OffsetDateTime removedFromQueueAt) {
    UUID cardId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO review_cards"
            + " (id, user_id, expression_id, next_review_at, current_interval_days,"
            + " removed_from_queue_at, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, now())",
        cardId,
        userId,
        expressionId,
        nextReviewAt,
        currentIntervalDays,
        removedFromQueueAt);
    return cardId;
  }

  private UUID insertUser(String email) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO users (id, email) VALUES (?, ?)", id, email);
    return id;
  }

  private UUID insertAnalysis(UUID userId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO analysis_requests"
            + " (id, user_id, input_text, output_json, prompt_version)"
            + " VALUES (?, ?, ?, ?::jsonb, 's07-v1')",
        id,
        userId,
        "병원 예약 전화에서 말문이 막혔어요",
        "{\"expressions\":[]}");
    return id;
  }
}
