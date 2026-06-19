package com.phraselog.expression.repository;

import com.phraselog.expression.dto.ExpressionResponse;
import com.phraselog.expression.dto.ExpressionVariantResponse;
import com.phraselog.expression.dto.NewExpression;
import com.phraselog.expression.dto.NewExpressionVariant;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

/** JdbcTemplate-backed persistence for {@code POST /expressions} (#41). */
public class JdbcExpressionRepository implements ExpressionRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcExpressionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<ExpressionResponse> findByAnalysisIdForUser(UUID analysisRequestId, UUID userId) {
    try {
      ExpressionRow row =
          jdbcTemplate.queryForObject(
              """
              SELECT e.id, e.source_type, e.analysis_request_id, e.practice_session_id,
                     e.original_situation, e.selected_variant_id, e.created_at,
                     rc.id AS review_card_id, rc.next_review_at
                FROM expressions e
                LEFT JOIN review_cards rc
                  ON rc.expression_id = e.id AND rc.user_id = e.user_id
               WHERE e.analysis_request_id = ? AND e.user_id = ?
               ORDER BY e.created_at DESC
               LIMIT 1
              """,
              expressionRowMapper(),
              analysisRequestId,
              userId);
      return Optional.of(toResponse(row));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  @Transactional
  public ExpressionResponse createFromAnalysis(NewExpression expression) {
    UUID expressionId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();

    jdbcTemplate.update(
        """
        INSERT INTO expressions
          (id, user_id, source_type, analysis_request_id, practice_session_id,
           original_situation, created_at)
        VALUES (?, ?, 'analysis', ?, NULL, ?, ?)
        """,
        expressionId,
        expression.userId(),
        expression.analysisRequestId(),
        expression.originalSituation(),
        now);

    List<ExpressionVariantResponse> variants = new ArrayList<>(expression.variants().size());
    for (NewExpressionVariant variant : expression.variants()) {
      UUID variantId = UUID.randomUUID();
      jdbcTemplate.update(
          """
          INSERT INTO expression_variants
            (id, expression_id, variant_order, tone_label, english_text, ipa,
             korean_pronunciation, pronunciation_tip, cultural_tip, created_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          variantId,
          expressionId,
          variant.variantOrder(),
          variant.toneLabel(),
          variant.englishText(),
          variant.ipa(),
          variant.koreanPronunciation(),
          variant.pronunciationTip(),
          variant.culturalTip(),
          now);
      variants.add(
          new ExpressionVariantResponse(
              variantId,
              variant.variantOrder(),
              variant.toneLabel(),
              variant.englishText(),
              variant.ipa(),
              variant.koreanPronunciation(),
              variant.pronunciationTip(),
              variant.culturalTip(),
              null));
    }

    UUID selectedVariantId =
        variants.stream()
            .filter(variant -> variant.variantOrder() == expression.selectedVariantOrder())
            .findFirst()
            .orElseThrow()
            .id();
    jdbcTemplate.update(
        "UPDATE expressions SET selected_variant_id = ? WHERE id = ?",
        selectedVariantId,
        expressionId);

    UUID reviewCardId = UUID.randomUUID();
    OffsetDateTime nextReviewAt = now;
    jdbcTemplate.update(
        """
        INSERT INTO review_cards
          (id, user_id, expression_id, next_review_at, current_interval_days, created_at)
        VALUES (?, ?, ?, ?, 1, ?)
        """,
        reviewCardId,
        expression.userId(),
        expressionId,
        nextReviewAt,
        now);

    return new ExpressionResponse(
        expressionId,
        "analysis",
        expression.analysisRequestId(),
        null,
        expression.originalSituation(),
        selectedVariantId,
        variants,
        reviewCardId,
        nextReviewAt,
        now);
  }

  private ExpressionResponse toResponse(ExpressionRow row) {
    return new ExpressionResponse(
        row.id(),
        row.sourceType(),
        row.analysisRequestId(),
        row.practiceSessionId(),
        row.originalSituation(),
        row.selectedVariantId(),
        findVariants(row.id()),
        row.reviewCardId(),
        row.nextReviewAt(),
        row.createdAt());
  }

  private List<ExpressionVariantResponse> findVariants(UUID expressionId) {
    return jdbcTemplate.query(
        """
        SELECT id, variant_order, tone_label, english_text, ipa, korean_pronunciation,
               pronunciation_tip, cultural_tip
          FROM expression_variants
         WHERE expression_id = ?
         ORDER BY variant_order
        """,
        (rs, rowNum) ->
            new ExpressionVariantResponse(
                rs.getObject("id", UUID.class),
                rs.getInt("variant_order"),
                rs.getString("tone_label"),
                rs.getString("english_text"),
                rs.getString("ipa"),
                rs.getString("korean_pronunciation"),
                rs.getString("pronunciation_tip"),
                rs.getString("cultural_tip"),
                null),
        expressionId);
  }

  private static RowMapper<ExpressionRow> expressionRowMapper() {
    return (ResultSet rs, int rowNum) ->
        new ExpressionRow(
            rs.getObject("id", UUID.class),
            rs.getString("source_type"),
            rs.getObject("analysis_request_id", UUID.class),
            rs.getObject("practice_session_id", UUID.class),
            rs.getString("original_situation"),
            rs.getObject("selected_variant_id", UUID.class),
            rs.getObject("review_card_id", UUID.class),
            rs.getObject("next_review_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class));
  }

  private record ExpressionRow(
      UUID id,
      String sourceType,
      UUID analysisRequestId,
      UUID practiceSessionId,
      String originalSituation,
      UUID selectedVariantId,
      UUID reviewCardId,
      OffsetDateTime nextReviewAt,
      OffsetDateTime createdAt) {}
}
