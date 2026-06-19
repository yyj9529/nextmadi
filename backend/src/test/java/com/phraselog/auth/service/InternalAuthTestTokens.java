package com.phraselog.auth.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * 테스트 전용 X-Internal-Auth 토큰 발급 헬퍼. 검증기를 위조 토큰으로 단위 테스트하기 위한 것이며, 운영 발급은 Next.js {@code
 * mintInternalAuthToken}(src/lib/internal-auth.ts)이 담당한다. 두 구현은 동일한 HS256 + 클레임 규약을 따른다(interop
 * 테스트로 교차 검증).
 */
public final class InternalAuthTestTokens {

  private InternalAuthTestTokens() {}

  public static String signedWithUser(String secret, String userId, Instant iat, Instant exp) {
    return sign(secret, JWSAlgorithm.HS256, claims(userId, null, iat, exp));
  }

  public static String signedWithSession(
      String secret, String sessionToken, Instant iat, Instant exp) {
    return sign(secret, JWSAlgorithm.HS256, claims(null, sessionToken, iat, exp));
  }

  /** 다른 알고리즘(HS384)으로 서명 — HS256 고정이 이를 거부하는지(알고리즘 핀) 확인용. */
  public static String signedWithAlg(
      String secret, JWSAlgorithm alg, String userId, Instant iat, Instant exp) {
    return sign(secret, alg, claims(userId, null, iat, exp));
  }

  /** alg=none 평문 JWT — 서명 없는 토큰이 거부되는지 확인용. */
  public static String unsignedNone(String userId, Instant iat, Instant exp) {
    return new PlainJWT(claims(userId, null, iat, exp)).serialize();
  }

  public static String signedWithClaims(String secret, JWTClaimsSet claims) {
    return sign(secret, JWSAlgorithm.HS256, claims);
  }

  public static JWTClaimsSet claims(String userId, String sessionToken, Instant iat, Instant exp) {
    JWTClaimsSet.Builder builder =
        new JWTClaimsSet.Builder().issueTime(Date.from(iat)).expirationTime(Date.from(exp));
    if (userId != null) {
      builder.claim("user_id", userId);
    }
    if (sessionToken != null) {
      builder.claim("session_token", sessionToken);
    }
    return builder.build();
  }

  private static String sign(String secret, JWSAlgorithm alg, JWTClaimsSet claims) {
    try {
      SignedJWT jwt = new SignedJWT(new JWSHeader(alg), claims);
      jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException("test token signing failed", e);
    }
  }
}
