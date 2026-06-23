package com.phraselog.coach.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.phraselog.coach.dto.CoachResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the coach catalog read and the V003 seed against a real Postgres (#52). Skipped without
 * Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcCoachRepositoryTests {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("phraselog_test")
          .withUsername("phraselog")
          .withPassword("phraselog");

  private static DataSource dataSource;

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

  @Test
  void findAllReturnsThreeSeededCoachesOrderedBySlug() {
    JdbcCoachRepository repository = new JdbcCoachRepository(new JdbcTemplate(dataSource));

    List<CoachResponse> coaches = repository.findAll();

    assertThat(coaches).hasSize(3);
    assertThat(coaches).extracting(CoachResponse::slug).containsExactly("david", "mia", "sarah");
    assertThat(coaches)
        .extracting(CoachResponse::displayName)
        .containsExactly("David", "Mia", "Sarah");
    assertThat(coaches)
        .extracting(CoachResponse::ttsVoiceId)
        .containsExactly("onyx", "shimmer", "nova");
    assertThat(coaches).allSatisfy(c -> assertThat(c.personaSummary()).isNotBlank());
  }

  @Test
  void findByIdReturnsSeededCoachAndEmptyForUnknownId() {
    JdbcCoachRepository repository = new JdbcCoachRepository(new JdbcTemplate(dataSource));
    CoachResponse mia =
        repository.findAll().stream().filter(c -> "mia".equals(c.slug())).findFirst().orElseThrow();

    Optional<CoachResponse> found = repository.findById(mia.id());
    Optional<CoachResponse> missing = repository.findById(UUID.randomUUID());

    assertThat(found).isPresent();
    assertThat(found.get().slug()).isEqualTo("mia");
    assertThat(found.get().displayName()).isEqualTo("Mia");
    assertThat(missing).isEmpty();
  }
}
