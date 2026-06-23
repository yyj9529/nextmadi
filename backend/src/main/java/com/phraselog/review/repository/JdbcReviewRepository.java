package com.phraselog.review.repository;

import com.phraselog.expression.dto.ExpressionVariantResponse;
import com.phraselog.review.dto.ReviewCardResponse;
import com.phraselog.review.dto.ReviewCardState;
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

/** JdbcTemplate-backed persistence for the S10 review queue (#49). */
public class JdbcReviewRepository implements ReviewRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcReviewRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public List<ReviewCardResponse> findDueCards(UUID userId, List<UUID> excludeIds, int limit) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT rc.id, rc.expression_id, rc.next_review_at, rc.last_reviewed_at,
                   rc.last_rating, rc.current_interval_days, e.original_situation,
                   v.id AS variant_id, v.variant_order, v.tone_label, v.english_text, v.ipa,
                   v.korean_pronunciation, v.pronunciation_tip, v.cultural_tip
              FROM review_cards rc
              JOIN expressions e ON e.id = rc.expression_id
              LEFT JOIN expression_variants v ON v.id = e.selected_variant_id
             WHERE rc.user_id = ?
               AND rc.next_review_at <= now()
               AND rc.removed_from_queue_at IS NULL
               AND e.deleted_at IS NULL
            """);
    List<Object> args = new ArrayList<>();
    args.add(userId);

    if (excludeIds != null && !excludeIds.isEmpty()) {
      String placeholders = String.join(", ", excludeIds.stream().map(id -> "?").toList());
      sql.append(" AND rc.id NOT IN (").append(placeholders).append(")\n");
      args.addAll(excludeIds);
    }

    sql.append(" ORDER BY rc.next_review_at ASC, rc.id ASC LIMIT ?");
    args.add(limit);

    return jdbcTemplate.query(sql.toString(), reviewCardRowMapper(), args.toArray());
  }

  @Override
  public int countDue(UUID userId) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*)
              FROM review_cards rc
              JOIN expressions e ON e.id = rc.expression_id
             WHERE rc.user_id = ?
               AND rc.next_review_at <= now()
               AND rc.removed_from_queue_at IS NULL
               AND e.deleted_at IS NULL
            """,
            Integer.class,
            userId);
    return count == null ? 0 : count;
  }

  @Override
  public Optional<ReviewCardState> findCardForUser(UUID reviewCardId, UUID userId) {
    try {
      ReviewCardState state =
          jdbcTemplate.queryForObject(
              """
              SELECT id, expression_id, current_interval_days
                FROM review_cards
               WHERE id = ? AND user_id = ?
              """,
              (ResultSet rs, int rowNum) ->
                  new ReviewCardState(
                      rs.getObject("id", UUID.class),
                      rs.getObject("expression_id", UUID.class),
                      rs.getInt("current_interval_days")),
              reviewCardId,
              userId);
      return Optional.of(state);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  @Transactional
  public void applySubmit(
      UUID reviewCardId,
      UUID userId,
      UUID expressionId,
      String rating,
      int previousIntervalDays,
      int nextIntervalDays,
      OffsetDateTime reviewedAt,
      OffsetDateTime nextReviewAt) {
    jdbcTemplate.update(
        """
        INSERT INTO review_attempts
          (id, user_id, review_card_id, expression_id, rating,
           previous_interval_days, next_interval_days, reviewed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        userId,
        reviewCardId,
        expressionId,
        rating,
        previousIntervalDays,
        nextIntervalDays,
        reviewedAt);

    jdbcTemplate.update(
        """
        UPDATE review_cards
           SET next_review_at = ?, last_reviewed_at = ?, last_rating = ?,
               current_interval_days = ?
         WHERE id = ? AND user_id = ?
        """,
        nextReviewAt,
        reviewedAt,
        rating,
        nextIntervalDays,
        reviewCardId,
        userId);
  }

  @Override
  public boolean removeFromQueue(UUID reviewCardId, UUID userId) {
    int updated =
        jdbcTemplate.update(
            "UPDATE review_cards SET removed_from_queue_at = now() WHERE id = ? AND user_id = ?",
            reviewCardId,
            userId);
    return updated > 0;
  }

  @Override
  public boolean reAddToQueue(UUID reviewCardId, UUID userId) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE review_cards
               SET removed_from_queue_at = NULL, next_review_at = now(), current_interval_days = 1
             WHERE id = ? AND user_id = ? AND removed_from_queue_at IS NOT NULL
            """,
            reviewCardId,
            userId);
    return updated > 0;
  }

  private static RowMapper<ReviewCardResponse> reviewCardRowMapper() {
    return (ResultSet rs, int rowNum) -> {
      UUID variantId = rs.getObject("variant_id", UUID.class);
      ExpressionVariantResponse variant =
          variantId == null
              ? null
              : new ExpressionVariantResponse(
                  variantId,
                  rs.getInt("variant_order"),
                  rs.getString("tone_label"),
                  rs.getString("english_text"),
                  rs.getString("ipa"),
                  rs.getString("korean_pronunciation"),
                  rs.getString("pronunciation_tip"),
                  rs.getString("cultural_tip"),
                  null);
      return new ReviewCardResponse(
          rs.getObject("id", UUID.class),
          rs.getObject("expression_id", UUID.class),
          rs.getObject("next_review_at", OffsetDateTime.class),
          rs.getObject("last_reviewed_at", OffsetDateTime.class),
          rs.getString("last_rating"),
          rs.getInt("current_interval_days"),
          rs.getString("original_situation"),
          variant);
    };
  }
}
