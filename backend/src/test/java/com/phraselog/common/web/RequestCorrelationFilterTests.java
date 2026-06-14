package com.phraselog.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTests {

  @Test
  void usesIncomingCorrelationIdForMdcAndResponseHeader() throws Exception {
    RequestCorrelationFilter filter = new RequestCorrelationFilter();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/status");
    MockHttpServletResponse response = new MockHttpServletResponse();
    request.addHeader(RequestCorrelationFilter.CORRELATION_HEADER, "known-request-id");

    filter.doFilter(
        request,
        response,
        (servletRequest, servletResponse) ->
            assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isEqualTo("known-request-id"));

    assertThat(response.getHeader(RequestCorrelationFilter.CORRELATION_HEADER))
        .isEqualTo("known-request-id");
    assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
  }

  @Test
  void generatesCorrelationIdWhenHeaderIsMissing() throws Exception {
    RequestCorrelationFilter filter = new RequestCorrelationFilter();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/status");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> seenCorrelationId = new AtomicReference<>();

    filter.doFilter(
        request,
        response,
        (servletRequest, servletResponse) ->
            seenCorrelationId.set(MDC.get(RequestCorrelationFilter.MDC_KEY)));

    assertThat(seenCorrelationId.get()).isNotBlank();
    assertThat(response.getHeader(RequestCorrelationFilter.CORRELATION_HEADER))
        .isEqualTo(seenCorrelationId.get());
    assertThatCode(() -> UUID.fromString(seenCorrelationId.get())).doesNotThrowAnyException();
    assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
  }
}
