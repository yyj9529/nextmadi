package com.phraselog.usage.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcAnonymousAnalysisUsageRepositoryTests {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("phraselog_test")
          .withUsername("phraselog")
          .withPassword("phraselog");

  private static DataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private JdbcAnonymousAnalysisUsageRepository repository;

  @BeforeAll
  static void migrate() {
    dataSource =
        DataSourceBuilder.create()
            .url(postgres.getJdbcUrl())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.update("DELETE FROM anonymous_analysis_usage");
    repository = new JdbcAnonymousAnalysisUsageRepository(jdbcTemplate);
  }

  @Test
  void reservesOnlyTwoSlotsPerIpAndDate() {
    LocalDate today = LocalDate.of(2026, 6, 19);

    assertThat(repository.tryReserve("203.0.113.10", today, 2)).isTrue();
    assertThat(repository.currentCount("203.0.113.10", today)).isEqualTo(1);
    assertThat(repository.tryReserve("203.0.113.10", today, 2)).isTrue();
    assertThat(repository.currentCount("203.0.113.10", today)).isEqualTo(2);
    assertThat(repository.tryReserve("203.0.113.10", today, 2)).isFalse();
    assertThat(repository.currentCount("203.0.113.10", today)).isEqualTo(2);
  }

  @Test
  void separatesDifferentIpsAndDates() {
    LocalDate firstDay = LocalDate.of(2026, 6, 19);
    LocalDate secondDay = LocalDate.of(2026, 6, 20);

    repository.tryReserve("203.0.113.10", firstDay, 2);
    repository.tryReserve("198.51.100.12", firstDay, 2);
    repository.tryReserve("203.0.113.10", secondDay, 2);

    assertThat(repository.currentCount("203.0.113.10", firstDay)).isEqualTo(1);
    assertThat(repository.currentCount("198.51.100.12", firstDay)).isEqualTo(1);
    assertThat(repository.currentCount("203.0.113.10", secondDay)).isEqualTo(1);
  }

  @Test
  void releaseMakesAReservedSlotAvailableAgain() {
    LocalDate today = LocalDate.of(2026, 6, 19);

    repository.tryReserve("203.0.113.10", today, 2);
    repository.tryReserve("203.0.113.10", today, 2);
    repository.release("203.0.113.10", today);

    assertThat(repository.currentCount("203.0.113.10", today)).isEqualTo(1);
    assertThat(repository.tryReserve("203.0.113.10", today, 2)).isTrue();
    assertThat(repository.currentCount("203.0.113.10", today)).isEqualTo(2);
  }

  @Test
  void storesIpv6AddressesInPostgresInet() {
    LocalDate today = LocalDate.of(2026, 6, 19);

    assertThat(repository.tryReserve("2001:db8:0:0:0:0:0:1", today, 2)).isTrue();

    String inetText =
        jdbcTemplate.queryForObject(
            "SELECT host(ip_address) FROM anonymous_analysis_usage WHERE usage_date = ?",
            String.class,
            today);
    assertThat(inetText).isEqualTo("2001:db8::1");
  }

  @Test
  void concurrentReservationsAllowOnlyTheLimit() throws Exception {
    LocalDate today = LocalDate.of(2026, 6, 19);
    ExecutorService executor = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Boolean>> calls = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      calls.add(
          () -> {
            start.await();
            return repository.tryReserve("203.0.113.10", today, 2);
          });
    }

    List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
    for (Callable<Boolean> call : calls) {
      futures.add(executor.submit(call));
    }
    start.countDown();

    int successes = 0;
    for (java.util.concurrent.Future<Boolean> future : futures) {
      if (future.get()) {
        successes++;
      }
    }
    executor.shutdownNow();

    assertThat(successes).isEqualTo(2);
    assertThat(repository.currentCount("203.0.113.10", today)).isEqualTo(2);
  }
}
