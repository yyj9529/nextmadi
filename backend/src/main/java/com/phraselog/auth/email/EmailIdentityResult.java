package com.phraselog.auth.email;

import java.util.UUID;

public record EmailIdentityResult(
    UUID userId,
    String email,
    String displayName,
    boolean isOnboarded,
    boolean createdUser,
    boolean canceledScheduledDeletion,
    boolean linkedToExistingUser) {}
