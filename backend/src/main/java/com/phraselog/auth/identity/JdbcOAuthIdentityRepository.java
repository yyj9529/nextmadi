package com.phraselog.auth.identity;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcOAuthIdentityRepository implements OAuthIdentityRepository {

  private static final String USER_COLUMNS =
      "u.id, u.email, u.display_name, u.is_onboarded, u.scheduled_deletion_at";

  private final JdbcTemplate jdbcTemplate;

  public JdbcOAuthIdentityRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<OAuthUserRow> findByProviderIdentity(String provider, String providerUserId) {
    return queryOne(
        "SELECT "
            + USER_COLUMNS
            + " FROM user_auth_identities i"
            + " JOIN users u ON u.id = i.user_id"
            + " WHERE i.provider = ?"
            + " AND i.provider_user_id = ?"
            + " AND u.deleted_at IS NULL",
        provider,
        providerUserId);
  }

  @Override
  public Optional<OAuthUserRow> findActiveUserByEmail(String email) {
    return queryOne(
        "SELECT " + USER_COLUMNS + " FROM users u WHERE u.email = ? AND u.deleted_at IS NULL",
        email);
  }

  @Override
  public Optional<OAuthUserRow> findActiveUserById(UUID id) {
    return queryOne(
        "SELECT " + USER_COLUMNS + " FROM users u WHERE u.id = ? AND u.deleted_at IS NULL", id);
  }

  @Override
  public OAuthUserRow createUserWithIdentity(
      String provider, String providerUserId, String providerEmail, String displayName) {
    UUID userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)",
        userId,
        providerEmail,
        displayName);
    jdbcTemplate.update(
        "INSERT INTO user_auth_identities"
            + " (user_id, provider, provider_user_id, provider_email)"
            + " VALUES (?, ?, ?, ?)",
        userId,
        provider,
        providerUserId,
        providerEmail);
    return findByProviderIdentity(provider, providerUserId)
        .orElseThrow(() -> new IllegalStateException("Created OAuth identity could not be read"));
  }

  @Override
  public void linkIdentityToUser(
      UUID userId, String provider, String providerUserId, String providerEmail) {
    jdbcTemplate.update(
        "INSERT INTO user_auth_identities"
            + " (user_id, provider, provider_user_id, provider_email)"
            + " VALUES (?, ?, ?, ?)",
        userId,
        provider,
        providerUserId,
        providerEmail);
  }

  @Override
  public void clearScheduledDeletion(UUID userId) {
    jdbcTemplate.update("UPDATE users SET scheduled_deletion_at = NULL WHERE id = ?", userId);
  }

  private Optional<OAuthUserRow> queryOne(String sql, Object... args) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject(sql, rowMapper(), args));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private RowMapper<OAuthUserRow> rowMapper() {
    return (ResultSet rs, int rowNum) ->
        new OAuthUserRow(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("display_name"),
            rs.getBoolean("is_onboarded"),
            rs.getObject("scheduled_deletion_at", OffsetDateTime.class));
  }
}
