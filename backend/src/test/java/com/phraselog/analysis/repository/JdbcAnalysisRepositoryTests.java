package com.phraselog.analysis.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.analysis.dto.AnalysisRequestRow;
import com.phraselog.analysis.dto.NewAnalysis;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcAnalysisRepositoryTests {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("phraselog_test")
          .withUsername("phraselog")
          .withPassword("phraselog");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static DataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private JdbcAnalysisRepository repository;

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
    jdbcTemplate.update("DELETE FROM analysis_requests");
    jdbcTemplate.update("DELETE FROM ai_request_logs");
    jdbcTemplate.update("DELETE FROM users");
    repository = new JdbcAnalysisRepository(jdbcTemplate, MAPPER);
  }

  @Test
  void insertsAndReadsBackAuthenticatedRowWithParsedJsonAndLogId() {
    UUID userId = insertUser("owner@example.com");
    UUID logId = insertAiRequestLog();
    UUID key = UUID.randomUUID();

    AnalysisRequestRow inserted =
        repository.insert(
            new NewAnalysis(userId, null, null, "상황", output(), "s07-v1", logId, key));

    Optional<AnalysisRequestRow> found = repository.findByIdForOwner(inserted.id(), user(userId));
    assertThat(found).isPresent();
    AnalysisRequestRow row = found.get();
    assertThat(row.userId()).isEqualTo(userId);
    assertThat(row.sessionToken()).isNull();
    assertThat(row.promptVersion()).isEqualTo("s07-v1");
    assertThat(row.aiRequestLogId()).isEqualTo(logId);
    assertThat(row.outputJson().get("expressions")).hasSize(3);
    assertThat(row.createdAt()).isNotNull();
  }

  @Test
  void findByIdForOwnerReturnsEmptyForADifferentUser() {
    UUID owner = insertUser("owner@example.com");
    UUID other = insertUser("other@example.com");
    AnalysisRequestRow inserted =
        repository.insert(
            new NewAnalysis(owner, null, null, "상황", output(), "s07-v1", null, UUID.randomUUID()));

    assertThat(repository.findByIdForOwner(inserted.id(), user(other))).isEmpty();
  }

  @Test
  void anonymousRowIsScopedBySessionTokenAndStoresInetIp() {
    AnalysisRequestRow inserted =
        repository.insert(
            new NewAnalysis(
                null,
                "session-abc",
                "203.0.113.10",
                "상황",
                output(),
                "s07-v1",
                null,
                UUID.randomUUID()));

    assertThat(repository.findByIdForOwner(inserted.id(), session("session-abc"))).isPresent();
    assertThat(repository.findByIdForOwner(inserted.id(), session("other-session"))).isEmpty();

    String storedIp =
        jdbcTemplate.queryForObject(
            "SELECT host(ip_address) FROM analysis_requests WHERE id = ?",
            String.class,
            inserted.id());
    assertThat(storedIp).isEqualTo("203.0.113.10");
  }

  @Test
  void findByCallerAndKeyReturnsTheIdempotentRow() {
    UUID userId = insertUser("owner@example.com");
    UUID key = UUID.randomUUID();
    AnalysisRequestRow inserted =
        repository.insert(new NewAnalysis(userId, null, null, "상황", output(), "s07-v1", null, key));

    assertThat(repository.findByCallerAndKey(user(userId), key))
        .get()
        .extracting(AnalysisRequestRow::id)
        .isEqualTo(inserted.id());
    assertThat(repository.findByCallerAndKey(user(userId), UUID.randomUUID())).isEmpty();
  }

  @Test
  void duplicateIdempotencyKeyForSameCallerIsRejected() {
    UUID userId = insertUser("owner@example.com");
    UUID key = UUID.randomUUID();
    repository.insert(new NewAnalysis(userId, null, null, "상황", output(), "s07-v1", null, key));

    assertThatThrownBy(
            () ->
                repository.insert(
                    new NewAnalysis(userId, null, null, "다시", output(), "s07-v1", null, key)))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void sameKeyForDifferentCallersIsAllowed() {
    UUID userA = insertUser("a@example.com");
    UUID userB = insertUser("b@example.com");
    UUID key = UUID.randomUUID();

    repository.insert(new NewAnalysis(userA, null, null, "상황", output(), "s07-v1", null, key));
    AnalysisRequestRow second =
        repository.insert(new NewAnalysis(userB, null, null, "상황", output(), "s07-v1", null, key));

    assertThat(repository.findByIdForOwner(second.id(), user(userB))).isPresent();
  }

  @Test
  void claimAnonymousAnalysisAttributesMatchingSessionTokenToUserAndClearsToken() {
    UUID userId = insertUser("claimer@example.com");
    AnalysisRequestRow anonymous =
        repository.insert(
            new NewAnalysis(
                null, "session-claim", "203.0.113.5", "상황", output(), "s07-v1", null,
                UUID.randomUUID()));

    Optional<AnalysisRequestRow> claimed =
        repository.claimAnonymousAnalysis(anonymous.id(), "session-claim", userId);

    assertThat(claimed).isPresent();
    assertThat(claimed.get().userId()).isEqualTo(userId);
    assertThat(claimed.get().sessionToken()).isNull();
    // 토큰이 비워졌으니 이제 인증 사용자 소유로만 읽힌다.
    assertThat(repository.findByIdForOwner(anonymous.id(), user(userId))).isPresent();
    assertThat(repository.findByIdForOwner(anonymous.id(), session("session-claim"))).isEmpty();
  }

  @Test
  void claimAnonymousAnalysisWithMismatchedTokenClaimsNothing() {
    UUID userId = insertUser("claimer@example.com");
    AnalysisRequestRow anonymous =
        repository.insert(
            new NewAnalysis(
                null, "session-real", null, "상황", output(), "s07-v1", null, UUID.randomUUID()));

    assertThat(repository.claimAnonymousAnalysis(anonymous.id(), "session-wrong", userId)).isEmpty();
    // 행은 여전히 익명이며 원래 토큰으로만 접근된다 — 절도 불가.
    assertThat(repository.findByIdForOwner(anonymous.id(), session("session-real"))).isPresent();
    assertThat(repository.findByIdForOwner(anonymous.id(), user(userId))).isEmpty();
  }

  @Test
  void claimAnonymousAnalysisCannotStealAnAlreadyOwnedRow() {
    UUID owner = insertUser("owner@example.com");
    UUID attacker = insertUser("attacker@example.com");
    // 이미 소유된 행(insert에 user_id 지정) — session_token은 NULL이다.
    AnalysisRequestRow owned =
        repository.insert(
            new NewAnalysis(owner, null, null, "상황", output(), "s07-v1", null, UUID.randomUUID()));

    // 공격자가 어떤 토큰을 들고 와도 user_id IS NULL 조건에서 0행 → 빈 결과.
    assertThat(repository.claimAnonymousAnalysis(owned.id(), "anything", attacker)).isEmpty();
    assertThat(repository.findByIdForOwner(owned.id(), user(owner))).isPresent();
    assertThat(repository.findByIdForOwner(owned.id(), user(attacker))).isEmpty();
  }

  @Test
  void findLogIdByCorrelationResolvesTheLatestLogRow() {
    UUID correlationId = UUID.randomUUID();
    UUID logId = insertAiRequestLogWithCorrelation(correlationId);

    assertThat(repository.findLogIdByCorrelation(correlationId)).contains(logId);
    assertThat(repository.findLogIdByCorrelation(UUID.randomUUID())).isEmpty();
  }

  private JsonNode output() {
    try {
      return MAPPER.readTree(
          """
          {"expressions":[
            {"english":"A","tone_label":"정중한","ipa":"/a/","korean_pronunciation":"에이","pronunciation_tip":"t","cultural_tip":"c"},
            {"english":"B","tone_label":"부드러운","ipa":"/b/","korean_pronunciation":"비","pronunciation_tip":"t","cultural_tip":"c"},
            {"english":"C","tone_label":"단호한","ipa":"/c/","korean_pronunciation":"시","pronunciation_tip":"t","cultural_tip":"c"}
          ]}
          """);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID insertUser(String email) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO users (id, email) VALUES (?, ?)", id, email);
    return id;
  }

  private UUID insertAiRequestLog() {
    return insertAiRequestLogWithCorrelation(UUID.randomUUID());
  }

  private UUID insertAiRequestLogWithCorrelation(UUID correlationId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO ai_request_logs"
            + " (id, feature_name, model_name, latency_ms, status, request_correlation_id)"
            + " VALUES (?, 's07_analysis', 'claude-sonnet-4-6', 1200, 'success', ?)",
        id,
        correlationId);
    return id;
  }

  private static InternalAuthPrincipal user(UUID userId) {
    return new InternalAuthPrincipal(userId.toString(), null);
  }

  private static InternalAuthPrincipal session(String token) {
    return new InternalAuthPrincipal(null, token);
  }
}
