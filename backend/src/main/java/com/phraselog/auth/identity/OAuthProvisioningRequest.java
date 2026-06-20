package com.phraselog.auth.identity;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OAuthProvisioningRequest(
    String provider,
    @JsonProperty("provider_user_id") String providerUserId,
    @JsonProperty("provider_email") String providerEmail,
    @JsonProperty("display_name") String displayName) {

  OAuthIdentityRequest toIdentityRequest() {
    return new OAuthIdentityRequest(provider, providerUserId, providerEmail, displayName);
  }
}
