package com.phraselog.auth.identity;

import java.util.UUID;

public record OAuthIdentityResult(
    UUID userId,
    String email,
    String displayName,
    boolean isOnboarded,
    boolean createdUser,
    boolean canceledScheduledDeletion) {}
