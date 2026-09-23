package com.phraselog.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.phraselog.auth.service.InternalAuthVerifier;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/**
 * 커밋된 개발용 내부 인증 비밀은 {@code local}/{@code test} 프로필에서만 받아준다. (#175)
 *
 * <p>프로필 설정이 빠진 배포가 공개된 비밀로 조용히 뜨는 대신 기동 시점에 실패해야 한다. Gradle 테스트 태스크가 {@code
 * spring.profiles.active=test}를 시스템 프로퍼티로 걸기 때문에, 여기서는 시스템 프로퍼티를 보지 않는 {@link MockEnvironment}로
 * 컨텍스트를 만들어 프로필을 케이스마다 직접 정한다.
 */
class InternalAuthDevelopmentSecretGuardTests {

  private static final String OTHER_SECRET = "some-real-looking-secret-0123456789abcdef";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner(
              () -> {
                AnnotationConfigApplicationContext context =
                    new AnnotationConfigApplicationContext();
                context.setEnvironment(new MockEnvironment());
                return context;
              })
          .withUserConfiguration(InternalAuthConfiguration.class);

  @Test
  void refusesDevelopmentSecretWithoutAnyProfile() {
    runner
        .withPropertyValues(secrets(InternalAuthProperties.DEVELOPMENT_SECRET))
        .run(
            context -> {
              assertRefusedByGuard(context);
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .hasMessageNotContaining(InternalAuthProperties.DEVELOPMENT_SECRET);
            });
  }

  @Test
  void refusesDevelopmentSecretInRotationOverlapSlot() {
    runner
        .withPropertyValues(secrets(OTHER_SECRET + "," + InternalAuthProperties.DEVELOPMENT_SECRET))
        .run(InternalAuthDevelopmentSecretGuardTests::assertRefusedByGuard);
  }

  @Test
  void refusesDevelopmentSecretUnderNonDevelopmentProfile() {
    runner
        .withPropertyValues(
            secrets(InternalAuthProperties.DEVELOPMENT_SECRET), "spring.profiles.active=prod")
        .run(InternalAuthDevelopmentSecretGuardTests::assertRefusedByGuard);
  }

  @Test
  void acceptsDevelopmentSecretUnderLocalProfile() {
    runner
        .withPropertyValues(
            secrets(InternalAuthProperties.DEVELOPMENT_SECRET), "spring.profiles.active=local")
        .run(context -> assertThat(context).hasSingleBean(InternalAuthVerifier.class));
  }

  @Test
  void acceptsDevelopmentSecretUnderTestProfile() {
    runner
        .withPropertyValues(
            secrets(InternalAuthProperties.DEVELOPMENT_SECRET), "spring.profiles.active=test")
        .run(context -> assertThat(context).hasSingleBean(InternalAuthVerifier.class));
  }

  @Test
  void acceptsOtherSecretWithoutAnyProfile() {
    runner
        .withPropertyValues(secrets(OTHER_SECRET))
        .run(context -> assertThat(context).hasSingleBean(InternalAuthVerifier.class));
  }

  @Test
  void applicationYamlDefaultIsTheGuardedValue() throws IOException {
    // application.yml의 폴백 값과 검사 상수가 어긋나면 검사가 아무것도 막지 못한다.
    try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
      assertThat(in).isNotNull();
      String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(yaml)
          .contains(
              "${PHRASELOG_INTERNAL_AUTH_SECRETS:"
                  + InternalAuthProperties.DEVELOPMENT_SECRET
                  + "}");
    }
  }

  // 실패 여부만 보면 다른 이유(바인딩 오류 등)로 컨텍스트가 죽어도 초록이 된다. 이 검사가 막았는지까지 본다.
  private static void assertRefusedByGuard(AssertableApplicationContext context) {
    assertThat(context).hasFailed();
    assertThat(context.getStartupFailure())
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("development internal auth secret");
  }

  private static String secrets(String value) {
    return "phraselog.internal-auth.secrets=" + value;
  }
}
