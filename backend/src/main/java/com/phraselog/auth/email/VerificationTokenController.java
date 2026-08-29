package com.phraselog.auth.email;

import com.phraselog.common.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verification-token store for the S03 email magic link. Next.js reaches it through the Auth.js
 * adapter rather than touching the table itself (ADR-010).
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/auth/email/verification-tokens")
public class VerificationTokenController {

  private final VerificationTokenService service;

  public VerificationTokenController(VerificationTokenService service) {
    this.service = service;
  }

  @PostMapping
  public VerificationTokenResult create(
      HttpServletRequest servletRequest, @RequestBody CreateVerificationTokenRequest body) {
    EmailProvisioning.requirePrincipal(servletRequest);
    return service.create(body);
  }

  /**
   * Asked before the mail is handed to SES, not while storing the token. See {@link
   * VerificationTokenService#MAX_OUTSTANDING_TOKENS} for why the order matters.
   */
  @PostMapping("/quota")
  public SendQuotaResult quota(
      HttpServletRequest servletRequest, @RequestBody SendQuotaRequest body) {
    EmailProvisioning.requirePrincipal(servletRequest);
    return service.checkSendQuota(body);
  }

  @PostMapping("/consume")
  public VerificationTokenResult consume(
      HttpServletRequest servletRequest, @RequestBody ConsumeVerificationTokenRequest body) {
    EmailProvisioning.requirePrincipal(servletRequest);
    return service.consume(body);
  }
}
