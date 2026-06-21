package com.phraselog.user.controller;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.common.web.ApiPaths;
import com.phraselog.user.dto.UsageTodayResponse;
import com.phraselog.user.service.UsageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S11 daily usage counters (#52): {@code GET /usage/today}. Behind {@link
 * com.phraselog.auth.web.InternalAuthFilter}; thin principal extraction and delegation only.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/usage")
public class UsageController {

  private final UsageService usageService;

  public UsageController(UsageService usageService) {
    this.usageService = usageService;
  }

  @GetMapping("/today")
  public UsageTodayResponse today(HttpServletRequest request) {
    return usageService.getToday(principal(request));
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
        "No verified InternalAuthPrincipal on a protected usage route.",
        false);
  }
}
