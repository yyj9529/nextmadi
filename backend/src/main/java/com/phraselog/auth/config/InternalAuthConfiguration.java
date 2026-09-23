package com.phraselog.auth.config;

import com.phraselog.auth.service.InternalAuthVerifier;
import com.phraselog.auth.web.InternalAuthFilter;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** BFF 내부 인증(#20) 빈 구성. {@link InternalAuthFilter}는 @Component로 자동 등록된다. */
@Configuration
@EnableConfigurationProperties(InternalAuthProperties.class)
public class InternalAuthConfiguration {

  @Bean
  InternalAuthVerifier internalAuthVerifier(
      InternalAuthProperties properties, Environment environment) {
    refuseDevelopmentSecretOutsideDevelopment(properties, environment);
    // 운영 시계. 테스트는 Clock.fixed(...)로 만료/leeway를 결정적으로 검증한다.
    return new InternalAuthVerifier(properties, Clock.systemUTC());
  }

  /**
   * 커밋된 개발용 비밀이 local/test 밖에서 쓰이면 기동을 멈춘다 (#175). 프로필 설정이 빠진 배포는 default 프로필로 떠서 application.yml의
   * 개발용 폴백을 조용히 쓰게 되는데, 그 값은 저장소에 공개돼 있어 누구나 내부 요청을 서명할 수 있다.
   */
  private static void refuseDevelopmentSecretOutsideDevelopment(
      InternalAuthProperties properties, Environment environment) {
    List<String> secrets = properties.secrets();
    if (secrets == null || !secrets.contains(InternalAuthProperties.DEVELOPMENT_SECRET)) {
      return;
    }
    if (environment.acceptsProfiles(Profiles.of(InternalAuthProperties.DEVELOPMENT_PROFILES))) {
      return;
    }
    // 비밀 값은 메시지에 담지 않는다 (InternalAuthVerifier와 같은 원칙).
    throw new IllegalStateException(
        "phraselog.internal-auth.secrets contains the committed development internal auth secret,"
            + " which is only allowed with the 'local' or 'test' profile. Active profiles: "
            + Arrays.toString(environment.getActiveProfiles())
            + ". Inject the real secret (PHRASELOG_INTERNAL_AUTH_SECRETS, or INTERNAL_AUTH_SECRET"
            + " under the 'prod' profile), or start with --spring.profiles.active=local.");
  }
}
