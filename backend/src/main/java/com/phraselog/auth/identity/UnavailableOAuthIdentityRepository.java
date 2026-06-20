package com.phraselog.auth.identity;

import java.util.Optional;
import java.util.UUID;

final class UnavailableOAuthIdentityRepository implements OAuthIdentityRepository {

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "OAuthIdentityRepository requires a DataSource; none is configured");
  }

  @Override
  public Optional<OAuthUserRow> findByProviderIdentity(String provider, String providerUserId) {
    throw unavailable();
  }

  @Override
  public Optional<OAuthUserRow> findActiveUserByEmail(String email) {
    throw unavailable();
  }

  @Override
  public OAuthUserRow createUserWithIdentity(
      String provider, String providerUserId, String providerEmail, String displayName) {
    throw unavailable();
  }

  @Override
  public void clearScheduledDeletion(UUID userId) {
    throw unavailable();
  }
}
