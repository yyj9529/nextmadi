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
    if (repository.countUnexpired(identifier) >= MAX_OUTSTANDING_TOKENS) {
      throw new ApiErrorException(
          HttpStatus.TOO_MANY_REQUESTS,
          "rate_limit_exceeded",
          "메일을 너무 많이 요청했어요. 받은 링크를 확인하거나 잠시 후 다시 시도해주세요.",
          "Outstanding verification tokens for this identifier reached MAX_OUTSTANDING_TOKENS.",
          true);
    }

    repository.create(identifier, token, expires);
    return new VerificationTokenResult(identifier, token, expires);
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
