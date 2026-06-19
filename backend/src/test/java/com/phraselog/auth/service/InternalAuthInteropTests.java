package com.phraselog.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.phraselog.auth.config.InternalAuthProperties;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * jose(TS, src/lib/internal-auth.ts) → Nimbus(Java) 상호운용 검증. (#20)
 *
 * <p>아래 {@code JOSE_TOKEN}은 jose로 발급된 실제 토큰이다. Nimbus 검증기가 이를 통과시키면 두 구현이 동일한 HS256 서명·클레임 규약을 따른다는
 * 뜻 — 인코딩/알고리즘 drift를 잡는다. 토큰의 만료(exp)는 고정 시각 기준이므로 {@link Clock#fixed}로 그 시점에서 검증한다.
 *
 * <p>재생성: {@code bun run scripts/mint-interop-fixture.ts} 후 아래 상수를 갱신.
 */
class InternalAuthInteropTests {

  // scripts/mint-interop-fixture.ts 출력. 테스트 전용 더미 비밀(실제 비밀 아님).
  private static final String SHARED_SECRET = "interop-shared-secret-jose-and-nimbus-0123456789";
  private static final Instant FIXED_NOW = Instant.parse("2026-06-18T12:00:00Z");
  private static final String JOSE_TOKEN =
      "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
          + ".eyJ1c2VyX2lkIjoiaW50ZXJvcC11c2VyIiwiaWF0IjoxNzgxNzg0MDAwLCJleHAiOjE3ODE3ODQxMjB9"
          + ".uw7cG8ROJUPkt9I1uX-wNGtD4-ptH_maExtG4DDvIo4";

  @Test
  void nimbusVerifiesTokenMintedByJose() {
    InternalAuthVerifier verifier =
        new InternalAuthVerifier(
            new InternalAuthProperties(List.of(SHARED_SECRET), 30),
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

    InternalAuthPrincipal principal = verifier.verify(JOSE_TOKEN);

    assertThat(principal.userId()).isEqualTo("interop-user");
    assertThat(principal.isAuthenticatedUser()).isTrue();
  }
}
