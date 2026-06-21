package com.phraselog.user.controller;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.common.web.ApiPaths;
import com.phraselog.user.dto.PatchMeRequest;
import com.phraselog.user.dto.UserResponse;
import com.phraselog.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Current-user profile (#52): {@code GET /me}, {@code PATCH /me}. Behind {@link
 * com.phraselog.auth.web.InternalAuthFilter}; the controller stays thin — principal extraction and
 * delegation only. The caller is identified by the verified token principal, never by request body.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/me")
public class MeController {

  private final UserService userService;

  public MeController(UserService userService) {
    this.userService = userService;
  }

  @GetMapping
  public UserResponse getMe(HttpServletRequest request) {
    return userService.getMe(principal(request));
  }

  @PatchMapping
  public UserResponse updateMe(
      HttpServletRequest request, @RequestBody(required = false) PatchMeRequest body) {
    return userService.updateMe(principal(request), body);
  }

  private static InternalAuthPrincipal principal(HttpServletRequest request) {
    Object attribute = request.getAttribute(InternalAuthPrincipal.REQUEST_ATTRIBUTE);
    if (attribute instanceof InternalAuthPrincipal verified) {
      return verified;
    }
    throw new ApiErrorException(
        HttpStatus.UNAUTHORIZED,
        "internal_auth_invalid",
        "로그인이 필요해요.",
        "No verified InternalAuthPrincipal on a protected /me route.",
        false);
  }
}
