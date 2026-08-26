package com.phraselog.auth.email;

public record ConsumeVerificationTokenRequest(String identifier, String token) {}
