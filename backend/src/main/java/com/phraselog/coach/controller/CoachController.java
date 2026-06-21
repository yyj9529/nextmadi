package com.phraselog.coach.controller;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.coach.dto.CoachListResponse;
import com.phraselog.coach.service.CoachService;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.common.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S03b coach catalog (#52). Behind {@link com.phraselog.auth.web.InternalAuthFilter}; the
 * controller stays thin: principal extraction and delegation only.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/coaches")
public class CoachController {

  private final CoachService coachService;

  public CoachController(CoachService coachService) {
    this.coachService = coachService;
  }

  @GetMapping
  public CoachListResponse list(HttpServletRequest request) {
    return new CoachListResponse(coachService.list(principal(request)));
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
        "No verified InternalAuthPrincipal on a protected coach route.",
        false);
  }
}
