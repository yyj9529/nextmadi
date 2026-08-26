package com.phraselog.auth.email;

import com.phraselog.common.web.ApiErrorException;
import com.phraselog.common.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Email identity endpoints for the S03 magic link.
 *
 * <p>Lookup and resolve are separate because they carry different authority. Lookup runs while the
 * link is merely being sent and must not create anything; resolve runs only after the link came
 * back and may create or link a user. Folding them into one call would let anyone mint accounts by
 * typing addresses into the login form.
 *
 * <p>Both take the address in the body rather than a query parameter: an email in a URL ends up in
 * access logs.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/auth/email/identity")
public class EmailIdentityController {

  private final EmailIdentityService service;

  public EmailIdentityController(EmailIdentityService service) {
    this.service = service;
  }

  @PostMapping("/lookup")
  public EmailIdentityResult lookup(
      HttpServletRequest servletRequest, @RequestBody EmailIdentityRequest body) {
    EmailProvisioning.requirePrincipal(servletRequest);
    return service
        .findByEmail(body)
        .orElseThrow(
            () ->
                new ApiErrorException(
                    HttpStatus.NOT_FOUND,
                    "email_identity_not_found",
                    "가입되지 않은 이메일이에요.",
                    "No active user for this email address.",
                    false));
  }

  /** Backs the adapter's {@code updateUser}, which carries a user id and no address. */
  @PostMapping("/link")
  public EmailIdentityResult link(
      HttpServletRequest servletRequest, @RequestBody EmailIdentityLinkRequest body) {
    EmailProvisioning.requirePrincipal(servletRequest);
    return service.linkByUserId(userId(body));
  }

  @PostMapping
  public EmailIdentityResult resolve(
      HttpServletRequest servletRequest, @RequestBody EmailIdentityRequest body) {
    EmailProvisioning.requirePrincipal(servletRequest);
    return service.resolve(body);
  }

  private static UUID userId(EmailIdentityLinkRequest body) {
    try {
      return UUID.fromString(body == null ? null : body.userId());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new ApiErrorException(
          HttpStatus.BAD_REQUEST,
          "validation_failed",
          "입력값을 다시 확인해 주세요.",
          "user_id must be a UUID.",
          false);
    }
  }
}
