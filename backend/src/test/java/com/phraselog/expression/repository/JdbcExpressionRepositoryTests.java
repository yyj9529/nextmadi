package com.phraselog.expression.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
