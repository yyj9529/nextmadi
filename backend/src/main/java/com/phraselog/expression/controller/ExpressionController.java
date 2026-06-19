package com.phraselog.expression.controller;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.common.web.ApiPaths;
import com.phraselog.expression.dto.CreateExpressionRequest;
import com.phraselog.expression.dto.ExpressionResponse;
import com.phraselog.expression.dto.SaveExpressionResult;
import com.phraselog.expression.service.ExpressionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal BFF-to-Spring endpoint for saving S07 analysis output (#41). */
@RestController
@RequestMapping(ApiPaths.V1 + "/expressions")
public class ExpressionController {

  private final ExpressionService expressionService;

  public ExpressionController(ExpressionService expressionService) {
    this.expressionService = expressionService;
  }

  @PostMapping
  public ResponseEntity<ExpressionResponse> create(
      HttpServletRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) CreateExpressionRequest body) {
    SaveExpressionResult result =
        expressionService.create(principal(request), body, idempotencyKey);
    HttpStatus status = result.duplicate() ? HttpStatus.CONFLICT : HttpStatus.CREATED;
    return ResponseEntity.status(status).body(result.expression());
  }

  private static InternalAuthPrincipal principal(HttpServletRequest request) {
    Object attribute = request.getAttribute(InternalAuthPrincipal.REQUEST_ATTRIBUTE);
    if (attribute instanceof InternalAuthPrincipal verified) {
      return verified;
    }
    throw new ApiErrorException(
        HttpStatus.UNAUTHORIZED,
        "internal_auth_invalid",
        "Login required.",
        "No verified InternalAuthPrincipal on a protected expression route.",
        false);
  }
}
