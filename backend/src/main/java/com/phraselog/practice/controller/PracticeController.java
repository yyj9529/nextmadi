package com.phraselog.practice.controller;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.common.web.ApiPaths;
import com.phraselog.practice.dto.PracticeSessionResponse;
import com.phraselog.practice.dto.StartSessionRequest;
import com.phraselog.practice.service.PracticeSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S12 roleplay session endpoints (#59). Both routes sit behind {@link
 * com.phraselog.auth.web.InternalAuthFilter}, which verifies {@code X-Internal-Auth} and exposes
 * the caller as a request attribute. The controller stays thin: principal/header extraction and
 * status mapping only; all behavior lives in {@link PracticeSessionService}.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/practice/sessions")
public class PracticeController {

  private final PracticeSessionService practiceSessionService;

  public PracticeController(PracticeSessionService practiceSessionService) {
    this.practiceSessionService = practiceSessionService;
  }

  @PostMapping
  public ResponseEntity<PracticeSessionResponse> start(
      HttpServletRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) StartSessionRequest body) {

    PracticeSessionResponse response =
        practiceSessionService.start(principal(request), body, idempotencyKey);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/{session_id}")
  public PracticeSessionResponse get(
      HttpServletRequest request, @PathVariable("session_id") String sessionId) {
    return practiceSessionService.get(principal(request), sessionId);
  }

  private static InternalAuthPrincipal principal(HttpServletRequest request) {
    Object attribute = request.getAttribute(InternalAuthPrincipal.REQUEST_ATTRIBUTE);
    if (attribute instanceof InternalAuthPrincipal verified) {
      return verified;
    }
    // The auth filter enforces a verified principal on /api/v1/**; reaching here means a wiring
    // gap, not a client error. Fail closed with the standard 401 contract.
    throw new ApiErrorException(
        HttpStatus.UNAUTHORIZED,
        "internal_auth_invalid",
        "로그인이 필요해요.",
        "No verified InternalAuthPrincipal on a protected practice route.",
        false);
  }
}
