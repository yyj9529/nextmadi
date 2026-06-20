package com.phraselog.auth.identity;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.common.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.V1 + "/auth/oauth/identity")
public class OAuthIdentityController {

  public static final String PROVISIONING_SESSION_TOKEN = "__oauth_provisioning__";

  private final OAuthIdentityService service;

  public OAuthIdentityController(OAuthIdentityService service) {
    this.service = service;
  }

  @PostMapping
  public OAuthIdentityResult resolve(
      HttpServletRequest servletRequest, @RequestBody OAuthProvisioningRequest body) {
    requireProvisioningPrincipal(servletRequest);
    return service.resolve(body.toIdentityRequest());
  }

  private static void requireProvisioningPrincipal(HttpServletRequest request) {
    Object attribute = request.getAttribute(InternalAuthPrincipal.REQUEST_ATTRIBUTE);
    if (attribute instanceof InternalAuthPrincipal principal
        && !principal.isAuthenticatedUser()
        && PROVISIONING_SESSION_TOKEN.equals(principal.sessionToken())) {
      return;
    }
    throw new ApiErrorException(
        HttpStatus.UNAUTHORIZED,
        "internal_auth_invalid",
        "로그인이 필요해요.",
        "OAuth provisioning requires the dedicated internal provisioning token.",
        false);
  }
}
