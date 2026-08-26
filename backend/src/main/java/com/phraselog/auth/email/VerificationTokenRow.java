package com.phraselog.auth.email;

import java.time.OffsetDateTime;

/**
 * One outstanding magic-link token. {@code token} is Auth.js's sha256(rawToken + AUTH_SECRET), not
 * the value in the emailed link, and must never be logged.
 */
public record VerificationTokenRow(String identifier, String token, OffsetDateTime expires) {}
