# Spring @ConditionalOnBean 순서 함정

## 증상

`POST /analysis` 요청이 인증/익명 무관하게 전부 500.

```
NoSuchBeanDefinitionException: No qualifying bean of type
'com.phraselog.usage.service.AnonymousAnalysisUsageService' available
  at AnalysisService.create(AnalysisService.java:94)
```

DataSource도 있고 프로필도 맞는데 빈이 없다. `ai_request_logs`는 0행 — LLM 호출
이전 단계에서 죽는다.

## 근본 원인

`AnonymousAnalysisUsageConfiguration`이 `@ConditionalOnBean(JdbcTemplate.class)`로
빈을 게이트했다.

Spring 처리 순서는 **사용자 `@Configuration`(컴포넌트 스캔) → 자동 구성
(DeferredImportSelector)** 이다. `JdbcTemplate`은 `JdbcTemplateAutoConfiguration`의
산물이므로, 사용자 config가 평가되는 시점에는 아직 빈 정의가 없다. 조건이 **항상
false**가 되어 서비스 빈이 조용히 생성되지 않는다. Spring 문서가 "`@ConditionalOnBean`은
auto-configuration 클래스에서만 쓰라"고 경고하는 바로 그 함정이다.

코드베이스의 다른 config(`AiLoggingConfiguration`, `AnalysisConfiguration`)는 전부
`ObjectProvider<DataSource>` 패턴으로 이 함정을 피하고 있었다. 이 파일 하나만 벗어나
있었다.

## 왜 4번이나 반복됐나

- 1회차(`20260619_1110`): 같은 함정을 인지하고 `ObjectProvider` 패턴으로 회피
- 2회차(`20260704_0525`): 로컬 E2E에서 500으로 재발견. 브랜치에서 수정했으나 미커밋
- 3회차(`20260718_0848`): 다시 발견, 수정 후 PR #123
- 4회차(`20260720_0905`): **브랜치 사고로 수정이 유실**되어 또 발견

3·4회차는 코드 문제가 아니라 [branch-hygiene](branch-hygiene.md) 문제다. 실수 하나가
다른 실수를 통해 부활한 사례.

## 올바른 방법

DB 의존 빈은 조건부 어노테이션이 아니라 지연 해석으로 게이트한다.

```java
@Bean
Xxx xxx(ObjectProvider<DataSource> dataSourceProvider, ...) {
  DataSource ds = dataSourceProvider.getIfAvailable();
  if (ds == null) return null;              // no-DB scaffold 계약 유지
  return new Xxx(new JdbcTemplate(ds), ...);
}
```

`getIfAvailable()`은 빈 생성 시점에 평가되므로 등록 순서와 무관하다.

## 자동화 후보

재발 4회.

- 아키텍처 테스트: `com.phraselog` 하위 사용자 `@Configuration`에
  `@ConditionalOnBean`이 있으면 실패시킨다 (auto-config가 아닌 곳에서 금지)
- 테스트 한 개면 영구히 막힌다. 리뷰 체크리스트로는 이미 3번 실패했다

## 재발 이력

- `20260619_1110`
- `20260704_0525`
- `20260718_0848`
- `20260720_0905`
