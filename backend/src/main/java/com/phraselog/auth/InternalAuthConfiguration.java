package com.phraselog.auth;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** BFF 내부 인증(#20) 빈 구성. {@link InternalAuthFilter}는 @Component로 자동 등록된다. */
@Configuration
@EnableConfigurationProperties(InternalAuthProperties.class)
public class InternalAuthConfiguration {

  @Bean
  InternalAuthVerifier internalAuthVerifier(InternalAuthProperties properties) {
    // 운영 시계. 테스트는 Clock.fixed(...)로 만료/leeway를 결정적으로 검증한다.
    return new InternalAuthVerifier(properties, Clock.systemUTC());
  }
}
