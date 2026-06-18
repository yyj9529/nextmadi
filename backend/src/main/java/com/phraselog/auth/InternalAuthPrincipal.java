package com.phraselog.auth;

import org.springframework.util.StringUtils;

/**
 * 검증된 X-Internal-Auth 토큰이 표현하는 호출 주체. (#20, ADR-010)
 *
 * <p>인증 사용자는 {@code userId}, 가입 전 S02 익명 호출은 {@code sessionToken} — 정확히 하나만 존재한다. 다운스트림 핸들러는 이 값으로
 * 행(row) 소유권 인가를 수행한다. 토큰은 호출 주체를 식별할 뿐, 자원 수준 인가를 대체하지 않는다(ADR-010).
 *
 * <p>요청 속성으로 노출되는 키는 {@link #REQUEST_ATTRIBUTE}.
 */
public record InternalAuthPrincipal(String userId, String sessionToken) {

  /** {@code request.getAttribute(...)}로 핸들러가 주체를 읽는 키. */
  public static final String REQUEST_ATTRIBUTE = "phraselog.internalAuthPrincipal";

  public InternalAuthPrincipal {
    boolean hasUser = StringUtils.hasText(userId);
    boolean hasSession = StringUtils.hasText(sessionToken);
    if (hasUser == hasSession) {
      // 둘 다 있거나 둘 다 없으면 불변식 위반. 비밀/토큰 값은 메시지에 담지 않는다.
      throw new IllegalArgumentException(
          "internal auth principal requires exactly one of user_id / session_token");
    }
  }

  static InternalAuthPrincipal ofUser(String userId) {
    return new InternalAuthPrincipal(userId, null);
  }

  static InternalAuthPrincipal ofSession(String sessionToken) {
    return new InternalAuthPrincipal(null, sessionToken);
  }

  public boolean isAuthenticatedUser() {
    return userId != null;
  }
}
