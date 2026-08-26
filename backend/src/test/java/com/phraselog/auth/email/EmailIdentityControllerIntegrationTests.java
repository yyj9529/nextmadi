package com.phraselog.auth.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.phraselog.auth.service.InternalAuthTestTokens;
import com.phraselog.auth.web.InternalAuthFilter;
import com.phraselog.common.web.ApiPaths;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "phraselog.internal-auth.secrets=" + EmailIdentityControllerIntegrationTests.INTERNAL_SECRET,
      "phraselog.internal-auth.skew-leeway-seconds=30"
    })
class EmailIdentityControllerIntegrationTests {

  static final String INTERNAL_SECRET = "email-identity-internal-auth-secret-0123456789";
  private static final String RESOLVE_PATH = ApiPaths.V1 + "/auth/email/identity";
  private static final String LOOKUP_PATH = RESOLVE_PATH + "/lookup";
  private static final String EMAIL = "mia@example.com";

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("phraselog_test")
          .withUsername("phraselog")
          .withPassword("phraselog")
          // The suite creates and tears down a container per test class; on a slow Docker host the
          // default 60s readiness wait is not always enough and the class fails to initialise.
          // A longer ceiling costs nothing when startup is fast.
          .withStartupTimeout(Duration.ofMinutes(3));

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .cleanDisabled(false)
        .load()
        .clean();
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  @Test
  void lookupNeverCreatesAUser() {
    // Auth.js calls getUserByEmail while merely sending the link. If that created a user, typing a
    // stranger's address into the S03 form would be enough to open an account in their name.
    ResponseEntity<String> response = post(LOOKUP_PATH, provisioningToken(), body(EMAIL));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).contains("email_identity_not_found");
    assertThat(count("users")).isZero();
    assertThat(count("user_auth_identities")).isZero();
  }

  @Test
  void resolveCreatesAUserAndAnEmailIdentity() {
    ResponseEntity<EmailIdentityResult> response =
        postForResult(RESOLVE_PATH, provisioningToken(), body(EMAIL));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    EmailIdentityResult result = response.getBody();
    assertThat(result).isNotNull();
    assertThat(result.userId()).isNotNull();
    assertThat(result.email()).isEqualTo(EMAIL);
    assertThat(result.createdUser()).isTrue();
    assertThat(result.linkedToExistingUser()).isFalse();
    assertThat(result.isOnboarded()).isFalse();
    assertThat(count("users")).isEqualTo(1);
    assertThat(providersFor(result.userId())).containsExactly("email");
  }

  @Test
  void resolvingTheSameAddressTwiceReturnsTheSameUser() {
    UUID first = postForResult(RESOLVE_PATH, provisioningToken(), body(EMAIL)).getBody().userId();
    EmailIdentityResult second =
        postForResult(RESOLVE_PATH, provisioningToken(), body(EMAIL)).getBody();

    assertThat(second.userId()).isEqualTo(first);
    assertThat(second.createdUser()).isFalse();
    assertThat(second.linkedToExistingUser()).isFalse();
    assertThat(count("users")).isEqualTo(1);
    assertThat(count("user_auth_identities")).isEqualTo(1);
  }

  @Test
  void anAddressAlreadyRegisteredThroughGoogleLinksToThatSameUser() {
    // S03: OAuth refuses this case because a provider email is only a claim. A clicked magic link
    // proves the mailbox, so here it must attach rather than fork a second account.
    UUID existing = insertUserWithIdentity(EMAIL, "google", "google-abc");

    EmailIdentityResult result =
        postForResult(RESOLVE_PATH, provisioningToken(), body(EMAIL)).getBody();

    assertThat(result).isNotNull();
    assertThat(result.userId()).isEqualTo(existing);
    assertThat(result.createdUser()).isFalse();
    assertThat(result.linkedToExistingUser()).isTrue();
    assertThat(count("users")).isEqualTo(1);
    assertThat(providersFor(existing)).containsExactly("email", "google");
  }

  @Test
  void aLinkedAddressResolvesToTheSameUserOnTheNextSignIn() {
    UUID existing = insertUserWithIdentity(EMAIL, "google", "google-abc");
    postForResult(RESOLVE_PATH, provisioningToken(), body(EMAIL));

    EmailIdentityResult again =
        postForResult(RESOLVE_PATH, provisioningToken(), body(EMAIL)).getBody();

    assertThat(again.userId()).isEqualTo(existing);
    assertThat(again.linkedToExistingUser()).isFalse();
    assertThat(count("user_auth_identities")).isEqualTo(2);
  }

  @Test
  void lookupFindsAUserRegisteredThroughAnotherProvider() {
    UUID existing = insertUserWithIdentity(EMAIL, "kakao", "kakao-abc");

    EmailIdentityResult result =
        postForResult(LOOKUP_PATH, provisioningToken(), body(EMAIL)).getBody();

    assertThat(result).isNotNull();
    assertThat(result.userId()).isEqualTo(existing);
    assertThat(count("user_auth_identities")).isEqualTo(1);
  }

  @Test
  void addressCasingAndSurroundingSpaceResolveToOneIdentity() {
    UUID first =
        postForResult(RESOLVE_PATH, provisioningToken(), body(" MIA@Example.com "))
            .getBody()
            .userId();
    EmailIdentityResult second =
        postForResult(RESOLVE_PATH, provisioningToken(), body("mia@EXAMPLE.COM")).getBody();

    assertThat(second.userId()).isEqualTo(first);
    assertThat(count("users")).isEqualTo(1);
    assertThat(emails()).containsExactly(EMAIL);
  }

  @Test
  void signingInCancelsAScheduledDeletion() {
    UUID existing = insertUserWithIdentity(EMAIL, "email", EMAIL);
    jdbcTemplate.update(
        "UPDATE users SET scheduled_deletion_at = ? WHERE id = ?",
        OffsetDateTime.now(ZoneOffset.UTC).plusDays(7),
        existing);

    EmailIdentityResult result =
        postForResult(RESOLVE_PATH, provisioningToken(), body(EMAIL)).getBody();

    assertThat(result.canceledScheduledDeletion()).isTrue();
    assertThat(scheduledDeletion(existing)).isNull();
  }

  @Test
  void missingInternalAuthTokenIsRejected() {
    ResponseEntity<String> response =
        restTemplate.postForEntity(RESOLVE_PATH, body(EMAIL), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(count("users")).isZero();
  }

  @Test
  void oauthProvisioningTokenCannotResolveEmailIdentities() {
    ResponseEntity<String> response =
        post(RESOLVE_PATH, internalToken("__oauth_provisioning__"), body(EMAIL));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(count("users")).isZero();
  }

  @Test
  void aBlankAddressIsRejectedAsValidationFailure() {
    ResponseEntity<String> response = post(RESOLVE_PATH, provisioningToken(), body("   "));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("validation_failed");
    assertThat(count("users")).isZero();
  }

  private static Map<String, Object> body(String email) {
    return Map.of("email", email);
  }

  private ResponseEntity<String> post(String path, String internalToken, Map<String, Object> body) {
    return restTemplate.exchange(
        path, HttpMethod.POST, new HttpEntity<>(body, headers(internalToken)), String.class);
  }

  private ResponseEntity<EmailIdentityResult> postForResult(
      String path, String internalToken, Map<String, Object> body) {
    return restTemplate.exchange(
        path,
        HttpMethod.POST,
        new HttpEntity<>(body, headers(internalToken)),
        EmailIdentityResult.class);
  }

  private static HttpHeaders headers(String internalToken) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(InternalAuthFilter.HEADER, internalToken);
    return headers;
  }

  private static String provisioningToken() {
    return internalToken(EmailProvisioning.SESSION_TOKEN);
  }

  private static String internalToken(String sessionToken) {
    Instant issuedAt = Instant.now().minusSeconds(5);
    return InternalAuthTestTokens.signedWithSession(
        INTERNAL_SECRET, sessionToken, issuedAt, issuedAt.plusSeconds(120));
  }

  private UUID insertUserWithIdentity(String email, String provider, String providerUserId) {
    UUID userId = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO users (id, email) VALUES (?, ?)", userId, email);
    jdbcTemplate.update(
        "INSERT INTO user_auth_identities (user_id, provider, provider_user_id, provider_email)"
            + " VALUES (?, ?, ?, ?)",
        userId,
        provider,
        providerUserId,
        email);
    return userId;
  }

  private Integer count(String table) {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private List<String> providersFor(UUID userId) {
    return jdbcTemplate.queryForList(
        "SELECT provider FROM user_auth_identities WHERE user_id = ? ORDER BY provider",
        String.class,
        userId);
  }

  private List<String> emails() {
    return jdbcTemplate.queryForList("SELECT email FROM users ORDER BY email", String.class);
  }

  private OffsetDateTime scheduledDeletion(UUID userId) {
    return jdbcTemplate.queryForObject(
        "SELECT scheduled_deletion_at FROM users WHERE id = ?", OffsetDateTime.class, userId);
  }
}
