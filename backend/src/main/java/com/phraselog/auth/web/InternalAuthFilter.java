package com.phraselog.auth.web;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.auth.service.InternalAuthException;
import com.phraselog.auth.service.InternalAuthVerifier;
import com.phraselog.common.web.ApiErrorException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 모든 보호 대상 요청에 대해 X-Internal-Auth JWS 토큰을 강제한다. (#20, ADR-010)
 *
 * <p>{@link com.phraselog.common.web.RequestCorrelationFilter} 바로 다음에 실행되어, 401 응답에도 correlation
 * id가 실린다. 검증 성공 시 {@link InternalAuthPrincipal}을 요청 속성으로 노출하고, 실패 시에는 직접 JSON을 쓰지 않고 {@link
 * HandlerExceptionResolver}에 위임한다 — 그러면 기존 {@code GlobalExceptionHandler}가 표준 5필드 에러 계약({@code
 * internal_auth_invalid})으로 렌더링하므로 계약이 한 곳에서만 정의된다.
 *
 * <p>페일클로즈: {@code /api/v1/**}는 공개 화이트리스트에 없으면 토큰을 요구한다. 새 엔드포인트가 실수로 무인증 노출되지 않도록 한다. 화이트리스트는
 * openapi의 {@code security: []}를 그대로 반영한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class InternalAuthFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Internal-Auth";

  private static final String API_PREFIX = "/api/v1";

  private final InternalAuthVerifier verifier;
  private final HandlerExceptionResolver exceptionResolver;

  public InternalAuthFilter(
      InternalAuthVerifier verifier,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
    this.verifier = verifier;
    this.exceptionResolver = exceptionResolver;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    if (!requiresAuth(request)) {
      filterChain.doFilter(request, response);
      return;
    }

    String token = request.getHeader(HEADER);
    if (!StringUtils.hasText(token)) {
      reject(request, response);
      return;
    }

    InternalAuthPrincipal principal;
    try {
      principal = verifier.verify(token);
    } catch (InternalAuthException e) {
      reject(request, response);
      return;
    }

    request.setAttribute(InternalAuthPrincipal.REQUEST_ATTRIBUTE, principal);
    filterChain.doFilter(request, response);
  }

  /** {@code /api/v1/**} 중 공개 화이트리스트에 없는 경로만 인증을 요구한다(페일클로즈). */
  private boolean requiresAuth(HttpServletRequest request) {
    String path = request.getRequestURI();
    if (path == null || !path.startsWith(API_PREFIX)) {
      return false; // actuator 등 비-API 경로는 이 필터의 관심사가 아니다.
    }
    return !isPublic(request, path);
  }

  /** openapi {@code security: []}와 1:1로 대응. 현재는 S01 랜딩 예시 한 곳뿐이다. */
  private boolean isPublic(HttpServletRequest request, String path) {
    return HttpMethod.GET.matches(request.getMethod())
        && (API_PREFIX + "/landing/examples").equals(path);
  }

  private void reject(HttpServletRequest request, HttpServletResponse response) {
    // 실패 원인(서명/만료/클레임)을 외부로 구분해 노출하지 않는다 — 모두 동일한 401 계약.
    ApiErrorException unauthorized =
        new ApiErrorException(
            HttpStatus.UNAUTHORIZED,
            "internal_auth_invalid",
            "로그인이 필요해요.",
            "Check X-Internal-Auth signature, expiry, and required claims.",
            false);
    exceptionResolver.resolveException(request, response, null, unauthorized);
  }
}
