package com.phraselog.expression.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.phraselog.expression.dto.ExpressionListItem;
import com.phraselog.expression.dto.ExpressionResponse;
import com.phraselog.expression.dto.NewExpression;
import com.phraselog.expression.dto.NewExpressionVariant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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
class JdbcExpressionRepositoryTests {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("phraselog_test")
          .withUsername("phraselog")
          .withPassword("phraselog");

  private static DataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private JdbcExpressionRepository repository;

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
    jdbcTemplate.update("DELETE FROM review_cards");
    jdbcTemplate.update("DELETE FROM expression_variants");
    jdbcTemplate.update("DELETE FROM expressions");
    jdbcTemplate.update("DELETE FROM analysis_requests");
    jdbcTemplate.update("DELETE FROM ai_request_logs");
    jdbcTemplate.update("DELETE FROM users");
    repository = new JdbcExpressionRepository(jdbcTemplate);
  }

  @Test
  void createFromAnalysisCreatesExpressionVariantsAndImmediatelyDueReviewCard() {
    UUID userId = insertUser("owner@example.com");
    UUID analysisId = insertAnalysis(userId);

    ExpressionResponse response = repository.createFromAnalysis(command(userId, analysisId, 2));

    assertThat(response.sourceType()).isEqualTo("analysis");
    assertThat(response.analysisRequestId()).isEqualTo(analysisId);
    assertThat(response.practiceSessionId()).isNull();
    assertThat(response.originalSituation()).isEqualTo("병원 예약 전화에서 말문이 막혔어요");
    assertThat(response.variants()).hasSize(3);
    assertThat(response.selectedVariantId()).isEqualTo(response.variants().get(1).id());
    assertThat(response.reviewCardId()).isNotNull();
    assertThat(response.nextReviewAt()).isBeforeOrEqualTo(OffsetDateTime.now().plusSeconds(1));

    Integer expressionCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM expressions WHERE id = ?", Integer.class, response.id());
    Integer variantCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM expression_variants WHERE expression_id = ?",
            Integer.class,
            response.id());
    Integer interval =
        jdbcTemplate.queryForObject(
            "SELECT current_interval_days FROM review_cards WHERE id = ?",
            Integer.class,
            response.reviewCardId());

    assertThat(expressionCount).isEqualTo(1);
    assertThat(variantCount).isEqualTo(3);
    assertThat(interval).isEqualTo(1);
  }

  @Test
  void findByAnalysisIdForUserReturnsExistingSavedExpression() {
    UUID userId = insertUser("owner@example.com");
    UUID otherUserId = insertUser("other@example.com");
    UUID analysisId = insertAnalysis(userId);
    ExpressionResponse created = repository.createFromAnalysis(command(userId, analysisId, 1));

    Optional<ExpressionResponse> found = repository.findByAnalysisIdForUser(analysisId, userId);
    Optional<ExpressionResponse> otherUser =
        repository.findByAnalysisIdForUser(analysisId, otherUserId);

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(created.id());
    assertThat(found.get().variants()).hasSize(3);
    assertThat(otherUser).isEmpty();
  }

  // ── #45 list / detail / delete ──────────────────────────────────────────────

  @Test
  void listOrdersByCreatedAtDescThenIdDesc() {
    UUID userId = insertUser("owner@example.com");
    UUID analysisId = insertAnalysis(userId);
    OffsetDateTime t1 = OffsetDateTime.parse("2026-06-19T10:00:00Z");
    OffsetDateTime t2 = OffsetDateTime.parse("2026-06-19T11:00:00Z");
    UUID older = insertExpression(userId, analysisId, t1, "오래된 상황", englishTexts("older"), 1);
    // 같은 created_at의 두 행 — id DESC 타이브레이커 확인용.
    UUID tieSmall = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID tieLarge = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    insertExpressionWithId(tieSmall, userId, analysisId, t2, "동시 A", englishTexts("tieA"), 1);
    insertExpressionWithId(tieLarge, userId, analysisId, t2, "동시 B", englishTexts("tieB"), 1);

    List<ExpressionListItem> items = repository.list(userId, null, null, null, 10);

    assertThat(items).extracting(ExpressionListItem::id).containsExactly(tieLarge, tieSmall, older);
  }

  @Test
  void listPaginatesWithCursorAndEndsWithNullNextCursor() {
    UUID userId = insertUser("owner@example.com");
    UUID analysisId = insertAnalysis(userId);
    OffsetDateTime base = OffsetDateTime.parse("2026-06-19T10:00:00Z");
    for (int i = 0; i < 3; i++) {
      insertExpression(
          userId, analysisId, base.plusMinutes(i), "상황 " + i, englishTexts("text" + i), 1);
    }

    List<ExpressionListItem> page1 = repository.list(userId, null, null, null, 2);
    assertThat(page1).hasSize(2);
    ExpressionListItem last = page1.get(1);

    List<ExpressionListItem> page2 = repository.list(userId, null, last.createdAt(), last.id(), 2);
    assertThat(page2).hasSize(1);
    // 페이지가 안 겹친다.
    assertThat(page2.get(0).id()).isNotIn(page1.get(0).id(), page1.get(1).id());
  }

  @Test
  void softDeletedExpressionIsExcludedFromListAndDetail() {
    UUID userId = insertUser("owner@example.com");
    UUID analysisId = insertAnalysis(userId);
    UUID id =
        insertExpression(
            userId,
            analysisId,
            OffsetDateTime.parse("2026-06-19T10:00:00Z"),
            "삭제될 상황",
            englishTexts("doomed"),
            1);

    assertThat(repository.softDelete(id, userId)).isTrue();

    assertThat(repository.list(userId, null, null, null, 10)).isEmpty();
    assertThat(repository.findByIdForUser(id, userId)).isEmpty();
    // 두 번째 삭제는 0행 → false; 타 유저 삭제도 false.
    assertThat(repository.softDelete(id, userId)).isFalse();
  }

  @Test
  void searchMatchesKoreanSituation() {
    UUID userId = insertUser("owner@example.com");
    UUID analysisId = insertAnalysis(userId);
    insertExpression(
        userId,
        analysisId,
        OffsetDateTime.parse("2026-06-19T10:00:00Z"),
        "환불 요청 전화에서 막혔어요",
        englishTexts("refund please"),
        1);
    insertExpression(
        userId,
        analysisId,
        OffsetDateTime.parse("2026-06-19T11:00:00Z"),
        "병원 예약 상황",
        englishTexts("appointment"),
        1);

    List<ExpressionListItem> hits = repository.list(userId, "환불", null, null, 10);

    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).originalSituation()).contains("환불");
  }

  @Test
  void searchMatchesNonSelectedVariantEnglish() {
    UUID userId = insertUser("owner@example.com");
    UUID analysisId = insertAnalysis(userId);
    // selected variant(order 1)는 'apple'; 비-selected variant(order 3)에만 'banana'가 있다.
    UUID id =
        insertExpression(
            userId,
            analysisId,
            OffsetDateTime.parse("2026-06-19T10:00:00Z"),
            "과일 상황",
            List.of("I want an apple", "some orange", "a banana please"),
            1);

    List<ExpressionListItem> hits = repository.list(userId, "banana", null, null, 10);

    assertThat(hits).extracting(ExpressionListItem::id).containsExactly(id);
    // 목록 카드는 그래도 selected variant(apple)를 보여준다.
    assertThat(hits.get(0).englishText()).isEqualTo("I want an apple");
  }

  @Test
  void detailReturnsAllVariantsAndNullsReviewCardWhenRemovedFromQueue() {
    UUID userId = insertUser("owner@example.com");
    UUID analysisId = insertAnalysis(userId);
    UUID inQueue = repository.createFromAnalysis(command(userId, analysisId, 1)).id();
    UUID otherAnalysis = insertAnalysis(userId);
    UUID removed = repository.createFromAnalysis(command(userId, otherAnalysis, 1)).id();
    jdbcTemplate.update(
        "UPDATE review_cards SET removed_from_queue_at = now() WHERE expression_id = ?", removed);

    ExpressionResponse inQueueDetail = repository.findByIdForUser(inQueue, userId).orElseThrow();
    ExpressionResponse removedDetail = repository.findByIdForUser(removed, userId).orElseThrow();

    assertThat(inQueueDetail.variants()).hasSize(3);
    assertThat(inQueueDetail.reviewCardId()).isNotNull();
    assertThat(inQueueDetail.nextReviewAt()).isNotNull();
    // 큐에서 제거된 카드는 detail에서 null로 노출된다.
    assertThat(removedDetail.reviewCardId()).isNull();
    assertThat(removedDetail.nextReviewAt()).isNull();
  }

  @Test
  void findByIdForUserIsScopedToOwner() {
    UUID userId = insertUser("owner@example.com");
    UUID otherUserId = insertUser("other@example.com");
    UUID analysisId = insertAnalysis(userId);
    UUID id =
        insertExpression(
            userId,
            analysisId,
            OffsetDateTime.parse("2026-06-19T10:00:00Z"),
            "내 상황",
            englishTexts("mine"),
            1);

    assertThat(repository.findByIdForUser(id, userId)).isPresent();
    assertThat(repository.findByIdForUser(id, otherUserId)).isEmpty();
    assertThat(repository.softDelete(id, otherUserId)).isFalse();
  }

  private static List<String> englishTexts(String prefix) {
    return List.of(prefix + " one", prefix + " two", prefix + " three");
  }

  private UUID insertExpression(
      UUID userId,
      UUID analysisId,
      OffsetDateTime createdAt,
      String situation,
      List<String> englishTexts,
      int selectedOrder) {
    return insertExpressionWithId(
        UUID.randomUUID(), userId, analysisId, createdAt, situation, englishTexts, selectedOrder);
  }

  private UUID insertExpressionWithId(
      UUID expressionId,
      UUID userId,
      UUID analysisId,
      OffsetDateTime createdAt,
      String situation,
      List<String> englishTexts,
      int selectedOrder) {
    jdbcTemplate.update(
        "INSERT INTO expressions"
            + " (id, user_id, source_type, analysis_request_id, original_situation, created_at)"
            + " VALUES (?, ?, 'analysis', ?, ?, ?)",
        expressionId,
        userId,
        analysisId,
        situation,
        createdAt);
    UUID selectedVariantId = null;
    for (int order = 1; order <= englishTexts.size(); order++) {
      UUID variantId = UUID.randomUUID();
      jdbcTemplate.update(
          "INSERT INTO expression_variants"
              + " (id, expression_id, variant_order, tone_label, english_text, created_at)"
              + " VALUES (?, ?, ?, ?, ?, ?)",
          variantId,
          expressionId,
          order,
          "정중한",
          englishTexts.get(order - 1),
          createdAt);
      if (order == selectedOrder) {
        selectedVariantId = variantId;
      }
    }
    jdbcTemplate.update(
        "UPDATE expressions SET selected_variant_id = ? WHERE id = ?",
        selectedVariantId,
        expressionId);
    return expressionId;
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
        """
        {"expressions":[
          {"english":"I'd like to make an appointment.","tone_label":"정중한","ipa":"/a/","korean_pronunciation":"아이드","pronunciation_tip":"Keep it short.","cultural_tip":"Use would like for polite requests."},
          {"english":"Could I schedule a visit?","tone_label":"부드러운","ipa":"/b/","korean_pronunciation":"쿠드","pronunciation_tip":"Raise could slightly.","cultural_tip":"Schedule is natural for clinics."},
          {"english":"Is there any availability this week?","tone_label":"간접적인","ipa":"/c/","korean_pronunciation":"이즈","pronunciation_tip":"Link there any.","cultural_tip":"Availability is common for appointment slots."}
        ]}
        """);
    return id;
  }

  private static NewExpression command(UUID userId, UUID analysisId, int selectedOrder) {
    return new NewExpression(
        userId,
        analysisId,
        "병원 예약 전화에서 말문이 막혔어요",
        selectedOrder,
        List.of(
            new NewExpressionVariant(
                1,
                "정중한",
                "I'd like to make an appointment.",
                "/a/",
                "아이드",
                "Keep it short.",
                "Use would like for polite requests."),
            new NewExpressionVariant(
                2,
                "부드러운",
                "Could I schedule a visit?",
                "/b/",
                "쿠드",
                "Raise could slightly.",
                "Schedule is natural for clinics."),
            new NewExpressionVariant(
                3,
                "간접적인",
                "Is there any availability this week?",
                "/c/",
                "이즈",
                "Link there any.",
                "Availability is common for appointment slots.")));
  }
}
