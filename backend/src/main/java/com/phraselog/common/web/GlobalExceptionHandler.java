package com.phraselog.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

  /**
   * Spring rejects an oversize multipart upload before any controller runs. Without this the
   * catch-all below turned it into a retryable 500, so the client kept offering a retry that failed
   * the same way every time.
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ApiErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException exception) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(
            new ApiErrorResponse(
                "payload_too_large",
                "녹음이 너무 길어요. 다시 녹음해주세요.",
                "Upload exceeds spring.servlet.multipart.max-file-size or max-request-size.",
                false,
                requestCorrelationId()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrorResponse> handleUnhandledException(Exception exception) {
    // 전역 핸들러가 잡은 예외는 Spring이 별도로 로깅하지 않으므로 여기서 직접 남긴다.
    // developerHint가 약속하는 "request_correlation_id로 로그 조회"가 실제로 가능해진다.
    log.error(
        "Unhandled backend exception (request_correlation_id={})",
        requestCorrelationId(),
        exception);
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
