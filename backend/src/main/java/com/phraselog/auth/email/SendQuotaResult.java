package com.phraselog.auth.email;

/** How many more links this address may be sent before the outstanding-token cap refuses. */
public record SendQuotaResult(String identifier, int remaining) {}
