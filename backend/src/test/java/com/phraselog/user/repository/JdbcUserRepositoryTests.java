package com.phraselog.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.phraselog.user.dto.UserResponse;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
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

/** Verifies user profile read/update against a real Postgres (#52). Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcUserRepositoryTests {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("phraselog_test")
          .withUsername("phraselog")
          .withPassword("phraselog");

  private static DataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private JdbcUserRepository repository;

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
    jdbcTemplate.update("DELETE FROM users");
    repository = new JdbcUserRepository(jdbcTemplate);
  }

  @Test
  void findByIdReturnsActiveUser() {
    UUID id = insertUser("owner@example.com");

    Optional<UserResponse> found = repository.findById(id);

    assertThat(found).isPresent();
    assertThat(found.get().email()).isEqualTo("owner@example.com");
    assertThat(found.get().isOnboarded()).isFalse();
  }

  @Test
  void findByIdSkipsSoftDeletedUser() {
    UUID id = insertUser("gone@example.com");
    jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?", id);

    assertThat(repository.findById(id)).isEmpty();
  }

  @Test
  void coachExistsReflectsSeededCatalog() {
    UUID mia =
        jdbcTemplate.queryForObject("SELECT id FROM coach_profiles WHERE slug = 'mia'", UUID.class);

    assertThat(repository.coachExists(mia)).isTrue();
    assertThat(repository.coachExists(UUID.randomUUID())).isFalse();
  }

  @Test
  void updateAppliesDisplayNameCoachAndOnboarding() {
    UUID id = insertUser("owner@example.com");
    UUID mia =
        jdbcTemplate.queryForObject("SELECT id FROM coach_profiles WHERE slug = 'mia'", UUID.class);

    Optional<UserResponse> updated = repository.update(id, "새이름", mia, true);

    assertThat(updated).isPresent();
    assertThat(updated.get().displayName()).isEqualTo("새이름");
    assertThat(updated.get().selectedCoachId()).isEqualTo(mia);
    assertThat(updated.get().isOnboarded()).isTrue();
  }

  @Test
  void updateLeavesUnspecifiedColumnsUntouched() {
    UUID id = insertUser("owner@example.com");
    repository.update(id, "first", null, true);

    Optional<UserResponse> updated = repository.update(id, null, null, false);

    assertThat(updated).isPresent();
    assertThat(updated.get().displayName()).isEqualTo("first"); // unchanged
    assertThat(updated.get().isOnboarded()).isTrue(); // unchanged — never reset to false
  }

  @Test
  void updateReturnsEmptyForUnknownUser() {
    assertThat(repository.update(UUID.randomUUID(), "x", null, false)).isEmpty();
  }

  @Test
  void scheduleDeletionSetsWindowFourteenDaysOut() {
    UUID id = insertUser("leaving@example.com");

    assertThat(repository.scheduleDeletion(id, 14)).isTrue();

    OffsetDateTime scheduled = repository.findById(id).orElseThrow().scheduledDeletionAt();
    assertThat(scheduled).isNotNull();
    // Bounded rather than exact: now() is the database clock, not the test's.
    assertThat(scheduled)
        .isAfter(OffsetDateTime.now().plusDays(13))
        .isBefore(OffsetDateTime.now().plusDays(15));
  }

  @Test
  void cancelDeletionClearsTheWindow() {
    UUID id = insertUser("returning@example.com");
    repository.scheduleDeletion(id, 14);

    assertThat(repository.cancelDeletion(id)).isTrue();
    assertThat(repository.findById(id).orElseThrow().scheduledDeletionAt()).isNull();
  }

  @Test
  void cancelDeletionIsANoOpWhenNothingScheduled() {
    UUID id = insertUser("staying@example.com");

    assertThat(repository.cancelDeletion(id)).isTrue();
    assertThat(repository.findById(id).orElseThrow().scheduledDeletionAt()).isNull();
  }

  @Test
  void deletionSchedulingIgnoresSoftDeletedAndUnknownUsers() {
    UUID gone = insertUser("gone@example.com");
    jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?", gone);

    assertThat(repository.scheduleDeletion(gone, 14)).isFalse();
    assertThat(repository.cancelDeletion(gone)).isFalse();
    assertThat(repository.scheduleDeletion(UUID.randomUUID(), 14)).isFalse();
    assertThat(repository.cancelDeletion(UUID.randomUUID())).isFalse();
  }

  private UUID insertUser(String email) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, email, is_onboarded, created_at) VALUES (?, ?, false, now())",
        id,
        email);
    return id;
  }
}
