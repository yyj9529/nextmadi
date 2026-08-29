package com.phraselog.auth.email;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcVerificationTokenRepository implements VerificationTokenRepository {

  private static final RowMapper<VerificationTokenRow> ROW_MAPPER =
      (rs, rowNum) ->
          new VerificationTokenRow(
              rs.getString("identifier"),
              rs.getString("token"),
              rs.getObject("expires", OffsetDateTime.class));

  private final JdbcTemplate jdbcTemplate;

  public JdbcVerificationTokenRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void create(String identifier, String token, OffsetDateTime expires) {
    jdbcTemplate.update(
        "INSERT INTO verification_tokens (identifier, token, expires) VALUES (?, ?, ?)",
        identifier,
        token,
        expires);
  }

  @Override
  public Optional<VerificationTokenRow> consume(String identifier, String token) {
    return jdbcTemplate
        .query(
            "DELETE FROM verification_tokens"
                + " WHERE identifier = ? AND token = ?"
                + " RETURNING identifier, token, expires",
            ROW_MAPPER,
            identifier,
            token)
        .stream()
        .findFirst();
  }

  // Postgres now() rather than an injected clock: the database is the one place both the expiry
  // written at create time and the comparison at read time agree on.
  @Override
  public int countUnexpired(String identifier) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM verification_tokens WHERE identifier = ? AND expires > now()",
            Integer.class,
            identifier);
    return count == null ? 0 : count;
  }

  @Override
  public int purgeExpired() {
    return jdbcTemplate.update("DELETE FROM verification_tokens WHERE expires <= now()");
  }
}
