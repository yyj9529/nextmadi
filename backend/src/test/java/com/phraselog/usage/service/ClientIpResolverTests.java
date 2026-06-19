package com.phraselog.usage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.phraselog.common.web.ApiErrorException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ClientIpResolverTests {

  private final ClientIpResolver resolver = new ClientIpResolver();

  @Test
  void acceptsSingleIpv4Literal() {
    assertThat(resolver.resolveRequired("203.0.113.10")).isEqualTo("203.0.113.10");
  }

  @Test
  void acceptsSingleIpv6LiteralAndNormalizesIt() {
    assertThat(resolver.resolveRequired("2001:db8::1")).isEqualTo("2001:db8:0:0:0:0:0:1");
  }

  @Test
  void rejectsMissingClientIpHeader() {
    assertValidationError(null);
  }

  @Test
  void rejectsAmbiguousForwardedChains() {
    assertValidationError("203.0.113.10, 198.51.100.12");
  }

  @Test
  void rejectsPortsAndHostnames() {
    assertValidationError("203.0.113.10:443");
    assertValidationError("example.com");
  }

  private void assertValidationError(String headerValue) {
    assertThatThrownBy(() -> resolver.resolveRequired(headerValue))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(error.errorCode()).isEqualTo("validation_failed");
            });
  }
}
