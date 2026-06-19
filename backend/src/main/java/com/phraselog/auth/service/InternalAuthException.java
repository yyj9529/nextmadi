package com.phraselog.auth.service;

/**
 * X-Internal-Auth 검증 실패. (#20)
 *
 * <p>외부로는 모두 동일한 401 {@code internal_auth_invalid} 계약으로 매핑되지만(공격자에게 실패 원인을 흘리지 않는다), 내부 진단/테스트를 위해
 * {@link Reason}으로 구분한다. 메시지·필드에 토큰 원문이나 비밀 값을 절대 담지 않는다(SECURITY.md).
 */
public class InternalAuthException extends RuntimeException {

  public enum Reason {
    // 헤더 누락/공백은 필터가 토큰 파싱 전에 직접 401로 거부하므로 별도 사유를 두지 않는다.
    MALFORMED, // JWS 파싱 불가
    BAD_ALGORITHM, // alg!=HS256 (none/비대칭 — 알고리즘 혼동 방어)
    BAD_SIGNATURE, // 설정된 어떤 비밀로도 서명 검증 실패
    EXPIRED, // exp 누락 또는 leeway 초과
    BAD_TIMING, // iat 누락, 미래 iat, 또는 120s 초과 lifetime
    BAD_CLAIMS // user_id/session_token이 정확히 하나가 아님
  }

  private final Reason reason;

  public InternalAuthException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
