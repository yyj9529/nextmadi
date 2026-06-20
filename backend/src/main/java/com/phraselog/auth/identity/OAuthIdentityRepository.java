package com.phraselog.auth.identity;

import java.util.Optional;
import java.util.UUID;

public interface OAuthIdentityRepository {

  Optional<OAuthUserRow> findByProviderIdentity(String provider, String providerUserId);

  Optional<OAuthUserRow> findActiveUserByEmail(String email);

  OAuthUserRow createUserWithIdentity(
      String provider, String providerUserId, String providerEmail, String displayName);

  void clearScheduledDeletion(UUID userId);
}
