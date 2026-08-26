package com.phraselog.auth.email;

import java.time.OffsetDateTime;

public record CreateVerificationTokenRequest(
    String identifier, String token, OffsetDateTime expires) {}
