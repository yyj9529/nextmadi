package com.phraselog.auth.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * Backs both provider families that write {@code users} and {@code user_auth_identities}: the OAuth
 * flow (#18) and the S03 email magic link (#19). The name predates the email path.
 */
public interface OAuthIdentityRepository {

  /** Attaches an identity to a user that already exists, without creating a second user. */
  void linkIdentityToUser(
      java.util.UUID userId, String provider, String providerUserId, String providerEmail);

  Optional<OAuthUserRow> findByProviderIdentity(String provider, String providerUserId);

  Optional<OAuthUserRow> findActiveUserByEmail(String email);

  OAuthUserRow createUserWithIdentity(
      String provider, String providerUserId, String providerEmail, String displayName);

  void clearScheduledDeletion(UUID userId);
}
