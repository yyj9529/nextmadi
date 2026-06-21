package com.phraselog.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Response body for {@code GET /me} and {@code PATCH /me} (#52), matching the OpenAPI User. */
public record UserResponse(
    UUID id,
    String email,
    @JsonProperty("display_name") String displayName,
    @JsonProperty("selected_coach_id") UUID selectedCoachId,
    @JsonProperty("is_onboarded") boolean isOnboarded,
    @JsonProperty("created_at") OffsetDateTime createdAt,
    @JsonProperty("scheduled_deletion_at") OffsetDateTime scheduledDeletionAt) {}
