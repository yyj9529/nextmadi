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
    requireVerifiedEmail(request.providerEmailVerified());

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

  /**
   * Refuses an address the provider explicitly marked unverified.
   *
   * <p>{@code users.email} is the key the S03 magic link uses to find an existing account, so that
   * column must only ever hold addresses somebody proved they control. Without this check, an
   * account registered while claiming a stranger's address would swallow that stranger the first
   * time they signed in with a genuine magic link.
   *
   * <p>Only an explicit {@code false} is refused. A null means the provider said nothing, which is
   * the normal case for providers that omit the claim; treating it as unverified would block them
   * all. The BFF applies the same rule before calling, so neither side alone is load-bearing.
   */
  private static void requireVerifiedEmail(Boolean providerEmailVerified) {
    if (Boolean.FALSE.equals(providerEmailVerified)) {
      throw new ApiErrorException(
          HttpStatus.CONFLICT,
          "email_unverified",
          "이메일 주소가 확인되지 않았어요. 해당 서비스에서 이메일을 인증한 뒤 다시 시도해주세요.",
          "The OAuth provider reported provider_email as unverified.",
          false);
    }
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
