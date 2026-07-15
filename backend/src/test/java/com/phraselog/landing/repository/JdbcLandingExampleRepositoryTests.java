package com.phraselog.landing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.phraselog.landing.dto.LandingExampleResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the landing example read and the V007 seed against a real Postgres (#32). Skipped
 * without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcLandingExampleRepositoryTests {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("phraselog_test")
          .withUsername("phraselog")
          .withPassword("phraselog");

  private static DataSource dataSource;
  private static JdbcTemplate jdbcTemplate;

  @BeforeAll
  static void migrate() {
    dataSource =
        DataSourceBuilder.create()
            .url(postgres.getJdbcUrl())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbcTemplate = new JdbcTemplate(dataSource);
  }

  @AfterEach
  void reactivateAll() {
    // 개별 테스트가 is_active를 토글하더라도 다른 테스트에 오염되지 않도록 복원한다.
    jdbcTemplate.update("UPDATE landing_examples SET is_active = true");
  }

  @Test
  void findRandomActiveReturnsThreeExamplesFromSeededPool() {
    JdbcLandingExampleRepository repository = new JdbcLandingExampleRepository(jdbcTemplate);

    List<LandingExampleResponse> examples = repository.findRandomActive(3);

    assertThat(examples).hasSize(3);
    assertThat(examples).allSatisfy(e -> assertThat(e.id()).isNotNull());
    assertThat(examples).allSatisfy(e -> assertThat(e.koreanText()).isNotBlank());
  }

  @Test
  void findRandomActiveSamplesDifferentRowsAcrossCalls() {
    JdbcLandingExampleRepository repository = new JdbcLandingExampleRepository(jdbcTemplate);

    // 12개 시드 풀에서 LIMIT 3 무작위 표본을 20회 뽑으면, 항상 같은 3개만 나올 확률은
    // 사실상 0이다. 방문마다 다른 표본(s01) 계약을 결정론적이지 않게라도 확인한다.
    Set<UUID> seen = new HashSet<>();
    for (int i = 0; i < 20; i++) {
      repository.findRandomActive(3).forEach(e -> seen.add(e.id()));
    }

    assertThat(seen).hasSizeGreaterThan(3);
  }

  @Test
  void findRandomActiveReturnsEmptyWhenNoActiveRows() {
    jdbcTemplate.update("UPDATE landing_examples SET is_active = false");
    JdbcLandingExampleRepository repository = new JdbcLandingExampleRepository(jdbcTemplate);

    assertThat(repository.findRandomActive(3)).isEmpty();
  }
}
