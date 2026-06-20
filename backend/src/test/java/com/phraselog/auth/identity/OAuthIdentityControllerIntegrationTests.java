package com.phraselog.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.phraselog.auth.service.InternalAuthTestTokens;
import com.phraselog.auth.web.InternalAuthFilter;
import com.phraselog.common.web.ApiPaths;
import java.time.Instant;
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
      "phraselog.internal-auth.secrets=" + OAuthIdentityControllerIntegrationTests.INTERNAL_SECRET,
      "phraselog.internal-auth.skew-leeway-seconds=30"
    })
class OAuthIdentityControllerIntegrationTests {

  static final String INTERNAL_SECRET = "oauth-controller-internal-auth-secret-0123456789";
  private static final String PATH = ApiPaths.V1 + "/auth/oauth/identity";

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("phraselog_test")
          .withUsername("phraselog")
          .withPassword("phraselog");

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
  void missingInternalAuthTokenIsRejectedByTheExistingAuthFilter() {
    ResponseEntity<String> response =
        restTemplate.postForEntity(PATH, requestBody("google", "google-1"), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).contains("\"error_code\":\"internal_auth_invalid\"");
  }

  @Test
  void ordinaryAnonymousTokenCannotProvisionOAuthIdentity() {
    String token = internalToken("regular-anonymous-session");

    ResponseEntity<String> response = post(token, requestBody("google", "google-1"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).contains("\"error_code\":\"internal_auth_invalid\"");
    assertThat(count("users")).isZero();
  }

  @Test
  void provisioningTokenCreatesUserAndIdentity() {
    String token = internalToken(OAuthIdentityController.PROVISIONING_SESSION_TOKEN);

    ResponseEntity<OAuthIdentityResult> response =
        postForResult(token, requestBody("kakao", "kakao-1"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    OAuthIdentityResult body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.userId()).isNotNull();
    assertThat(body.email()).isEqualTo("new@example.com");
    assertThat(body.createdUser()).isTrue();
    assertThat(count("users")).isEqualTo(1);
    assertThat(count("user_auth_identities")).isEqualTo(1);
  }

  private ResponseEntity<String> post(String token, Map<String, String> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(InternalAuthFilter.HEADER, token);
    return restTemplate.exchange(
        PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }

  private ResponseEntity<OAuthIdentityResult> postForResult(
      String token, Map<String, String> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(InternalAuthFilter.HEADER, token);
    return restTemplate.exchange(
        PATH, HttpMethod.POST, new HttpEntity<>(body, headers), OAuthIdentityResult.class);
  }

  private Map<String, String> requestBody(String provider, String providerUserId) {
    return Map.of(
        "provider",
        provider,
        "provider_user_id",
        providerUserId,
        "provider_email",
        "new@example.com",
        "display_name",
        "New User");
  }

  private static String internalToken(String sessionToken) {
    Instant issuedAt = Instant.now().minusSeconds(5);
    return InternalAuthTestTokens.signedWithSession(
        INTERNAL_SECRET, sessionToken, issuedAt, issuedAt.plusSeconds(120));
  }

  private Integer count(String table) {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }
}
