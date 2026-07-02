package com.phraselog.expression.repository;

import com.phraselog.expression.dto.ExpressionListItem;
import com.phraselog.expression.dto.ExpressionResponse;
import com.phraselog.expression.dto.ExpressionVariantResponse;
import com.phraselog.expression.dto.NewExpression;
import com.phraselog.expression.dto.NewExpressionVariant;
import com.phraselog.expression.dto.NewRoleplayExpression;
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
  public Optional<ExpressionResponse> findByRoleplayIdempotencyKey(
      UUID practiceSessionId, UUID userId, UUID idempotencyKey) {
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
               WHERE e.practice_session_id = ?
                 AND e.user_id = ?
                 AND e.source_type = 'roleplay_result'
                 AND e.roleplay_save_idempotency_key = ?
               ORDER BY e.created_at DESC
               LIMIT 1
              """,
              expressionRowMapper(),
              practiceSessionId,
              userId,
              idempotencyKey);
      return Optional.of(toResponse(row));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<ExpressionResponse> findActiveRoleplaySaveByIndex(
      UUID practiceSessionId, UUID userId, int roleplayResultIndex) {
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
                 AND rc.removed_from_queue_at IS NULL
               WHERE e.practice_session_id = ?
                 AND e.user_id = ?
                 AND e.source_type = 'roleplay_result'
                 AND e.roleplay_result_index = ?
                 AND e.deleted_at IS NULL
               ORDER BY e.created_at DESC
               LIMIT 1
              """,
              expressionRowMapper(),
              practiceSessionId,
              userId,
              roleplayResultIndex);
      return Optional.of(toResponse(row));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public List<ExpressionListItem> list(
      UUID userId, String q, OffsetDateTime cursorCreatedAt, UUID cursorId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT e.id, e.original_situation, e.created_at,
                   v.english_text, v.tone_label
              FROM expressions e
              LEFT JOIN expression_variants v ON v.id = e.selected_variant_id
             WHERE e.user_id = ? AND e.deleted_at IS NULL
            """);
    List<Object> args = new ArrayList<>();
    args.add(userId);

    if (cursorCreatedAt != null && cursorId != null) {
      sql.append(" AND (e.created_at, e.id) < (?, ?)\n");
      args.add(cursorCreatedAt);
      args.add(cursorId);
    }

    if (q != null) {
      sql.append(
          """
           AND ( to_tsvector('simple', e.original_situation) @@ plainto_tsquery('simple', ?)
                 OR EXISTS (SELECT 1 FROM expression_variants sv
                             WHERE sv.expression_id = e.id
                               AND to_tsvector('simple', sv.english_text)
                                   @@ plainto_tsquery('simple', ?)) )
          """);
      args.add(q);
      args.add(q);
    }

    sql.append(" ORDER BY e.created_at DESC, e.id DESC LIMIT ?");
    args.add(limit);

    return jdbcTemplate.query(
        sql.toString(),
        (rs, rowNum) ->
            new ExpressionListItem(
                rs.getObject("id", UUID.class),
                rs.getString("original_situation"),
                rs.getString("english_text"),
                rs.getString("tone_label"),
                rs.getObject("created_at", OffsetDateTime.class)),
        args.toArray());
  }

  @Override
  public int countActive(UUID userId) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*)
              FROM expressions
             WHERE user_id = ? AND deleted_at IS NULL
            """,
            Integer.class,
            userId);
    return count == null ? 0 : count;
  }

  @Override
  public Optional<ExpressionResponse> findByIdForUser(UUID expressionId, UUID userId) {
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
                 AND rc.removed_from_queue_at IS NULL
               WHERE e.id = ? AND e.user_id = ? AND e.deleted_at IS NULL
              """,
              expressionRowMapper(),
              expressionId,
              userId);
      return Optional.of(toResponse(row));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public boolean softDelete(UUID expressionId, UUID userId) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE expressions SET deleted_at = now()
             WHERE id = ? AND user_id = ? AND deleted_at IS NULL
            """,
            expressionId,
            userId);
    return updated > 0;
  }

  @Override
  @Transactional
  public ExpressionResponse createFromAnalysis(NewExpression expression) {
    return create(
        expression.userId(),
        "analysis",
        expression.analysisRequestId(),
        null,
        expression.originalSituation(),
        expression.selectedVariantOrder(),
        null,
        null,
        expression.variants());
  }

  @Override
  @Transactional
  public ExpressionResponse createFromRoleplayResult(NewRoleplayExpression expression) {
    return create(
        expression.userId(),
        "roleplay_result",
        null,
        expression.practiceSessionId(),
        expression.originalSituation(),
        expression.selectedVariantOrder(),
        expression.roleplayResultIndex(),
        expression.idempotencyKey(),
        expression.variants());
  }

  private ExpressionResponse create(
      UUID userId,
      String sourceType,
      UUID analysisRequestId,
      UUID practiceSessionId,
      String originalSituation,
      int selectedVariantOrder,
      Integer roleplayResultIndex,
      UUID roleplaySaveIdempotencyKey,
      List<NewExpressionVariant> newVariants) {
    UUID expressionId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();

    jdbcTemplate.update(
        """
        INSERT INTO expressions
          (id, user_id, source_type, analysis_request_id, practice_session_id,
           original_situation, roleplay_result_index, roleplay_save_idempotency_key, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        expressionId,
        userId,
        sourceType,
        analysisRequestId,
        practiceSessionId,
        originalSituation,
        roleplayResultIndex,
        roleplaySaveIdempotencyKey,
        now);

    List<ExpressionVariantResponse> variants = new ArrayList<>(newVariants.size());
    for (NewExpressionVariant variant : newVariants) {
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
            .filter(variant -> variant.variantOrder() == selectedVariantOrder)
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
        userId,
        expressionId,
        nextReviewAt,
        now);

    return new ExpressionResponse(
        expressionId,
        sourceType,
        analysisRequestId,
        practiceSessionId,
        originalSituation,
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
