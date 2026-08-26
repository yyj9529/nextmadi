package com.phraselog.auth.email;

import com.phraselog.auth.identity.OAuthIdentityRepository;
import com.phraselog.auth.identity.OAuthUserRow;
import com.phraselog.common.web.ApiErrorException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Resolves a verified email address to a PhraseLog user.
 *
 * <p>Where the OAuth path refuses to touch an existing account with the same address, this one
 * attaches to it. The difference is evidence, not inconsistency: a provider-supplied email is a
 * claim, while a clicked magic link proves the person can open that mailbox. Refusing here would
 * also strand the very users S03 sends this way — the OAuth conflict message tells them to sign in
 * by email link.
 */
@Service
public class EmailIdentityService {

  /** Matches the {@code provider} vocabulary in data-model.md: 'google' | 'kakao' | 'email'. */
  static final String PROVIDER_EMAIL = "email";

  private final OAuthIdentityRepository repository;

  public EmailIdentityService(OAuthIdentityRepository repository) {
    this.repository = repository;
  }

  /**
   * Read-only lookup for the adapter's {@code getUserByEmail}. It must not create anything: Auth.js
   * calls it while merely sending the link, so a creating lookup would mint an account for every
   * address typed into the S03 form, verified or not.
   */
  @Transactional(readOnly = true)
  public Optional<EmailIdentityResult> findByEmail(EmailIdentityRequest request) {
    String email = email(request == null ? null : request.email());
    return repository
        .findByProviderIdentity(PROVIDER_EMAIL, email)
        .or(() -> repository.findActiveUserByEmail(email))
        .map(user -> result(user, false, false));
  }

  /** Called only after the magic link has been verified. */
  @Transactional
  public EmailIdentityResult resolve(EmailIdentityRequest request) {
    String email = email(request == null ? null : request.email());

    Optional<OAuthUserRow> byIdentity = repository.findByProviderIdentity(PROVIDER_EMAIL, email);
    if (byIdentity.isPresent()) {
      return settled(byIdentity.get(), false, false);
    }

    Optional<OAuthUserRow> byEmail = repository.findActiveUserByEmail(email);
    if (byEmail.isPresent()) {
      OAuthUserRow user = byEmail.get();
      repository.linkIdentityToUser(user.id(), PROVIDER_EMAIL, email, email);
      return settled(user, false, true);
    }

    // provider_user_id is the address itself: it is the stable identifier the mailbox proves.
    return settled(
        repository.createUserWithIdentity(PROVIDER_EMAIL, email, email, null), true, false);
  }

  /**
   * Ensures the {@code email} identity exists for a user Auth.js has already matched by address,
   * and returns that user.
   *
   * <p>Keyed by user id rather than address because that is all Auth.js hands the adapter here: its
   * {@code updateUser} call carries {@code {id, emailVerified}} and no email. Without this, the
   * linking case in {@link #resolve} would never be reached through the adapter and a
   * Google-registered address signing in by link would leave no email identity behind.
   */
  @Transactional
  public EmailIdentityResult linkByUserId(UUID userId) {
    OAuthUserRow user =
        repository
            .findActiveUserById(userId)
            .orElseThrow(
                () ->
                    new ApiErrorException(
                        HttpStatus.NOT_FOUND,
                        "email_identity_not_found",
                        "가입되지 않은 계정이에요.",
                        "No active user for this id.",
                        false));

    String email = user.email().toLowerCase(Locale.ROOT);
    boolean alreadyLinked = repository.findByProviderIdentity(PROVIDER_EMAIL, email).isPresent();
    if (!alreadyLinked) {
      repository.linkIdentityToUser(user.id(), PROVIDER_EMAIL, email, email);
    }
    return settled(user, false, !alreadyLinked);
  }

  private EmailIdentityResult settled(
      OAuthUserRow user, boolean createdUser, boolean linkedToExistingUser) {
    boolean cancelDeletion = user.scheduledDeletionAt() != null;
    if (cancelDeletion) {
      repository.clearScheduledDeletion(user.id());
    }
    return new EmailIdentityResult(
        user.id(),
        user.email(),
        user.displayName(),
        user.isOnboarded(),
        createdUser,
        cancelDeletion,
        linkedToExistingUser);
  }

  private static EmailIdentityResult result(
      OAuthUserRow user, boolean createdUser, boolean linkedToExistingUser) {
    return new EmailIdentityResult(
        user.id(),
        user.email(),
        user.displayName(),
        user.isOnboarded(),
        createdUser,
        false,
        linkedToExistingUser);
  }

  private static String email(String value) {
    if (!StringUtils.hasText(value)) {
      throw new ApiErrorException(
          HttpStatus.BAD_REQUEST,
          "validation_failed",
          "입력값을 다시 확인해 주세요.",
          "email is required.",
          false);
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }
}
