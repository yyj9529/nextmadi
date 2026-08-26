package com.phraselog.auth.email;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;

/**
 * Guards the email sign-in endpoints, which run before any user exists and so cannot be authorized
 * by user id. A dedicated session token rather than the OAuth one keeps the two provisioning
 * capabilities separate: a leaked email-provisioning token cannot mint OAuth identities.
 */
final class EmailProvisioning {

  static final String SESSION_TOKEN = "__email_provisioning__";

  private EmailProvisioning() {}

  static void requirePrincipal(HttpServletRequest request) {
    Object attribute = request.getAttribute(InternalAuthPrincipal.REQUEST_ATTRIBUTE);
    if (attribute instanceof InternalAuthPrincipal principal
        && !principal.isAuthenticatedUser()
        && SESSION_TOKEN.equals(principal.sessionToken())) {
      return;
    }
    throw new ApiErrorException(
        HttpStatus.UNAUTHORIZED,
        "internal_auth_invalid",
        "로그인이 필요해요.",
        "Email sign-in requires the dedicated internal email-provisioning token.",
        false);
  }
}
