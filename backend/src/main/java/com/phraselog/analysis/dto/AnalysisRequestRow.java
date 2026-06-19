package com.phraselog.analysis.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One persisted {@code analysis_requests} row, as read back for the S07 response (#39).
 *
 * <p>Carries exactly the fields the {@code AnalysisRequest} response needs plus the ownership keys
 * used to authorize reads. {@code ip_address} and {@code idempotency_key} are write-time concerns
 * not surfaced in responses, so they are intentionally absent here.
 *
 * <p>Exactly one of {@code userId}/{@code sessionToken} is non-null, mirroring the verified {@link
 * com.phraselog.auth.dto.InternalAuthPrincipal}.
 */
public record AnalysisRequestRow(
    UUID id,
    UUID userId,
    String sessionToken,
    String inputText,
    JsonNode outputJson,
    String promptVersion,
    UUID aiRequestLogId,
    OffsetDateTime createdAt) {}
