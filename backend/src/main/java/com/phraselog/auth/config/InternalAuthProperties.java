package com.phraselog.auth.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * BFF 내부 인증(X-Internal-Auth) 검증 설정. (#20, ADR-010)
 *
 * <p>{@code secrets}는 HS256 공유 비밀의 순서 있는 목록이다. 검증기는 앞에서부터 차례로 시도한다 — 첫 번째가 현재 비밀, 뒤따르는 항목은 키 회전 오버랩
 * 기간의 이전 비밀이다 (architecture.md 회전 정책: 연 1회 수동, 2-secret 오버랩). v1은 하나만 설정한다.
 *
 * <p>{@code skewLeewaySeconds}는 Next.js와 EC2 간 시계 오차 보정용 만료 허용치다(확정 30s). 실제 비밀 값은 절대 소스/커밋에 두지 않는다
 * — Secrets Manager(prod) / 환경변수(dev)로 주입.
 */
@ConfigurationProperties(prefix = "phraselog.internal-auth")
public record InternalAuthProperties(List<String> secrets, long skewLeewaySeconds) {

  /** HS256은 키 길이가 최소 256비트(32바이트)여야 한다. 짧은 비밀은 시작 시점에 거부한다. */
  public static final int MIN_SECRET_BYTES = 32;

  /**
   * application.yml에 커밋된 개발용 폴백 비밀. 저장소가 공개되면 누구나 아는 값이라 {@link #DEVELOPMENT_PROFILES}에서만 받아준다
   * (#175).
   */
  public static final String DEVELOPMENT_SECRET =
      "dev-placeholder-internal-auth-secret-do-not-use-in-prod";

  /** {@link #DEVELOPMENT_SECRET}으로 기동해도 되는 프로필. */
  public static final String[] DEVELOPMENT_PROFILES = {"local", "test"};
}
