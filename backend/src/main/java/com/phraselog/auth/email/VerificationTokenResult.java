package com.phraselog.auth.email;

import java.time.OffsetDateTime;

public record VerificationTokenResult(String identifier, String token, OffsetDateTime expires) {}
