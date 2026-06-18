package com.phraselog.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * X-Internal-Auth JWS 토큰을 검증해 호출 주체를 추출한다. (#20, ADR-010)
 *
 * <p>순수 검증 단위 — 서블릿 타입에 의존하지 않으므로 토큰을 위조해 단위 테스트한다. 정책:
 *
 * <ul>
 *   <li>알고리즘은 HS256으로 고정. 토큰 헤더의 {@code alg}를 신뢰하지 않는다 — {@code none}이나 비대칭 알고리즘은 즉시 거부(알고리즘 혼동 공격
 *       방어).
 *   <li>설정된 비밀 목록을 앞에서부터 시도(키 회전 오버랩). 하나라도 서명이 맞으면 통과.
 *   <li>{@code iat}/{@code exp}는 필수. lifetime은 최대 120초이고, {@code iat}가 leeway 이상 미래이면 거부.
 *   <li>{@code now > exp + leeway}면 만료.
 *   <li>{@code user_id} / {@code session_token} 중 정확히 하나만 허용.
 * </ul>
 */
public class InternalAuthVerifier {

  private static final Duration MAX_TOKEN_LIFETIME = Duration.ofSeconds(120);

  private final List<byte[]> secretKeys;
  private final Duration leeway;
  private final Clock clock;

  public InternalAuthVerifier(InternalAuthProperties properties, Clock clock) {
    List<String> secrets = properties.secrets();
    if (secrets == null || secrets.isEmpty()) {
      throw new IllegalStateException(
          "phraselog.internal-auth.secrets must configure at least one secret");
    }
    this.secretKeys = new ArrayList<>(secrets.size());
    for (String secret : secrets) {
      byte[] key = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
      if (key.length < InternalAuthProperties.MIN_SECRET_BYTES) {
        // 설정 오류는 시작 시점에 빠르게 드러낸다. 비밀 값 자체는 메시지에 담지 않는다.
        throw new IllegalStateException(
            "internal auth secret too short for HS256: need >= "
                + InternalAuthProperties.MIN_SECRET_BYTES
                + " bytes");
      }
      this.secretKeys.add(key);
    }
    this.leeway = Duration.ofSeconds(Math.max(0, properties.skewLeewaySeconds()));
    this.clock = clock;
  }

  /**
   * 토큰을 검증하고 주체를 반환한다. 어떤 실패든 {@link InternalAuthException}을 던진다.
   *
   * @throws InternalAuthException 헤더/서명/만료/클레임 위반 시
   */
  public InternalAuthPrincipal verify(String token) {
    SignedJWT jwt = parse(token);
    requireHs256(jwt);
    requireValidSignature(jwt);
    JWTClaimsSet claims = extractClaims(jwt);
    requireValidTiming(claims);
    return resolvePrincipal(claims);
  }

  private SignedJWT parse(String token) {
    try {
      return SignedJWT.parse(token);
    } catch (ParseException e) {
      throw new InternalAuthException(InternalAuthException.Reason.MALFORMED);
    }
  }

  private void requireHs256(SignedJWT jwt) {
    if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
      throw new InternalAuthException(InternalAuthException.Reason.BAD_ALGORITHM);
    }
  }

  private void requireValidSignature(SignedJWT jwt) {
    for (byte[] key : secretKeys) {
      try {
        if (jwt.verify(new MACVerifier(key))) {
          return;
        }
      } catch (JOSEException e) {
        // 이 비밀로는 검증 불가 — 다음 비밀(회전 오버랩)을 시도한다.
      }
    }
    throw new InternalAuthException(InternalAuthException.Reason.BAD_SIGNATURE);
  }

  private JWTClaimsSet extractClaims(SignedJWT jwt) {
    try {
      return jwt.getJWTClaimsSet();
    } catch (ParseException e) {
      throw new InternalAuthException(InternalAuthException.Reason.MALFORMED);
    }
  }

  private void requireValidTiming(JWTClaimsSet claims) {
    Date iat = claims.getIssueTime();
    Date exp = claims.getExpirationTime();
    if (exp == null) {
      throw new InternalAuthException(InternalAuthException.Reason.EXPIRED);
    }
    if (iat == null) {
      throw new InternalAuthException(InternalAuthException.Reason.BAD_TIMING);
    }

    Instant issuedAt = iat.toInstant();
    Instant expiresAt = exp.toInstant();
    Instant now = Instant.now(clock);
    if (!expiresAt.isAfter(issuedAt)
        || Duration.between(issuedAt, expiresAt).compareTo(MAX_TOKEN_LIFETIME) > 0
        || !issuedAt.isBefore(now.plus(leeway))) {
      throw new InternalAuthException(InternalAuthException.Reason.BAD_TIMING);
    }
    if (now.isAfter(expiresAt.plus(leeway))) {
      throw new InternalAuthException(InternalAuthException.Reason.EXPIRED);
    }
  }

  private InternalAuthPrincipal resolvePrincipal(JWTClaimsSet claims) {
    String userId = stringClaim(claims, "user_id");
    String sessionToken = stringClaim(claims, "session_token");
    boolean hasUser = userId != null && !userId.isBlank();
    boolean hasSession = sessionToken != null && !sessionToken.isBlank();
    if (hasUser == hasSession) {
      throw new InternalAuthException(InternalAuthException.Reason.BAD_CLAIMS);
    }
    return hasUser
        ? InternalAuthPrincipal.ofUser(userId)
        : InternalAuthPrincipal.ofSession(sessionToken);
  }

  private String stringClaim(JWTClaimsSet claims, String name) {
    try {
      return claims.getStringClaim(name);
    } catch (ParseException e) {
      // 문자열이 아닌 타입으로 들어온 클레임 — 클레임 위반으로 취급.
      throw new InternalAuthException(InternalAuthException.Reason.BAD_CLAIMS);
    }
  }
}
