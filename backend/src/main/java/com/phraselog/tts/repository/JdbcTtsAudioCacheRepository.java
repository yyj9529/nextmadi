package com.phraselog.tts.repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TtsAudioCacheRepository}의 JdbcTemplate 구현.
 *
 * <p>바인딩 스타일은 {@code JdbcAiRequestLogStore}/{@code JdbcPracticeTurnRepository}를 따른다 — UUID는 {@code
 * Types.OTHER}, nullable 컬럼은 명시 JDBC 타입. {@code insertAndLink}는 INSERT(RETURNING id)와
 * expression_variants 링크 UPDATE를 한 트랜잭션으로 묶어, 네트워크 I/O(OpenAI/S3)는 트랜잭션 밖에서 끝낸 뒤 DB 쓰기만 원자적으로 처리한다.
 */
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

  private static final String LINK_VARIANT_SQL =
      "UPDATE expression_variants SET tts_audio_cache_id = ? WHERE id = ?";

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
  public TtsAudioCacheRow insertAndLink(InsertTtsCacheCommand command, UUID expressionVariantId) {
    return transactionTemplate.execute(
        tx -> {
          UUID id = insertRow(command);
          if (expressionVariantId != null) {
            jdbcTemplate.update(
                connection -> {
                  PreparedStatement ps = connection.prepareStatement(LINK_VARIANT_SQL);
                  ps.setObject(1, id, Types.OTHER);
                  ps.setObject(2, expressionVariantId, Types.OTHER);
                  return ps;
                });
          }
          return new TtsAudioCacheRow(
              id,
              command.textHash(),
              command.voiceId(),
              command.modelName(),
              command.audioS3Key(),
              command.durationMs());
        });
  }

  private UUID insertRow(InsertTtsCacheCommand command) {
    // nullable 컬럼(duration_ms/expires_at)은 명시 JDBC 타입으로 바인딩해 PostgreSQL의 null 타입 추론
    // 실패를 피한다(JdbcAiRequestLogStore와 동일). RETURNING id로 생성 행 id를 받는다.
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
