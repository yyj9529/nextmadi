package com.phraselog.auth.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.phraselog.auth.service.InternalAuthTestTokens;
import com.phraselog.auth.web.InternalAuthFilter;
import com.phraselog.common.web.ApiPaths;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
      "phraselog.internal-auth.secrets="
          + VerificationTokenControllerIntegrationTests.INTERNAL_SECRET,
      "phraselog.internal-auth.skew-leeway-seconds=30"
    })
class VerificationTokenControllerIntegrationTests {

  static final String INTERNAL_SECRET = "verification-token-internal-auth-secret-0123456789";
  private static final String CREATE_PATH = ApiPaths.V1 + "/auth/email/verification-tokens";
  private static final String CONSUME_PATH = CREATE_PATH + "/consume";
  private static final String IDENTIFIER = "mia@example.com";

  // Stands in for Auth.js's sha256(rawToken + AUTH_SECRET); the raw token never reaches us.
  private static final String TOKEN_HASH =
      "3d2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f";

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
  void missingInternalAuthTokenIsRejected() {
    ResponseEntity<String> response =
        restTemplate.postForEntity(CREATE_PATH, createBody(inOneDay()), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).contains("internal_auth_invalid");
    assertThat(tokenCount()).isZero();
  }

  @Test
  void ordinaryAnonymousTokenCannotStoreVerificationTokens() {
    ResponseEntity<String> response =
        post(CREATE_PATH, internalToken("regular-anonymous-session"), createBody(inOneDay()));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).contains("internal_auth_invalid");
    assertThat(tokenCount()).isZero();
  }

  @Test
  void oauthProvisioningTokenCannotStoreVerificationTokens() {
    // Separate capabilities: the OAuth provisioning token must not reach the email store.
    ResponseEntity<String> response =
        post(CREATE_PATH, internalToken("__oauth_provisioning__"), createBody(inOneDay()));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(tokenCount()).isZero();
  }

  @Test
  void createThenConsumeReturnsTheStoredRow() {
    OffsetDateTime expires = inOneDay();

    ResponseEntity<VerificationTokenResult> created =
        postForResult(CREATE_PATH, provisioningToken(), createBody(expires));

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(tokenCount()).isEqualTo(1);

    ResponseEntity<VerificationTokenResult> consumed =
        postForResult(CONSUME_PATH, provisioningToken(), consumeBody(TOKEN_HASH));

    assertThat(consumed.getStatusCode()).isEqualTo(HttpStatus.OK);
    VerificationTokenResult body = consumed.getBody();
    assertThat(body).isNotNull();
    assertThat(body.identifier()).isEqualTo(IDENTIFIER);
    assertThat(body.token()).isEqualTo(TOKEN_HASH);
    // Postgres timestamptz keeps microseconds, so the round-trip loses sub-microsecond precision.
    assertThat(body.expires().toInstant())
        .isCloseTo(expires.toInstant(), within(1, ChronoUnit.SECONDS));
    assertThat(tokenCount()).isZero();
  }

  @Test
  void consumingTheSameTokenTwiceFailsTheSecondTime() {
    postForResult(CREATE_PATH, provisioningToken(), createBody(inOneDay()));
    postForResult(CONSUME_PATH, provisioningToken(), consumeBody(TOKEN_HASH));

    ResponseEntity<String> replay =
        post(CONSUME_PATH, provisioningToken(), consumeBody(TOKEN_HASH));

    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(replay.getBody()).contains("verification_token_not_found");
  }

  @Test
  void aWrongTokenForAKnownIdentifierIsNotFound() {
    postForResult(CREATE_PATH, provisioningToken(), createBody(inOneDay()));

    ResponseEntity<String> response =
        post(
            CONSUME_PATH,
            provisioningToken(),
            consumeBody("0000000000000000000000000000000000000000000000000000000000000000"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(tokenCount()).isEqualTo(1);
  }

  @Test
  void anExpiredTokenIsReturnedOnceAndThenGone() {
    // Auth.js compares expires itself and raises Verification; the store must not pre-judge it.
    insertDirectly(IDENTIFIER, TOKEN_HASH, OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

    ResponseEntity<VerificationTokenResult> consumed =
        postForResult(CONSUME_PATH, provisioningToken(), consumeBody(TOKEN_HASH));

    assertThat(consumed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(consumed.getBody()).isNotNull();
    assertThat(consumed.getBody().expires()).isBefore(OffsetDateTime.now(ZoneOffset.UTC));
    assertThat(tokenCount()).isZero();
  }

  @Test
  void expiredRowsForOtherIdentifiersArePurgedOnCreate() {
    insertDirectly(
        "stale@example.com", "stale-token", OffsetDateTime.now(ZoneOffset.UTC).minusDays(2));
    assertThat(tokenCount()).isEqualTo(1);

    postForResult(CREATE_PATH, provisioningToken(), createBody(inOneDay()));

    assertThat(tokenCount()).isEqualTo(1);
    assertThat(identifiers()).containsExactly(IDENTIFIER);
  }

  @Test
  void oneIdentifierCannotHoldMoreThanTheOutstandingCap() {
    for (int i = 0; i < VerificationTokenService.MAX_OUTSTANDING_TOKENS; i++) {
      insertDirectly(IDENTIFIER, "outstanding-token-" + i, inOneDay());
    }

    ResponseEntity<String> response =
        post(CREATE_PATH, provisioningToken(), createBody(inOneDay()));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(response.getBody()).contains("rate_limit_exceeded");
    assertThat(tokenCount()).isEqualTo(VerificationTokenService.MAX_OUTSTANDING_TOKENS);
  }

  @Test
  void anIdentifierMayHoldSeveralOutstandingTokensBelowTheCap() {
    postForResult(CREATE_PATH, provisioningToken(), createBody(inOneDay()));
    postForResult(
        CREATE_PATH, provisioningToken(), createBody(IDENTIFIER, "second-token", inOneDay()));

    assertThat(tokenCount()).isEqualTo(2);
  }

  @Test
  void identifierIsNormalisedToLowercaseOnBothSides() {
    postForResult(
        CREATE_PATH, provisioningToken(), createBody("MIA@Example.com ", TOKEN_HASH, inOneDay()));

    assertThat(identifiers()).containsExactly(IDENTIFIER);

    ResponseEntity<VerificationTokenResult> consumed =
        postForResult(
            CONSUME_PATH, provisioningToken(), consumeBody("Mia@EXAMPLE.com", TOKEN_HASH));

    assertThat(consumed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(tokenCount()).isZero();
  }

  @Test
  void aRequestWithoutATokenIsRejectedAsValidationFailure() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("identifier", IDENTIFIER);
    body.put("expires", inOneDay().toString());

    ResponseEntity<String> response = post(CREATE_PATH, provisioningToken(), body);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("validation_failed");
  }

  @Test
  void aRequestWithoutAnExpiryIsRejectedAsValidationFailure() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("identifier", IDENTIFIER);
    body.put("token", TOKEN_HASH);

    ResponseEntity<String> response = post(CREATE_PATH, provisioningToken(), body);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("validation_failed");
  }

  private static OffsetDateTime inOneDay() {
    return OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
  }

  private static Map<String, Object> createBody(OffsetDateTime expires) {
    return createBody(IDENTIFIER, TOKEN_HASH, expires);
  }

  private static Map<String, Object> createBody(
      String identifier, String token, OffsetDateTime expires) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("identifier", identifier);
    body.put("token", token);
    body.put("expires", expires.toString());
    return body;
  }

  private static Map<String, Object> consumeBody(String token) {
    return consumeBody(IDENTIFIER, token);
  }

  private static Map<String, Object> consumeBody(String identifier, String token) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("identifier", identifier);
    body.put("token", token);
    return body;
  }

  private ResponseEntity<String> post(String path, String internalToken, Map<String, Object> body) {
    return restTemplate.exchange(
        path, HttpMethod.POST, new HttpEntity<>(body, headers(internalToken)), String.class);
  }

  private ResponseEntity<VerificationTokenResult> postForResult(
      String path, String internalToken, Map<String, Object> body) {
    return restTemplate.exchange(
        path,
        HttpMethod.POST,
        new HttpEntity<>(body, headers(internalToken)),
        VerificationTokenResult.class);
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

  private void insertDirectly(String identifier, String token, OffsetDateTime expires) {
    jdbcTemplate.update(
        "INSERT INTO verification_tokens (identifier, token, expires) VALUES (?, ?, ?)",
        identifier,
        token,
        expires);
  }

  private Integer tokenCount() {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM verification_tokens", Integer.class);
  }

  private List<String> identifiers() {
    return jdbcTemplate.queryForList(
        "SELECT identifier FROM verification_tokens ORDER BY identifier", String.class);
  }
}
