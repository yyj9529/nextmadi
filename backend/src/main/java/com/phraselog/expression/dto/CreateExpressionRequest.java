package com.phraselog.expression.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * Request body for {@code POST /expressions} (#41, #42).
 *
 * <p>{@code session_token} is the pending-save claim token (#42): the original anonymous S02
 * session that produced the analysis. It is only present when an authenticated user is claiming a
 * pre-signup analysis. The internal JWS still carries exactly the authenticated {@code user_id};
 * this body field is the explicit, request-scoped claim contract so the JWS XOR invariant is
 * untouched (see exec-plan 2026-06-21-pending-save-claim.md). Absent for ordinary authenticated
 * saves.
 */
public record CreateExpressionRequest(
    @JsonProperty("analysis_request_id") UUID analysisRequestId,
    @JsonProperty("selected_variant_order") Integer selectedVariantOrder,
    @JsonProperty("session_token") String sessionToken) {}
