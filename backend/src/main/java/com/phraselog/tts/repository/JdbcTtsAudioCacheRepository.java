package com.phraselog.tts.repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/** JdbcTemplate implementation of {@link TtsAudioCacheRepository}. */
public class JdbcTtsAudioCacheRepository implements TtsAudioCacheRepository {

  private static final String SELECT_BY_KEY =
      """
      SELECT id, text_hash, voice_id, model_name, audio_s3_key, duration_ms
      FROM tts_audio_cache
      WHERE text_hash = ? AND voice_id = ? AND model_name = ?
      """;

  private static final String INSERT_SQL =
      """
      INSERT INTO tts_audio_cache
        (text_hash, text_content, voice_id, model_name, audio_s3_key, duration_ms, expires_at)
      VALUES (?, ?, ?, ?, ?, ?, ?)
      RETURNING id
      """;

  private static final String IS_LINKABLE_VARIANT_SQL =
      """
      SELECT EXISTS (
        SELECT 1
        FROM expression_variants ev
        JOIN expressions e ON ev.expression_id = e.id
        WHERE ev.id = ?
          AND e.user_id = ?
          AND e.deleted_at IS NULL
          AND ev.english_text = ?
      )
      """;

  private static final String LINK_VARIANT_SQL =
      """
      UPDATE expression_variants ev
      SET tts_audio_cache_id = ?
      FROM expressions e
      WHERE ev.expression_id = e.id
        AND ev.id = ?
        AND e.user_id = ?
        AND e.deleted_at IS NULL
        AND ev.english_text = ?
      """;

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;

  public JdbcTtsAudioCacheRepository(
      JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  public Optional<TtsAudioCacheRow> findByKey(String textHash, String voiceId, String modelName) {
    try {
      return Optional.ofNullable(
          jdbcTemplate.queryForObject(SELECT_BY_KEY, rowMapper(), textHash, voiceId, modelName));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Override
  public TtsAudioCacheRow insert(InsertTtsCacheCommand command) {
    return transactionTemplate.execute(
        tx -> {
          UUID id = insertRow(command);
          return new TtsAudioCacheRow(
              id,
              command.textHash(),
              command.voiceId(),
              command.modelName(),
              command.audioS3Key(),
              command.durationMs());
        });
  }

  @Override
  public boolean isLinkableVariant(UUID expressionVariantId, UUID userId, String textContent) {
    if (expressionVariantId == null || userId == null || textContent == null) {
      return false;
    }
    Boolean linkable =
        jdbcTemplate.queryForObject(
            IS_LINKABLE_VARIANT_SQL, Boolean.class, expressionVariantId, userId, textContent);
    return Boolean.TRUE.equals(linkable);
  }

  @Override
  public boolean linkVariant(
      UUID cacheId, UUID expressionVariantId, UUID userId, String textContent) {
    if (cacheId == null || expressionVariantId == null || userId == null || textContent == null) {
      return false;
    }
    int updated =
        jdbcTemplate.update(
            connection -> {
              PreparedStatement ps = connection.prepareStatement(LINK_VARIANT_SQL);
              ps.setObject(1, cacheId, Types.OTHER);
              ps.setObject(2, expressionVariantId, Types.OTHER);
              ps.setObject(3, userId, Types.OTHER);
              ps.setString(4, textContent);
              return ps;
            });
    return updated == 1;
  }

  private UUID insertRow(InsertTtsCacheCommand command) {
    // Bind nullable values with explicit JDBC types to avoid PostgreSQL null inference failures.
    return jdbcTemplate.query(
        connection -> {
          PreparedStatement ps = connection.prepareStatement(INSERT_SQL);
          ps.setString(1, command.textHash());
          ps.setString(2, command.textContent());
          ps.setString(3, command.voiceId());
          ps.setString(4, command.modelName());
          ps.setString(5, command.audioS3Key());
          if (command.durationMs() != null) {
            ps.setInt(6, command.durationMs());
          } else {
            ps.setNull(6, Types.INTEGER);
          }
          if (command.expiresAt() != null) {
            ps.setObject(7, command.expiresAt());
          } else {
            ps.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE);
          }
          return ps;
        },
        rs -> {
          rs.next();
          return (UUID) rs.getObject("id");
        });
  }

  private static RowMapper<TtsAudioCacheRow> rowMapper() {
    return (rs, n) ->
        new TtsAudioCacheRow(
            (UUID) rs.getObject("id"),
            rs.getString("text_hash"),
            rs.getString("voice_id"),
            rs.getString("model_name"),
            rs.getString("audio_s3_key"),
            (Integer) rs.getObject("duration_ms"));
  }
}
