package com.phraselog.auth.identity;

public record OAuthIdentityRequest(
    String provider,
    String providerUserId,
    String providerEmail,
    String displayName,
    Boolean providerEmailVerified) {}
