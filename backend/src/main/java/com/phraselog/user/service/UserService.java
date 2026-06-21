package com.phraselog.user.service;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.user.dto.PatchMeRequest;
import com.phraselog.user.dto.UserResponse;
import com.phraselog.user.repository.UserRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Backend core for {@code GET /me} and {@code PATCH /me} (#52). */
@Service
public class UserService {

  private static final int MAX_DISPLAY_NAME_LENGTH = 100;

  private final UserRepository userRepository;

  public UserService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  public UserResponse getMe(InternalAuthPrincipal principal) {
    UUID userId = requireAuthenticatedUser(principal);
    return userRepository.findById(userId).orElseThrow(UserService::userNotFound);
  }

  public UserResponse updateMe(InternalAuthPrincipal principal, PatchMeRequest body) {
    UUID userId = requireAuthenticatedUser(principal);
    PatchMeRequest patch = body == null ? new PatchMeRequest(null, null, null) : body;

    String displayName = validatedDisplayName(patch.displayName());
    UUID selectedCoachId = validatedCoachId(patch.selectedCoachId());
    boolean setOnboardedTrue = validatedOnboarding(patch.isOnboarded());

    return userRepository
        .update(userId, displayName, selectedCoachId, setOnboardedTrue)
        .orElseThrow(UserService::userNotFound);
  }

  /** {@code null} = field absent, leave unchanged. Non-null is validated against the length cap. */
  private static String validatedDisplayName(String displayName) {
    if (displayName == null) {
      return null;
    }
    if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
      throw validationFailed("display_name must be at most 100 characters.");
    }
    return displayName;
  }

  private UUID validatedCoachId(UUID selectedCoachId) {
    if (selectedCoachId == null) {
      return null;
    }
    if (!userRepository.coachExists(selectedCoachId)) {
      throw validationFailed("selected_coach_id does not reference an existing coach.");
    }
    return selectedCoachId;
  }

  /** Three-state: null = absent, true = complete onboarding, false = forbidden via API. */
  private static boolean validatedOnboarding(Boolean isOnboarded) {
    if (Boolean.FALSE.equals(isOnboarded)) {
      throw validationFailed("is_onboarded cannot be set to false via API.");
    }
    return Boolean.TRUE.equals(isOnboarded);
  }

  private static UUID requireAuthenticatedUser(InternalAuthPrincipal principal) {
    if (principal == null || !principal.isAuthenticatedUser()) {
      throw new ApiErrorException(
          HttpStatus.UNAUTHORIZED,
          "internal_auth_invalid",
          "로그인이 필요해요.",
          "/me requires an authenticated user_id principal.",
          false);
    }
    try {
      return UUID.fromString(principal.userId());
    } catch (IllegalArgumentException e) {
      throw validationFailed("user_id claim must be a UUID.");
    }
  }

  private static ApiErrorException validationFailed(String developerHint) {
    return new ApiErrorException(
        HttpStatus.BAD_REQUEST, "validation_failed", "입력값을 다시 확인해 주세요.", developerHint, false);
  }

  private static ApiErrorException userNotFound() {
    return new ApiErrorException(
        HttpStatus.NOT_FOUND,
        "not_found",
        "사용자를 찾을 수 없어요.",
        "Authenticated user_id has no active users row.",
        false);
  }
}
