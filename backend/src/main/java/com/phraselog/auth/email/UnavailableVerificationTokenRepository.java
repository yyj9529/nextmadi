package com.phraselog.auth.email;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Fails loudly when no DataSource is configured. Returning empty results instead would let the
 * magic-link flow report success while storing nothing.
 */
final class UnavailableVerificationTokenRepository implements VerificationTokenRepository {

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "VerificationTokenRepository requires a DataSource; none is configured");
  }

  @Override
  public void create(String identifier, String token, OffsetDateTime expires) {
    throw unavailable();
  }

  @Override
  public Optional<VerificationTokenRow> consume(String identifier, String token) {
    throw unavailable();
  }

  @Override
  public int countUnexpired(String identifier) {
    throw unavailable();
  }

  @Override
  public int purgeExpired() {
    throw unavailable();
  }
}
