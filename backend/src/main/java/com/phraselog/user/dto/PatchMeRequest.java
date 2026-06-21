package com.phraselog.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * Partial-update body for {@code PATCH /me} (#52).
 *
 * <p>Partial semantics: a {@code null} field means "leave unchanged". v1 does not support clearing
 * {@code display_name} back to NULL through this route (S11 leaves empty-name handling TBD); an
 * empty string is stored as-is. {@code isOnboarded} is a three-state flag — {@code null} = absent
 * (unchanged), {@code true} = complete onboarding, {@code false} = rejected (cannot un-onboard via
 * API). The distinction is why this is a {@link Boolean}, not a primitive.
 */
public record PatchMeRequest(
    @JsonProperty("display_name") String displayName,
    @JsonProperty("selected_coach_id") UUID selectedCoachId,
    @JsonProperty("is_onboarded") Boolean isOnboarded) {}
