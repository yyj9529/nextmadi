// BFF 내부 인증 토큰(X-Internal-Auth) 발급 유틸. (#20, ADR-010)
//
// Next.js 서버가 요청마다 짧게 사는 HS256 JWS 토큰을 발급해 Spring Boot로 보낸다. Spring Boot의
// InternalAuthVerifier(backend, com.phraselog.auth)가 동일한 공유 비밀로 서명을 검증한다 —
// 두 구현은 같은 알고리즘(HS256)과 클레임 규약(user_id XOR session_token, iat, exp)을 따른다.
//
// 주의: 이 함수는 서버 전용이다. INTERNAL_AUTH_SECRET을 다루므로 브라우저 번들에 절대 포함되면
// 안 된다(route handler / server action 등 서버 코드에서만 호출).
//
// route handler에 실제로 연결하는 작업(NextAuth 세션 → mint → Spring Boot 프록시)은 BFF/NextAuth
// 티켓에서 한다. 여기서는 순수 발급 유틸만 제공한다.

import "server-only";
import { SignJWT } from "jose";

/** per-request 토큰 TTL(초). architecture.md "minutes" 범위, 확정값 120s. */
export const INTERNAL_AUTH_TTL_SECONDS = 120;

/** 인증 사용자는 userId, 가입 전 S02 익명 호출은 sessionToken — 정확히 하나만 준다. */
export type InternalAuthSubject =
  | { userId: string; sessionToken?: never }
  | { sessionToken: string; userId?: never };

export type MintOptions = {
  /** 기본 120s. 테스트/특수 상황에서만 재정의. */
  ttlSeconds?: number;
  /** 기본 현재 시각. 결정적 테스트/픽스처 생성용으로 주입. */
  now?: Date;
};

/**
 * X-Internal-Auth 헤더에 실을 서명된 JWS 토큰을 발급한다.
 *
 * @param subject user_id 또는 session_token (정확히 하나)
 * @param secret  INTERNAL_AUTH_SECRET (HS256이므로 32바이트 이상이어야 한다)
 */
export async function mintInternalAuthToken(
  subject: InternalAuthSubject,
  secret: string,
  options: MintOptions = {},
): Promise<string> {
  const key = new TextEncoder().encode(secret);
  if (key.length < 32) {
    throw new Error("INTERNAL_AUTH_SECRET must be at least 32 bytes for HS256");
  }

  const nowSeconds = Math.floor((options.now?.getTime() ?? Date.now()) / 1000);
  const ttl = options.ttlSeconds ?? INTERNAL_AUTH_TTL_SECONDS;

  const claims: Record<string, string> =
    "userId" in subject && subject.userId !== undefined
      ? { user_id: subject.userId }
      : { session_token: subject.sessionToken };

  return new SignJWT(claims)
    .setProtectedHeader({ alg: "HS256", typ: "JWT" })
    .setIssuedAt(nowSeconds)
    .setExpirationTime(nowSeconds + ttl)
    .sign(key);
}
