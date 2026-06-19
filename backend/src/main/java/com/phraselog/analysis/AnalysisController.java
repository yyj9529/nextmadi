package com.phraselog.analysis;

import com.phraselog.auth.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.common.web.ApiPaths;
import com.phraselog.usage.ClientIpResolver;
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
 * S07 analysis endpoints (#39). Both routes are behind {@link
 * com.phraselog.auth.InternalAuthFilter}, which verifies {@code X-Internal-Auth} and exposes the
 * caller as a request attribute. The controller stays thin: principal/header extraction and status
 * mapping only; all behavior lives in {@link AnalysisService}.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/analysis")
public class AnalysisController {

  private final AnalysisService analysisService;

  public AnalysisController(AnalysisService analysisService) {
    this.analysisService = analysisService;
  }

  @PostMapping
  public ResponseEntity<AnalysisResponse> create(
      HttpServletRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = ClientIpResolver.HEADER, required = false) String clientIp,
      @RequestBody(required = false) CreateAnalysisRequest body) {

    AnalysisResponse response =
        analysisService.create(principal(request), body, idempotencyKey, clientIp);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/{analysis_request_id}")
  public AnalysisResponse get(
      HttpServletRequest request, @PathVariable("analysis_request_id") String analysisRequestId) {
    return analysisService.get(principal(request), analysisRequestId);
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
        "No verified InternalAuthPrincipal on a protected analysis route.",
        false);
  }
}
