package com.phraselog.auth.identity;

import com.phraselog.common.web.ApiErrorException;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class OAuthIdentityService {

  private final OAuthIdentityRepository repository;

  public OAuthIdentityService(OAuthIdentityRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public OAuthIdentityResult resolve(OAuthIdentityRequest request) {
    String provider = validatedProvider(request == null ? null : request.provider());
    String providerUserId = required(request.providerUserId(), "provider_user_id is required.");
    String providerEmail =
        required(request.providerEmail(), "provider_email is required.").toLowerCase(Locale.ROOT);
    String displayName = optional(request.displayName());

    return repository
        .findByProviderIdentity(provider, providerUserId)
        .map(existing -> existingResult(existing, false))
        .orElseGet(
            () -> {
              if (repository.findActiveUserByEmail(providerEmail).isPresent()) {
                throw accountLinkRequired();
              }
              OAuthUserRow created =
                  repository.createUserWithIdentity(
                      provider, providerUserId, providerEmail, displayName);
              return toResult(created, true, false);
            });
  }

  private OAuthIdentityResult existingResult(OAuthUserRow user, boolean createdUser) {
    boolean cancelDeletion = user.scheduledDeletionAt() != null;
    if (cancelDeletion) {
      repository.clearScheduledDeletion(user.id());
    }
    return toResult(user, createdUser, cancelDeletion);
  }

  private static OAuthIdentityResult toResult(
      OAuthUserRow user, boolean createdUser, boolean canceledScheduledDeletion) {
    return new OAuthIdentityResult(
        user.id(),
        user.email(),
        user.displayName(),
        user.isOnboarded(),
        createdUser,
        canceledScheduledDeletion);
  }

  private static String validatedProvider(String provider) {
    String normalized = required(provider, "provider is required.").toLowerCase(Locale.ROOT);
    if (!"google".equals(normalized) && !"kakao".equals(normalized)) {
      throw validationFailed("provider must be google or kakao.");
    }
    return normalized;
  }

  private static String required(String value, String developerHint) {
    if (!StringUtils.hasText(value)) {
      throw validationFailed(developerHint);
    }
    return value.trim();
  }

  private static String optional(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static ApiErrorException validationFailed(String developerHint) {
    return new ApiErrorException(
        HttpStatus.BAD_REQUEST, "validation_failed", "입력값을 다시 확인해 주세요.", developerHint, false);
  }

  private static ApiErrorException accountLinkRequired() {
    return new ApiErrorException(
        HttpStatus.CONFLICT,
        "account_link_required",
        "이미 가입된 계정이에요. 기존 로그인 방법이나 이메일 링크로 로그인해주세요.",
        "Same email exists with a different OAuth identity; do not auto-link while signed out.",
        false);
  }
}
