package com.phraselog.auth.email;

import com.phraselog.common.web.ApiErrorException;
import java.time.OffsetDateTime;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class VerificationTokenService {

  /**
   * How many unexpired links one address may hold. Without a cap, the S03 email form lets anyone
   * spend our SES quota mailing a stranger. Deliberately crude — this is not a rate limiter.
   *
   * <p>Checked in two places, and the order is what makes it work. {@link #checkSendQuota} is the
   * one that prevents the mail: the BFF calls it <em>before</em> handing anything to SES. {@link
   * #create} keeps the same cap as a backstop.
   *
   * <p>Store-time alone was the original mistake. Auth.js starts the send and the store
   * concurrently (@auth/core/lib/actions/signin/send-token.js), so refusing at store time fires
   * after the mail is in flight — the quota is spent anyway and the recipient gets a link with no
   * row behind it, which reads to them as "this link expired".
   *
   * <p>Store-time is still needed, for the same concurrency. When the send is refused, the store
   * call is already running and is not cancelled; without a cap there it would write another row on
   * every refused attempt, inflating the count and locking the address out until expiry. Refusing
   * there is harmless now precisely because no mail went out.
   */
  static final int MAX_OUTSTANDING_TOKENS = 5;

  private final VerificationTokenRepository repository;

  public VerificationTokenService(VerificationTokenRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public VerificationTokenResult create(CreateVerificationTokenRequest request) {
    String identifier = identifier(request == null ? null : request.identifier());
    String token = token(request.token());
    OffsetDateTime expires = expires(request.expires());

    repository.purgeExpired();
    // 발송이 거절돼도 Auth.js는 이 호출을 취소하지 않는다. 여기서 막지 않으면 거절될 때마다
    // 행이 하나씩 늘어 카운트가 부풀고, 그 주소는 만료까지 영구히 잠긴다.
    if (repository.countUnexpired(identifier) >= MAX_OUTSTANDING_TOKENS) {
      throw rateLimitExceeded();
    }

    repository.create(identifier, token, expires);
    return new VerificationTokenResult(identifier, token, expires);
  }

  /**
   * Answers whether we may mail another link to this address, and refuses when the cap is reached.
   *
   * <p>Called from the send path so a refusal happens before anything reaches SES. Read-only apart
   * from the expired-row purge: this decides, it does not reserve. The window between this check
   * and the subsequent store is deliberate — closing it would need a reservation protocol for a
   * guard that is explicitly crude.
   */
  @Transactional
  public SendQuotaResult checkSendQuota(SendQuotaRequest request) {
    String identifier = identifier(request == null ? null : request.identifier());

    repository.purgeExpired();
    int outstanding = repository.countUnexpired(identifier);
    if (outstanding >= MAX_OUTSTANDING_TOKENS) {
      throw rateLimitExceeded();
    }

    return new SendQuotaResult(identifier, MAX_OUTSTANDING_TOKENS - outstanding);
  }

  /**
   * Consumes a token. Expiry is deliberately not judged here: Auth.js owns the Verification error
   * and compares {@code expires} itself, so an expired row is returned once and then gone. Judging
   * it in both places invites the two sides to disagree.
   */
  @Transactional
  public VerificationTokenResult consume(ConsumeVerificationTokenRequest request) {
    String identifier = identifier(request == null ? null : request.identifier());
    String token = token(request.token());

    VerificationTokenRow row =
        repository
            .consume(identifier, token)
            .orElseThrow(
                () ->
                    new ApiErrorException(
                        HttpStatus.NOT_FOUND,
                        "verification_token_not_found",
                        "링크가 만료됐어요. 다시 보내드릴게요.",
                        "No verification token for this identifier and token hash.",
                        false));

    repository.purgeExpired();
    return new VerificationTokenResult(row.identifier(), row.token(), row.expires());
  }

  private static ApiErrorException rateLimitExceeded() {
    return new ApiErrorException(
        HttpStatus.TOO_MANY_REQUESTS,
        "rate_limit_exceeded",
        "메일을 너무 많이 요청했어요. 받은 링크를 확인하거나 잠시 후 다시 시도해주세요.",
        "Outstanding verification tokens for this identifier reached MAX_OUTSTANDING_TOKENS.",
        true);
  }

  private static String identifier(String value) {
    return required(value, "identifier is required.").toLowerCase(Locale.ROOT);
  }

  private static String token(String value) {
    return required(value, "token is required.");
  }

  private static OffsetDateTime expires(OffsetDateTime value) {
    if (value == null) {
      throw validationFailed("expires is required.");
    }
    return value;
  }

  private static String required(String value, String developerHint) {
    if (!StringUtils.hasText(value)) {
      throw validationFailed(developerHint);
    }
    return value.trim();
  }

  private static ApiErrorException validationFailed(String developerHint) {
    return new ApiErrorException(
        HttpStatus.BAD_REQUEST, "validation_failed", "입력값을 다시 확인해 주세요.", developerHint, false);
  }
}
