package com.phraselog.common.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class ErrorContractControllerAdviceTests {

  @Test
  void unhandledExceptionsReturnSafeFiveFieldContract() throws Exception {
    standaloneSetup(new TestErrorController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .addFilter(new RequestCorrelationFilter())
        .build()
        .perform(
            get(ApiPaths.V1 + "/test-errors/unhandled")
                .header("X-Request-Correlation-Id", "known-correlation-id"))
        .andExpect(status().isInternalServerError())
        .andExpect(header().string("X-Request-Correlation-Id", "known-correlation-id"))
        .andExpect(jsonPath("$.error_code", equalTo("internal_server_error")))
        .andExpect(jsonPath("$.user_message", equalTo("문제가 발생했어요. 잠시 후 다시 시도해 주세요.")))
        .andExpect(
            jsonPath(
                "$.developer_hint",
                equalTo("Unhandled backend exception. Check logs by request_correlation_id.")))
        .andExpect(jsonPath("$.developer_hint", not(containsString("raw secret user text"))))
        .andExpect(jsonPath("$.retryable", equalTo(true)))
        .andExpect(jsonPath("$.request_correlation_id", equalTo("known-correlation-id")));
  }

  @Test
  void knownClientAndDomainErrorsReturnContractForRepresentativeStatuses() throws Exception {
    var mockMvc =
        standaloneSetup(new TestErrorController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilter(new RequestCorrelationFilter())
            .build();

    mockMvc
        .perform(
            get(ApiPaths.V1 + "/test-errors/400").header("X-Request-Correlation-Id", "request-400"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error_code", equalTo("validation_failed")))
        .andExpect(jsonPath("$.user_message", equalTo("입력값을 다시 확인해 주세요.")))
        .andExpect(
            jsonPath(
                "$.developer_hint",
                equalTo("Validate request body and parameters before calling the service.")))
        .andExpect(jsonPath("$.retryable", equalTo(false)))
        .andExpect(jsonPath("$.request_correlation_id", equalTo("request-400")));

    mockMvc
        .perform(
            get(ApiPaths.V1 + "/test-errors/401").header("X-Request-Correlation-Id", "request-401"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error_code", equalTo("internal_auth_invalid")))
        .andExpect(jsonPath("$.user_message", equalTo("로그인이 필요해요.")))
        .andExpect(
            jsonPath(
                "$.developer_hint",
                equalTo("Check X-Internal-Auth signature, expiry, and required claims.")))
        .andExpect(jsonPath("$.retryable", equalTo(false)))
        .andExpect(jsonPath("$.request_correlation_id", equalTo("request-401")));

    mockMvc
        .perform(
            get(ApiPaths.V1 + "/test-errors/404").header("X-Request-Correlation-Id", "request-404"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error_code", equalTo("resource_not_found")))
        .andExpect(jsonPath("$.user_message", equalTo("요청한 항목을 찾을 수 없어요.")))
        .andExpect(
            jsonPath("$.developer_hint", equalTo("Verify resource id and row ownership check.")))
        .andExpect(jsonPath("$.retryable", equalTo(false)))
        .andExpect(jsonPath("$.request_correlation_id", equalTo("request-404")));

    mockMvc
        .perform(
            get(ApiPaths.V1 + "/test-errors/409").header("X-Request-Correlation-Id", "request-409"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error_code", equalTo("conflict")))
        .andExpect(jsonPath("$.user_message", equalTo("이미 처리된 요청이에요.")))
        .andExpect(
            jsonPath(
                "$.developer_hint",
                equalTo("Check idempotency key or unique constraint conflict handling.")))
        .andExpect(jsonPath("$.retryable", equalTo(false)))
        .andExpect(jsonPath("$.request_correlation_id", equalTo("request-409")));

    mockMvc
        .perform(
            get(ApiPaths.V1 + "/test-errors/429").header("X-Request-Correlation-Id", "request-429"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error_code", equalTo("rate_limit_exceeded")))
        .andExpect(jsonPath("$.user_message", equalTo("오늘 사용할 수 있는 횟수를 모두 썼어요.")))
        .andExpect(
            jsonPath(
                "$.developer_hint",
                equalTo("Check anonymous_analysis_usage or per-user daily limit counters.")))
        .andExpect(jsonPath("$.retryable", equalTo(true)))
        .andExpect(jsonPath("$.request_correlation_id", equalTo("request-429")));
  }

  @RestController
  private static class TestErrorController {

    @GetMapping(ApiPaths.V1 + "/test-errors/unhandled")
    void unhandled() {
      throw new IllegalStateException("raw secret user text must not leak");
    }

    @GetMapping(ApiPaths.V1 + "/test-errors/400")
    void badRequest() {
      throw new ApiErrorException(
          HttpStatus.BAD_REQUEST,
          "validation_failed",
          "입력값을 다시 확인해 주세요.",
          "Validate request body and parameters before calling the service.",
          false);
    }

    @GetMapping(ApiPaths.V1 + "/test-errors/401")
    void unauthorized() {
      throw new ApiErrorException(
          HttpStatus.UNAUTHORIZED,
          "internal_auth_invalid",
          "로그인이 필요해요.",
          "Check X-Internal-Auth signature, expiry, and required claims.",
          false);
    }

    @GetMapping(ApiPaths.V1 + "/test-errors/404")
    void notFound() {
      throw new ApiErrorException(
          HttpStatus.NOT_FOUND,
          "resource_not_found",
          "요청한 항목을 찾을 수 없어요.",
          "Verify resource id and row ownership check.",
          false);
    }

    @GetMapping(ApiPaths.V1 + "/test-errors/409")
    void conflict() {
      throw new ApiErrorException(
          HttpStatus.CONFLICT,
          "conflict",
          "이미 처리된 요청이에요.",
          "Check idempotency key or unique constraint conflict handling.",
          false);
    }

    @GetMapping(ApiPaths.V1 + "/test-errors/429")
    void rateLimited() {
      throw new ApiErrorException(
          HttpStatus.TOO_MANY_REQUESTS,
          "rate_limit_exceeded",
          "오늘 사용할 수 있는 횟수를 모두 썼어요.",
          "Check anonymous_analysis_usage or per-user daily limit counters.",
          true);
    }
  }
}
