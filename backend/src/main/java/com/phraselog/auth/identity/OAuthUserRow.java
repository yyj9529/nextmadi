package com.phraselog.auth.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OAuthUserRow(
    UUID id,
    String email,
    String displayName,
    boolean isOnboarded,
    OffsetDateTime scheduledDeletionAt) {}
