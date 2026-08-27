package com.phraselog.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.phraselog.common.web.ApiErrorException;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class OAuthIdentityServiceTests {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("phraselog_test")
          .withUsername("phraselog")
          .withPassword("phraselog");

  private static DataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private OAuthIdentityService service;

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
    jdbcTemplate.update("DELETE FROM user_auth_identities");
    jdbcTemplate.update("DELETE FROM users");
    service = new OAuthIdentityService(new JdbcOAuthIdentityRepository(jdbcTemplate));
  }

  @Test
  void existingProviderIdentityReturnsActiveUserAndCancelsScheduledDeletion() {
    UUID userId =
        insertUser("owner@example.com", "Owner", false, OffsetDateTime.now().plusDays(13));
    insertIdentity(userId, "google", "google-123", "owner@example.com");

    OAuthIdentityResult result =
        service.resolve(
            new OAuthIdentityRequest("google", "google-123", "owner@example.com", "Owner G", true));

    assertThat(result.userId()).isEqualTo(userId);
    assertThat(result.email()).isEqualTo("owner@example.com");
    assertThat(result.isOnboarded()).isFalse();
    assertThat(result.createdUser()).isFalse();
    assertThat(result.canceledScheduledDeletion()).isTrue();
    assertThat(scheduledDeletionAt(userId)).isNull();
  }

  @Test
  void newProviderIdentityCreatesUserAndIdentityRows() {
    OAuthIdentityResult result =
        service.resolve(
            new OAuthIdentityRequest("kakao", "kakao-123", "new@example.com", "New", true));

    assertThat(result.userId()).isNotNull();
    assertThat(result.email()).isEqualTo("new@example.com");
    assertThat(result.displayName()).isEqualTo("New");
    assertThat(result.isOnboarded()).isFalse();
    assertThat(result.createdUser()).isTrue();
    assertThat(result.canceledScheduledDeletion()).isFalse();

    assertThat(userCount(result.userId(), "new@example.com")).isEqualTo(1);
    assertThat(identityCount(result.userId(), "kakao", "kakao-123")).isEqualTo(1);
  }

  @Test
  void sameEmailDifferentProviderDoesNotAutoLinkWhileSignedOut() {
    UUID existingUser = insertUser("same@example.com", "Existing", false, null);

    assertThatThrownBy(
            () ->
                service.resolve(
                    new OAuthIdentityRequest(
                        "google", "google-999", "same@example.com", "Same", true)))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(error.errorCode()).isEqualTo("account_link_required");
            });

    assertThat(identityCount(existingUser, "google", "google-999")).isZero();
  }

  @Test
  void unverifiedProviderEmailIsRefusedBeforeAnyRowIsWritten() {
    // users.email is the key the S03 magic link uses to find an existing account, so an address
    // the provider did not verify must never reach it. Otherwise an account registered while
    // claiming a stranger's address swallows that stranger when they sign in by magic link.
    assertThatThrownBy(
            () ->
                service.resolve(
                    new OAuthIdentityRequest(
                        "kakao", "kakao-evil", "victim@example.com", "Not Me", false)))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(error.errorCode()).isEqualTo("email_unverified");
            });

    assertThat(userCountByEmail("victim@example.com")).isZero();
  }

  @Test
  void aProviderThatSaysNothingAboutVerificationIsStillAllowed() {
    // Refusing silence would block every provider that omits the claim, and would break sign-in
    // during a deploy where the BFF is older than this service.
    OAuthIdentityResult result =
        service.resolve(
            new OAuthIdentityRequest("google", "google-quiet", "quiet@example.com", "Quiet", null));

    assertThat(result.createdUser()).isTrue();
    assertThat(result.email()).isEqualTo("quiet@example.com");
  }

  @Test
  void missingProviderEmailIsRejectedBeforeCreatingRows() {
    assertThatThrownBy(
            () ->
                service.resolve(
                    new OAuthIdentityRequest("google", "google-123", " ", "No Mail", true)))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(error.errorCode()).isEqualTo("validation_failed");
            });

    Integer users = jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class);
    assertThat(users).isZero();
  }

  @Test
  void missingRequestBodyIsRejectedBeforeCreatingRows() {
    assertThatThrownBy(() -> service.resolve(null))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(error.errorCode()).isEqualTo("validation_failed");
            });

    Integer users = jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class);
    assertThat(users).isZero();
  }

  private UUID insertUser(
      String email, String displayName, boolean isOnboarded, OffsetDateTime scheduledDeletionAt) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, email, display_name, is_onboarded, scheduled_deletion_at)"
            + " VALUES (?, ?, ?, ?, ?)",
        id,
        email,
        displayName,
        isOnboarded,
        scheduledDeletionAt);
    return id;
  }

  private void insertIdentity(UUID userId, String provider, String providerUserId, String email) {
    jdbcTemplate.update(
        "INSERT INTO user_auth_identities"
            + " (user_id, provider, provider_user_id, provider_email)"
            + " VALUES (?, ?, ?, ?)",
        userId,
        provider,
        providerUserId,
        email);
  }

  private OffsetDateTime scheduledDeletionAt(UUID userId) {
    return jdbcTemplate.queryForObject(
        "SELECT scheduled_deletion_at FROM users WHERE id = ?", OffsetDateTime.class, userId);
  }

  private Integer userCount(UUID userId, String email) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM users WHERE id = ? AND email = ?", Integer.class, userId, email);
  }

  private Integer userCountByEmail(String email) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
  }

  private Integer identityCount(UUID userId, String provider, String providerUserId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM user_auth_identities"
            + " WHERE user_id = ? AND provider = ? AND provider_user_id = ?",
        Integer.class,
        userId,
        provider,
        providerUserId);
  }
}
