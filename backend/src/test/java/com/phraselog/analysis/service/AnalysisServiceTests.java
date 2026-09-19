package com.phraselog.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.client.service.AnthropicService;
import com.phraselog.ai.logging.dto.AiFeature;
import com.phraselog.ai.prompt.dto.PromptDefinition;
import com.phraselog.ai.prompt.service.PromptLoader;
import com.phraselog.analysis.dto.AnalysisRequestRow;
import com.phraselog.analysis.dto.AnalysisResponse;
import com.phraselog.analysis.dto.CreateAnalysisRequest;
import com.phraselog.analysis.dto.NewAnalysis;
import com.phraselog.analysis.repository.AnalysisRepository;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.usage.repository.AnonymousAnalysisUsageRepository;
import com.phraselog.usage.service.AnonymousAnalysisUsageService;
import com.phraselog.usage.service.ClientIpResolver;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

class AnalysisServiceTests {

  private static final UUID LOG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String IP = "203.0.113.10";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private FakeAnalysisRepository repository;
  private AnthropicService anthropicService;
  private PromptLoader promptLoader;
  private FakeUsageRepository usageRepository;
  private AnalysisService service;

  @BeforeEach
  void setUp() {
    repository = new FakeAnalysisRepository();
    repository.stubLogId = LOG_ID;
    anthropicService = mock(AnthropicService.class);
    promptLoader = mock(PromptLoader.class);
    when(promptLoader.load("s07", 3)).thenReturn(promptDefinition());
    when(anthropicService.callClaude(eq(AiFeature.S07_ANALYSIS), any(), anyString(), any(), any()))
        .thenReturn(threeExpressions());

    usageRepository = new FakeUsageRepository();
    AnonymousAnalysisUsageService usageService =
        new AnonymousAnalysisUsageService(usageRepository, new ClientIpResolver());
    service =
        new AnalysisService(
            repository,
            anthropicService,
            promptLoader,
            singletonProvider(usageService),
            new ClientIpResolver());
  }

  /** Minimal {@link ObjectProvider} that always yields the given instance. */
  private static <T> ObjectProvider<T> singletonProvider(T instance) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return instance;
      }

      @Override
      public T getObject(Object... args) {
        return instance;
      }

      @Override
      public T getIfAvailable() {
        return instance;
      }

      @Override
      public T getIfUnique() {
        return instance;
      }
    };
  }

  @Test
  void authenticatedRequestPersistsThreeVariantsAndLogId() {
    InternalAuthPrincipal user = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);

    AnalysisResponse response =
        service.create(user, body("친구가 약속을 또 취소했어요"), UUID.randomUUID().toString(), null);

    assertThat(response.variants()).hasSize(3);
    assertThat(response.promptVersion()).isEqualTo("s07-v1");
    assertThat(response.inputText()).isEqualTo("친구가 약속을 또 취소했어요");
    assertThat(response.variants().get(0).variantOrder()).isEqualTo(1);
    assertThat(response.variants().get(0).englishText()).isNotBlank();

    assertThat(repository.inserts).hasSize(1);
    NewAnalysis inserted = repository.inserts.get(0);
    assertThat(inserted.userId()).isEqualTo(UUID.fromString(user.userId()));
    assertThat(inserted.sessionToken()).isNull();
    assertThat(inserted.ipAddress()).isNull();
    assertThat(inserted.promptVersion()).isEqualTo("s07-v1");
    assertThat(inserted.aiRequestLogId()).isEqualTo(LOG_ID);
  }

  @Test
  void anonymousThirdDailyAttemptReturns429BeforeCallingTheLlm() {
    service.create(anonymous("s1"), body("상황1"), UUID.randomUUID().toString(), IP);
    service.create(anonymous("s2"), body("상황2"), UUID.randomUUID().toString(), IP);

    assertThatThrownBy(
            () -> service.create(anonymous("s3"), body("상황3"), UUID.randomUUID().toString(), IP))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
              assertThat(error.errorCode()).isEqualTo("rate_limit_exceeded");
            });

    // Only the first two anonymous calls reached the LLM; the throttled third did not.
    verify(anthropicService, times(2))
        .callClaude(eq(AiFeature.S07_ANALYSIS), any(), anyString(), any(), any());
    assertThat(repository.inserts).hasSize(2);
    assertThat(repository.inserts.get(0).ipAddress()).isEqualTo(IP);
  }

  @Test
  void authenticatedRequestsBypassAnonymousUsageLimit() {
    InternalAuthPrincipal user = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);

    for (int i = 0; i < 5; i++) {
      service.create(user, body("상황" + i), UUID.randomUUID().toString(), IP);
    }

    assertThat(repository.inserts).hasSize(5);
    assertThat(usageRepository.reserveCalls).isZero();
  }

  @Test
  void idempotentRetryReturnsExistingRowWithoutSecondLlmCall() {
    InternalAuthPrincipal user = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    String key = UUID.randomUUID().toString();

    AnalysisResponse first = service.create(user, body("상황"), key, null);
    AnalysisResponse retry = service.create(user, body("상황"), key, null);

    assertThat(retry.id()).isEqualTo(first.id());
    assertThat(repository.inserts).hasSize(1);
    verify(anthropicService, times(1))
        .callClaude(eq(AiFeature.S07_ANALYSIS), any(), anyString(), any(), any());
  }

  @Test
  void blankInputIsRejectedBeforeTheLlm() {
    InternalAuthPrincipal user = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);

    assertThatThrownBy(() -> service.create(user, body("   "), UUID.randomUUID().toString(), null))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(error.errorCode()).isEqualTo("validation_failed");
            });
    assertThat(repository.inserts).isEmpty();
  }

  @Test
  void inputOverFiveHundredCharsIsRejected() {
    InternalAuthPrincipal user = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    String tooLong = "a".repeat(501);

    assertThatThrownBy(
            () -> service.create(user, body(tooLong), UUID.randomUUID().toString(), null))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> assertThat(error.errorCode()).isEqualTo("validation_failed"));
  }

  @Test
  void missingIdempotencyKeyIsRejected() {
    InternalAuthPrincipal user = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);

    assertThatThrownBy(() -> service.create(user, body("상황"), null, null))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> assertThat(error.errorCode()).isEqualTo("validation_failed"));
    assertThat(repository.inserts).isEmpty();
  }

  @Test
  void providerFailureInsertsNoRowAndReleasesAnonymousSlot() {
    when(anthropicService.callClaude(eq(AiFeature.S07_ANALYSIS), any(), anyString(), any(), any()))
        .thenThrow(
            new ApiErrorException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "schema_validation_failed",
                "bad",
                "schema failed",
                true));

    assertThatThrownBy(
            () -> service.create(anonymous("s1"), body("상황"), UUID.randomUUID().toString(), IP))
        .isInstanceOf(ApiErrorException.class);

    assertThat(repository.inserts).isEmpty();
    assertThat(usageRepository.currentCount(IP, LocalDate.now())).isZero();
  }

  @Test
  void getReturnsAnalysisForItsOwner() {
    InternalAuthPrincipal user = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    AnalysisResponse created = service.create(user, body("상황"), UUID.randomUUID().toString(), null);

    AnalysisResponse fetched = service.get(user, created.id().toString());

    assertThat(fetched.id()).isEqualTo(created.id());
    assertThat(fetched.variants()).hasSize(3);
  }

  @Test
  void getByNonOwnerReturns404() {
    InternalAuthPrincipal owner = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    InternalAuthPrincipal other = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);
    AnalysisResponse created =
        service.create(owner, body("상황"), UUID.randomUUID().toString(), null);

    assertThatThrownBy(() -> service.get(other, created.id().toString()))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(error.errorCode()).isEqualTo("not_found");
            });
  }

  @Test
  void getWithMalformedIdReturns404() {
    InternalAuthPrincipal user = new InternalAuthPrincipal(UUID.randomUUID().toString(), null);

    assertThatThrownBy(() -> service.get(user, "not-a-uuid"))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  private static InternalAuthPrincipal anonymous(String token) {
    return new InternalAuthPrincipal(null, token);
  }

  private static CreateAnalysisRequest body(String inputText) {
    return new CreateAnalysisRequest(inputText, null, "expressions");
  }

  @Test
  void clarificationIsReadableWithoutVariantsAndRetryDoesNotSpendAgain() throws Exception {
    when(anthropicService.callClaude(eq(AiFeature.S07_ANALYSIS), any(), anyString(), any(), any()))
        .thenReturn(
            MAPPER.readTree("{\"result_type\":\"needs_context\",\"question\":\"어떤 말을 하고 싶으세요?\"}"));
    String key = UUID.randomUUID().toString();
    var first = service.create(anonymous("clarification"), body("집주인"), key, IP);
    var retry = service.create(anonymous("clarification"), body("집주인"), key, IP);
    assertThat(first.resultType()).isEqualTo("needs_context");
    assertThat(first.variants()).isEmpty();
    assertThat(service.get(anonymous("clarification"), first.id().toString()).question())
        .isNotBlank();
    assertThat(retry.id()).isEqualTo(first.id());
    assertThat(usageRepository.currentCount(IP, LocalDate.now())).isEqualTo(1);
    verify(anthropicService, times(1)).callClaude(any(), any(), anyString(), any(), any());
  }

  @Test
  void wordResultIsSeparateAndHasNoVariants() throws Exception {
    when(anthropicService.callClaude(eq(AiFeature.S07_ANALYSIS), any(), anyString(), any(), any()))
        .thenReturn(
            MAPPER.readTree(
                "{\"result_type\":\"word\",\"word\":{\"english\":\"landlord\",\"meaning_ko\":\"집주인\"}}"));
    var result =
        service.create(
            anonymous("word"),
            new CreateAnalysisRequest("집주인", null, "word"),
            UUID.randomUUID().toString(),
            IP);
    assertThat(result.resultType()).isEqualTo("word");
    assertThat(result.word().path("english").asText()).isEqualTo("landlord");
    assertThat(result.variants()).isEmpty();
  }

  @Test
  void invalidAndUnselectedWordInputsDoNotCallAiOrConsumeQuota() {
    for (String text : new String[] {"ㅁㅈㅇㅁㅇㄴㅁㅇ추더러", "집주인"}) {
      assertThatThrownBy(
              () ->
                  service.create(
                      anonymous("bad"),
                      new CreateAnalysisRequest(text, null),
                      UUID.randomUUID().toString(),
                      IP))
          .isInstanceOf(ApiErrorException.class);
    }
    org.mockito.Mockito.verifyNoInteractions(anthropicService);
    assertThat(usageRepository.currentCount(IP, LocalDate.now())).isZero();
  }

  @Test
  void changedInputCannotReuseACompletedKey() {
    String key = UUID.randomUUID().toString();
    service.create(anonymous("edit"), body("물 주세요"), key, IP);
    assertThatThrownBy(() -> service.create(anonymous("edit"), body("고마워"), key, IP))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            e -> assertThat(e.errorCode()).isEqualTo("idempotency_conflict"));
    verify(anthropicService, times(1)).callClaude(any(), any(), anyString(), any(), any());
  }

  @Test
  void concurrentSameKeyUsesOneGeneration() throws Exception {
    String key = UUID.randomUUID().toString();
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(() -> service.create(anonymous("concurrent"), body("물 주세요"), key, IP));
      var second =
          executor.submit(() -> service.create(anonymous("concurrent"), body("물 주세요"), key, IP));
      assertThat(first.get().id()).isEqualTo(second.get().id());
    }
    verify(anthropicService, times(1)).callClaude(any(), any(), anyString(), any(), any());
    assertThat(usageRepository.currentCount(IP, LocalDate.now())).isEqualTo(1);
  }

  private static PromptDefinition promptDefinition() {
    return new PromptDefinition(
        "s07_analysis",
        "s07-v1",
        "claude-sonnet-4-6",
        "s07_analysis_v1",
        "2026-06-04",
        null,
        "system prompt body");
  }

  private static JsonNode threeExpressions() {
    try {
      return MAPPER.readTree(
          """
          {
            "expressions": [
              {"english":"A","tone_label":"정중한","ipa":"/a/","korean_pronunciation":"에이",
               "pronunciation_tip":"t1","cultural_tip":"c1"},
              {"english":"B","tone_label":"부드러운","ipa":"/b/","korean_pronunciation":"비",
               "pronunciation_tip":"t2","cultural_tip":"c2"},
              {"english":"C","tone_label":"단호한","ipa":"/c/","korean_pronunciation":"시",
               "pronunciation_tip":"t3","cultural_tip":"c3"}
            ]
          }
          """);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** In-memory {@link AnalysisRepository}; tracks inserts and supports owner/key lookups. */
  private static final class FakeAnalysisRepository implements AnalysisRepository {

    final java.util.List<NewAnalysis> inserts = new java.util.ArrayList<>();
    private final Map<UUID, AnalysisRequestRow> byId = new HashMap<>();
    private final Map<String, AnalysisRequestRow> byCallerKey = new HashMap<>();
    UUID stubLogId;

    @Override
    public AnalysisRequestRow insert(NewAnalysis analysis) {
      String callerKey =
          callerKey(analysis.userId(), analysis.sessionToken(), analysis.idempotencyKey());
      if (callerKey != null && byCallerKey.containsKey(callerKey)) {
        throw new DuplicateKeyException("duplicate idempotency key");
      }
      AnalysisRequestRow row =
          new AnalysisRequestRow(
              UUID.randomUUID(),
              analysis.userId(),
              analysis.sessionToken(),
              analysis.inputText(),
              analysis.outputJson(),
              analysis.promptVersion(),
              analysis.aiRequestLogId(),
              OffsetDateTime.parse("2026-06-19T12:00:00Z"));
      inserts.add(analysis);
      byId.put(row.id(), row);
      if (callerKey != null) {
        byCallerKey.put(callerKey, row);
      }
      return row;
    }

    @Override
    public Optional<AnalysisRequestRow> findByIdForOwner(UUID id, InternalAuthPrincipal principal) {
      AnalysisRequestRow row = byId.get(id);
      if (row == null) {
        return Optional.empty();
      }
      boolean owns =
          principal.isAuthenticatedUser()
              ? UUID.fromString(principal.userId()).equals(row.userId())
              : row.userId() == null && principal.sessionToken().equals(row.sessionToken());
      return owns ? Optional.of(row) : Optional.empty();
    }

    @Override
    public Optional<AnalysisRequestRow> claimAnonymousAnalysis(
        UUID id, String sessionToken, UUID userId) {
      AnalysisRequestRow row = byId.get(id);
      if (row == null || row.userId() != null || !sessionToken.equals(row.sessionToken())) {
        return Optional.empty();
      }
      AnalysisRequestRow claimed =
          new AnalysisRequestRow(
              row.id(),
              userId,
              null,
              row.inputText(),
              row.outputJson(),
              row.promptVersion(),
              row.aiRequestLogId(),
              row.createdAt());
      byId.put(id, claimed);
      return Optional.of(claimed);
    }

    @Override
    public Optional<AnalysisRequestRow> findByCallerAndKey(
        InternalAuthPrincipal principal, UUID idempotencyKey) {
      UUID userId = principal.isAuthenticatedUser() ? UUID.fromString(principal.userId()) : null;
      String callerKey = callerKey(userId, principal.sessionToken(), idempotencyKey);
      return Optional.ofNullable(byCallerKey.get(callerKey));
    }

    @Override
    public Optional<UUID> findLogIdByCorrelation(UUID correlationId) {
      return Optional.ofNullable(stubLogId);
    }

    private static String callerKey(UUID userId, String sessionToken, UUID key) {
      if (key == null) {
        return null;
      }
      return (userId != null ? "u:" + userId : "s:" + sessionToken) + "|" + key;
    }
  }

  /** Minimal in-memory anonymous usage quota for the rate-limit interaction. */
  private static final class FakeUsageRepository implements AnonymousAnalysisUsageRepository {

    private final Map<String, Integer> counts = new HashMap<>();
    int reserveCalls;

    @Override
    public boolean tryReserve(String ipAddress, LocalDate usageDate, int limit) {
      reserveCalls++;
      String key = ipAddress + "|" + usageDate;
      int current = counts.getOrDefault(key, 0);
      if (current >= limit) {
        return false;
      }
      counts.put(key, current + 1);
      return true;
    }

    @Override
    public void release(String ipAddress, LocalDate usageDate) {
      String key = ipAddress + "|" + usageDate;
      counts.put(key, Math.max(0, counts.getOrDefault(key, 0) - 1));
    }

    int currentCount(String ipAddress, LocalDate usageDate) {
      return counts.getOrDefault(ipAddress + "|" + usageDate, 0);
    }
  }
}
