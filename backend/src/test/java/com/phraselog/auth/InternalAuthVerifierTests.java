package com.phraselog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class InternalAuthVerifierTests {

  // HS256 키는 >=32바이트여야 한다. HS384 알고리즘 핀 테스트용 LONG_SECRET은 >=48바이트.
  private static final String PRIMARY = "primary-internal-auth-secret-0123456789abcdef";
  private static final String SECONDARY = "secondary-rotation-secret-0123456789abcdef!!";
  private static final String OTHER = "some-other-unrelated-secret-0123456789abcdef";
  private static final String LONG_SECRET =
      "long-secret-for-hs384-needs-48-bytes-0123456789abcdefghijklmnop";

  private static final long LEEWAY = 30;
  private static final Instant NOW = Instant.parse("2026-06-18T12:00:00Z");
  private static final Instant IAT = NOW.minusSeconds(10);
  private static final Instant FRESH_EXP = NOW.plusSeconds(110); // 120s TTL 발급분

  private InternalAuthVerifier verifier(List<String> secrets) {
    return new InternalAuthVerifier(
        new InternalAuthProperties(secrets, LEEWAY), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void verifiesAuthenticatedUserToken() {
    String token = InternalAuthTestTokens.signedWithUser(PRIMARY, "user-1", IAT, FRESH_EXP);

    InternalAuthPrincipal principal = verifier(List.of(PRIMARY)).verify(token);

    assertThat(principal.userId()).isEqualTo("user-1");
    assertThat(principal.sessionToken()).isNull();
    assertThat(principal.isAuthenticatedUser()).isTrue();
  }

  @Test
  void verifiesAnonymousSessionToken() {
    String token = InternalAuthTestTokens.signedWithSession(PRIMARY, "sess-1", IAT, FRESH_EXP);

    InternalAuthPrincipal principal = verifier(List.of(PRIMARY)).verify(token);

    assertThat(principal.sessionToken()).isEqualTo("sess-1");
    assertThat(principal.userId()).isNull();
    assertThat(principal.isAuthenticatedUser()).isFalse();
  }

  @Test
  void rejectsTokenSignedWithUnknownSecret() {
    String forged = InternalAuthTestTokens.signedWithUser(OTHER, "user-1", IAT, FRESH_EXP);

    assertRejected(forged, InternalAuthException.Reason.BAD_SIGNATURE);
  }

  @Test
  void rejectsExpiredTokenBeyondLeeway() {
    Instant exp = NOW.minusSeconds(LEEWAY + 1); // leeway 밖
    String token =
        InternalAuthTestTokens.signedWithUser(PRIMARY, "user-1", exp.minusSeconds(120), exp);

    assertRejected(token, InternalAuthException.Reason.EXPIRED);
  }

  @Test
  void acceptsTokenExpiredWithinLeeway() {
    Instant exp = NOW.minusSeconds(LEEWAY - 5); // leeway 안
    String token =
        InternalAuthTestTokens.signedWithUser(PRIMARY, "user-1", exp.minusSeconds(120), exp);

    assertThat(verifier(List.of(PRIMARY)).verify(token).userId()).isEqualTo("user-1");
  }

  @Test
  void rejectsTokenMissingExpiry() {
    JWTClaimsSet noExp = new JWTClaimsSet.Builder().claim("user_id", "user-1").build();
    String token = InternalAuthTestTokens.signedWithClaims(PRIMARY, noExp);

    assertRejected(token, InternalAuthException.Reason.EXPIRED);
  }

  @Test
  void rejectsTokenMissingIssuedAt() {
    JWTClaimsSet noIat =
        new JWTClaimsSet.Builder()
            .claim("user_id", "user-1")
            .expirationTime(java.util.Date.from(FRESH_EXP))
            .build();
    String token = InternalAuthTestTokens.signedWithClaims(PRIMARY, noIat);

    assertRejected(token, InternalAuthException.Reason.BAD_TIMING);
  }

  @Test
  void rejectsTokenWithLifetimeLongerThanTtl() {
    String token =
        InternalAuthTestTokens.signedWithUser(PRIMARY, "user-1", IAT, IAT.plusSeconds(121));

    assertRejected(token, InternalAuthException.Reason.BAD_TIMING);
  }

  @Test
  void rejectsTokenIssuedAtLeewayOrMoreInFuture() {
    Instant futureIat = NOW.plusSeconds(LEEWAY);
    String token =
        InternalAuthTestTokens.signedWithUser(
            PRIMARY, "user-1", futureIat, futureIat.plusSeconds(120));

    assertRejected(token, InternalAuthException.Reason.BAD_TIMING);
  }

  @Test
  void rejectsNonHs256Algorithm() {
    // HS384는 유효한 MAC 알고리즘이지만 우리는 HS256만 허용한다(알고리즘 핀).
    String token =
        InternalAuthTestTokens.signedWithAlg(
            LONG_SECRET, JWSAlgorithm.HS384, "user-1", IAT, FRESH_EXP);

    assertRejected(token, InternalAuthException.Reason.BAD_ALGORITHM);
  }

  @Test
  void rejectsUnsignedNoneToken() {
    // alg=none 평문 토큰은 어떤 사유로든 거부되어야 한다(서명 없는 신뢰 불가 토큰).
    String token = InternalAuthTestTokens.unsignedNone("user-1", IAT, FRESH_EXP);

    assertThatThrownBy(() -> verifier(List.of(PRIMARY)).verify(token))
        .isInstanceOf(InternalAuthException.class);
  }

  @Test
  void rejectsTokenWithBothSubjectClaims() {
    JWTClaimsSet both = InternalAuthTestTokens.claims("user-1", "sess-1", IAT, FRESH_EXP);
    String token = InternalAuthTestTokens.signedWithClaims(PRIMARY, both);

    assertRejected(token, InternalAuthException.Reason.BAD_CLAIMS);
  }

  @Test
  void rejectsTokenWithNeitherSubjectClaim() {
    JWTClaimsSet neither = InternalAuthTestTokens.claims(null, null, IAT, FRESH_EXP);
    String token = InternalAuthTestTokens.signedWithClaims(PRIMARY, neither);

    assertRejected(token, InternalAuthException.Reason.BAD_CLAIMS);
  }

  @Test
  void rejectsMalformedToken() {
    assertRejected("not-a-jwt", InternalAuthException.Reason.MALFORMED);
  }

  @Test
  void acceptsTokenSignedWithRotationSecondarySecret() {
    // 회전 오버랩: 토큰은 이전(보조) 비밀로 서명, 검증기는 [현재, 이전]을 설정.
    String token = InternalAuthTestTokens.signedWithUser(SECONDARY, "user-1", IAT, FRESH_EXP);

    InternalAuthPrincipal principal = verifier(List.of(PRIMARY, SECONDARY)).verify(token);

    assertThat(principal.userId()).isEqualTo("user-1");
  }

  @Test
  void rejectsConfigurationWithSecretTooShortForHs256() {
    assertThatThrownBy(() -> verifier(List.of("too-short")))
        .isInstanceOf(IllegalStateException.class);
  }

  private void assertRejected(String token, InternalAuthException.Reason expected) {
    Consumer<Throwable> hasReason =
        t -> assertThat(((InternalAuthException) t).reason()).isEqualTo(expected);
    assertThatThrownBy(() -> verifier(List.of(PRIMARY)).verify(token))
        .isInstanceOf(InternalAuthException.class)
        .satisfies(hasReason);
  }
}
