package com.phraselog.auth.identity;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OAuthProvisioningRequest(
    String provider,
    @JsonProperty("provider_user_id") String providerUserId,
    @JsonProperty("provider_email") String providerEmail,
    @JsonProperty("display_name") String displayName,
    /**
     * Whether the OAuth provider says it verified this address.
     *
     * <p>Boxed, and absence is treated as "the provider did not say" rather than "unverified".
     * Rejecting silence would block every provider that omits the claim, and would break sign-in
     * during any deploy where the BFF is older than this service.
     */
    @JsonProperty("provider_email_verified") Boolean providerEmailVerified) {

  OAuthIdentityRequest toIdentityRequest() {
    return new OAuthIdentityRequest(
        provider, providerUserId, providerEmail, displayName, providerEmailVerified);
  }
}
