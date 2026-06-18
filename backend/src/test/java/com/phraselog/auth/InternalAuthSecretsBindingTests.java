package com.phraselog.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 콤마 구분 {@code phraselog.internal-auth.secrets} 설정이 실제로 {@code List<String>}로 바인딩되어 키 회전
 * 오버랩(architecture.md: 연 1회 수동, 2-secret)이 코드 변경 없이 동작하는지 확정한다. (#20)
 *
 * <p>검증기 단위 테스트는 {@code List.of(a, b)}를 직접 넘겨 순서 시도 로직만 본다. 이 테스트는 운영이 실제로 쓸 바인딩 포맷("새비밀,이전비밀")이
 * 깨지지 않음을 잠근다 — 두 번째(이전) 비밀로 서명된 토큰이 통과해야 한다.
 */
@SpringBootTest(
    // MOCK 웹 컨텍스트가 필요하다 — InternalAuthFilter가 handlerExceptionResolver 빈에 의존한다.
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
      "phraselog.internal-auth.secrets="
          + InternalAuthSecretsBindingTests.CURRENT
          + ","
          + InternalAuthSecretsBindingTests.PREVIOUS,
      "phraselog.internal-auth.skew-leeway-seconds=30"
    })
class InternalAuthSecretsBindingTests {

  // 테스트 전용 더미(>=32바이트). 실제 비밀 아님.
  static final String CURRENT = "current-rotation-secret-0123456789abcdef";
  static final String PREVIOUS = "previous-rotation-secret-0123456789abcdef";

  @Autowired private InternalAuthVerifier verifier;

  @Test
  void tokenSignedWithPreviousSecretFromCommaListVerifies() {
    Instant issuedAt = Instant.now().minusSeconds(5);
    String token =
        InternalAuthTestTokens.signedWithUser(
            PREVIOUS, "user-1", issuedAt, issuedAt.plusSeconds(120));

    InternalAuthPrincipal principal = verifier.verify(token);

    assertThat(principal.userId()).isEqualTo("user-1");
  }
}
