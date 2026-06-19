package com.phraselog.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.auth.service.InternalAuthTestTokens;
import com.phraselog.common.web.ApiPaths;
import com.phraselog.common.web.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * X-Internal-Auth 필터의 전체 체인 통합 검증(#20): 실제 필터 순서(correlation→auth), 프로퍼티 바인딩, 401 에러 계약,
 * 공개/actuator 면제까지 실 컨텍스트로 확인한다. 수용 기준(유효 토큰 없는 직접 호출 → 401, 위조/만료 거부)을 다룬다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
      "phraselog.internal-auth.secrets=" + InternalAuthFilterIntegrationTests.SECRET,
      "phraselog.internal-auth.skew-leeway-seconds=30"
    })
@Import(InternalAuthFilterIntegrationTests.ProtectedTestController.class)
class InternalAuthFilterIntegrationTests {

  static final String SECRET = "integration-test-internal-auth-secret-0123456789";
  private static final String PROTECTED = ApiPaths.V1 + "/test-protected/ping";

  @Autowired private TestRestTemplate restTemplate;

  private static Instant iat() {
    return Instant.now().minusSeconds(5);
  }

  @Test
  void protectedCallWithoutTokenReturns401WithErrorContract() {
    HttpHeaders headers = new HttpHeaders();
    headers.set(RequestCorrelationFilter.CORRELATION_HEADER, "it-corr-1");

    ResponseEntity<String> response =
        restTemplate.exchange(PROTECTED, HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    // 기존 에러 계약(ErrorContractControllerAdviceTests의 401과 동일)을 그대로 따라야 한다.
    assertThat(response.getBody())
        .contains("\"error_code\":\"internal_auth_invalid\"")
        .contains("로그인이 필요해요.")
        .contains("\"retryable\":false")
        .contains("\"request_correlation_id\":\"it-corr-1\"");
    // correlation 필터가 auth 필터보다 먼저 실행되어 401에도 correlation id가 실린다.
    assertThat(response.getHeaders().getFirst(RequestCorrelationFilter.CORRELATION_HEADER))
        .isEqualTo("it-corr-1");
  }

  @Test
  void protectedCallWithValidUserTokenPassesAndExposesPrincipal() {
    Instant issuedAt = iat();
    String token =
        InternalAuthTestTokens.signedWithUser(
            SECRET, "user-1", issuedAt, issuedAt.plusSeconds(120));

    ResponseEntity<String> response = call(PROTECTED, token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("user:user-1");
  }

  @Test
  void protectedCallWithValidSessionTokenPasses() {
    Instant issuedAt = iat();
    String token =
        InternalAuthTestTokens.signedWithSession(
            SECRET, "sess-1", issuedAt, issuedAt.plusSeconds(120));

    ResponseEntity<String> response = call(PROTECTED, token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("session:sess-1");
  }

  @Test
  void protectedCallWithExpiredTokenReturns401() {
    String expired =
        InternalAuthTestTokens.signedWithUser(
            SECRET, "user-1", Instant.now().minusSeconds(600), Instant.now().minusSeconds(120));

    ResponseEntity<String> response = call(PROTECTED, expired);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void publicLandingPathIsNotGated() {
    // 공개 화이트리스트(security: []) — 토큰 없이도 401이 아니어야 한다.
    // 핸들러가 아직 없으므로 404가 되지만, 핵심은 "필터가 막지 않았다".
    ResponseEntity<String> response =
        restTemplate.getForEntity(ApiPaths.V1 + "/landing/examples", String.class);

    assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void actuatorHealthIsNotGated() {
    ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private ResponseEntity<String> call(String path, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(InternalAuthFilter.HEADER, token);
    return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  @TestConfiguration
  @RestController
  static class ProtectedTestController {

    @GetMapping(ApiPaths.V1 + "/test-protected/ping")
    String ping(HttpServletRequest request) {
      InternalAuthPrincipal principal =
          (InternalAuthPrincipal) request.getAttribute(InternalAuthPrincipal.REQUEST_ATTRIBUTE);
      return principal.isAuthenticatedUser()
          ? "user:" + principal.userId()
          : "session:" + principal.sessionToken();
    }
  }
}
