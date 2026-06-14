package com.phraselog.common.web;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ApiErrorException.class)
  ResponseEntity<ApiErrorResponse> handleApiError(ApiErrorException exception) {
    return ResponseEntity.status(exception.status())
        .body(
            new ApiErrorResponse(
                exception.errorCode(),
                exception.userMessage(),
                exception.developerHint(),
                exception.retryable(),
                requestCorrelationId()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrorResponse> handleUnhandledException(Exception exception) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            new ApiErrorResponse(
                "internal_server_error",
                "문제가 발생했어요. 잠시 후 다시 시도해 주세요.",
                "Unhandled backend exception. Check logs by request_correlation_id.",
                true,
                requestCorrelationId()));
  }

  private String requestCorrelationId() {
    String correlationId = MDC.get(RequestCorrelationFilter.MDC_KEY);
    if (StringUtils.hasText(correlationId)) {
      return correlationId;
    }
    return "unknown";
  }
}
