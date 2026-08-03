package com.phraselog.ai.client.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.client.config.FeatureRouting;
import com.phraselog.ai.client.dto.AnthropicMessage;
import com.phraselog.ai.client.dto.AnthropicResponse;
import com.phraselog.ai.logging.dto.AiErrorCode;
import com.phraselog.ai.logging.dto.AiFeature;
import com.phraselog.ai.logging.dto.AiRequestLogEntry;
import com.phraselog.ai.logging.dto.AiRequestStatus;
import com.phraselog.ai.logging.repository.AiRequestLogStore;
import com.phraselog.ai.logging.service.AiCostCalculator;
import com.phraselog.ai.logging.service.AiRequestLogger;
import com.phraselog.ai.prompt.dto.PromptDefinition;
import com.phraselog.common.web.ApiErrorException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnthropicServiceTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void testFeatureRoutingSonnet() {
    assertThat(FeatureRouting.getModelForFeature(AiFeature.S07_ANALYSIS))
        .isEqualTo("claude-sonnet-4-6");
    assertThat(FeatureRouting.getModelForFeature(AiFeature.ROLEPLAY_SESSION_INIT))
        .isEqualTo("claude-sonnet-4-6");
    assertThat(FeatureRouting.getModelForFeature(AiFeature.ROLEPLAY_RESULT))
        .isEqualTo("claude-sonnet-4-6");
  }

  @Test
  void testFeatureRoutingHaiku() {
    assertThat(FeatureRouting.getModelForFeature(AiFeature.ROLEPLAY_TURN_FEEDBACK))
        .isEqualTo("claude-haiku-4-5");
  }

  @Test
  void testTimeoutForFeatures() {
    // 60s for S07, set from measured latency (25.7s / 27.8s / 35.1s, 2026-07-25) after the
    // timeout became enforceable; the other Sonnet features stay at the unmeasured 30s default.
    assertThat(FeatureRouting.getTimeoutForFeature(AiFeature.S07_ANALYSIS))
        .isEqualTo(Duration.ofSeconds(60));
    assertThat(FeatureRouting.getTimeoutForFeature(AiFeature.ROLEPLAY_SESSION_INIT))
        .isEqualTo(Duration.ofSeconds(30));
    assertThat(FeatureRouting.getTimeoutForFeature(AiFeature.ROLEPLAY_RESULT))
        .isEqualTo(Duration.ofSeconds(30));
    assertThat(FeatureRouting.getTimeoutForFeature(AiFeature.ROLEPLAY_TURN_RESPONSE))
        .isEqualTo(Duration.ofSeconds(15));
    assertThat(FeatureRouting.getTimeoutForFeature(AiFeature.ROLEPLAY_TURN_FEEDBACK))
        .isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void testAnthropicMessageUser() {
    AnthropicMessage msg = AnthropicMessage.user("Hello");
    assertThat(msg.role()).isEqualTo("user");
    assertThat(msg.content()).isEqualTo("Hello");
  }

  @Test
  void testAnthropicMessageAssistant() {
    AnthropicMessage msg = AnthropicMessage.assistant("Response");
    assertThat(msg.role()).isEqualTo("assistant");
    assertThat(msg.content()).isEqualTo("Response");
  }

  @Test
  void testAnthropicMessageValidation() {
    assertThatThrownBy(() -> new AnthropicMessage(null, "content"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AnthropicMessage("  ", "content"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void callClaudeValidatesS07ResponseAgainstClasspathSchema() throws Exception {
    RecordingLogStore logStore = new RecordingLogStore();
    JsonNode providerResponse = MAPPER.readTree(validS07Analysis());
    AnthropicClient client =
        (modelId, messages, timeout) -> AnthropicResponse.withoutUsage(providerResponse);
    AnthropicService service =
        new AnthropicService(
            client,
            new AiRequestLogger(logStore),
            new AiCostCalculator(),
            new JsonSchemaValidator(MAPPER));
    UUID userId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();

    JsonNode response =
        service.callClaude(
            AiFeature.S07_ANALYSIS, promptDefinition(), "user situation", userId, correlationId);

    assertThat(response.get("expressions")).hasSize(3);
    assertThat(logStore.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.feature()).isEqualTo(AiFeature.S07_ANALYSIS);
              assertThat(entry.status()).isEqualTo(AiRequestStatus.SUCCESS);
              assertThat(entry.userId()).isEqualTo(userId);
              assertThat(entry.requestCorrelationId()).isEqualTo(correlationId);
              assertThat(entry.promptVersion()).isEqualTo("s07-v1");
            });
  }

  /**
   * Regression for the usage loss found by the first real keyed call (#107): the client reported
   * token counts, but they never reached {@code ai_request_logs}, so every real row stored null
   * tokens and null cost. No test covered the token/cost columns at all, which is why the mock —
   * which reports no usage — kept the gap invisible.
   */
  @Test
  void callClaudeLogsReportedTokensAndDerivedCost() throws Exception {
    RecordingLogStore logStore = new RecordingLogStore();
    AnthropicResponse withUsage =
        new AnthropicResponse(MAPPER.readTree(validS07Analysis()), 1500, 1200);
    AnthropicService service =
        new AnthropicService(
            new CapturingAnthropicClient(withUsage),
            new AiRequestLogger(logStore),
            new AiCostCalculator(),
            new JsonSchemaValidator(MAPPER));

    service.callClaude(
        AiFeature.S07_ANALYSIS,
        promptDefinition(),
        "user situation",
        UUID.randomUUID(),
        UUID.randomUUID());

    assertThat(logStore.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.inputTokens()).isEqualTo(1500);
              assertThat(entry.outputTokens()).isEqualTo(1200);
              assertThat(entry.estimatedCostUsd())
                  .isEqualByComparingTo(
                      new AiCostCalculator().llm("claude-sonnet-4-6", 1500, 1200));
            });
  }

  /** A provider that omits usage must log null cost rather than a fabricated zero. */
  @Test
  void callClaudeLogsNullCostWhenProviderReportsNoUsage() throws Exception {
    RecordingLogStore logStore = new RecordingLogStore();
    AnthropicService service =
        new AnthropicService(
            new CapturingAnthropicClient(
                AnthropicResponse.withoutUsage(MAPPER.readTree(validS07Analysis()))),
            new AiRequestLogger(logStore),
            new AiCostCalculator(),
            new JsonSchemaValidator(MAPPER));

    service.callClaude(
        AiFeature.S07_ANALYSIS,
        promptDefinition(),
        "user situation",
        UUID.randomUUID(),
        UUID.randomUUID());

    assertThat(logStore.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.inputTokens()).isNull();
              assertThat(entry.outputTokens()).isNull();
              assertThat(entry.estimatedCostUsd()).isNull();
            });
  }

  @Test
  void callClaudeUsesFeatureSpecificTimeoutForRoleplayTurnResponse() throws Exception {
    RecordingLogStore logStore = new RecordingLogStore();
    CapturingAnthropicClient client =
        new CapturingAnthropicClient(
            AnthropicResponse.withoutUsage(MAPPER.readTree(validS07Analysis())));
    AnthropicService service =
        new AnthropicService(
            client,
            new AiRequestLogger(logStore),
            new AiCostCalculator(),
            new JsonSchemaValidator(MAPPER));

    service.callClaude(
        AiFeature.ROLEPLAY_TURN_RESPONSE,
        promptDefinition(),
        "conversation state",
        UUID.randomUUID(),
        UUID.randomUUID());

    assertThat(client.timeout()).isEqualTo(Duration.ofSeconds(15));
  }

  @Test
  void provider5xxRetriesOnceThenSucceeds() throws Exception {
    RecordingLogStore logStore = new RecordingLogStore();
    RecordingSleeper sleeper = new RecordingSleeper();
    ScriptedAnthropicClient client =
        new ScriptedAnthropicClient(
            error(AiErrorCode.PROVIDER_5XX, true), MAPPER.readTree(validS07Analysis()));
    AnthropicService service = serviceWith(client, logStore, sleeper);

    JsonNode response =
        service.callClaude(
            AiFeature.S07_ANALYSIS,
            promptDefinition(),
            "user situation",
            UUID.randomUUID(),
            UUID.randomUUID());

    assertThat(response.get("expressions")).hasSize(3);
    assertThat(client.callCount()).isEqualTo(2);
    assertThat(sleeper.sleeps()).containsExactly(500L);
    // ADR-011: the failed attempt keeps its own row rather than being folded into the success.
    assertThat(logStore.entries()).hasSize(2);
    assertAttemptGroup(logStore);
    assertThat(logStore.entries().get(0))
        .satisfies(
            e -> {
              assertThat(e.status()).isEqualTo(AiRequestStatus.ERROR);
              assertThat(e.errorCode()).isEqualTo(AiErrorCode.PROVIDER_5XX);
              assertThat(e.attemptNumber()).isEqualTo(1);
              assertThat(e.isFinalAttempt()).isFalse();
            });
    assertThat(logStore.entries().get(1))
        .satisfies(
            e -> {
              assertThat(e.status()).isEqualTo(AiRequestStatus.SUCCESS);
              assertThat(e.attemptNumber()).isEqualTo(2);
              assertThat(e.isFinalAttempt()).isTrue();
            });
  }

  @Test
  void provider5xxRetriesOnceThenFailsWithProvider5xx() {
    RecordingLogStore logStore = new RecordingLogStore();
    RecordingSleeper sleeper = new RecordingSleeper();
    ScriptedAnthropicClient client =
        new ScriptedAnthropicClient(
            error(AiErrorCode.PROVIDER_5XX, true), error(AiErrorCode.PROVIDER_5XX, true));
    AnthropicService service = serviceWith(client, logStore, sleeper);
    UUID correlationId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                service.callClaude(
                    AiFeature.S07_ANALYSIS,
                    promptDefinition(),
                    "user situation",
                    UUID.randomUUID(),
                    correlationId))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(e -> assertThat(((ApiErrorException) e).errorCode()).isEqualTo("provider_5xx"));

    assertThat(client.callCount()).isEqualTo(2);
    assertThat(sleeper.sleeps()).containsExactly(500L);
    assertThat(logStore.entries()).hasSize(2);
    assertAttemptGroup(logStore);
    assertThat(logStore.entries())
        .allSatisfy(
            e -> {
              assertThat(e.status()).isEqualTo(AiRequestStatus.ERROR);
              assertThat(e.errorCode()).isEqualTo(AiErrorCode.PROVIDER_5XX);
              assertThat(e.requestCorrelationId()).isEqualTo(correlationId);
              // No usable response on either attempt: cost is unknown, not zero.
              assertThat(e.estimatedCostUsd()).isNull();
            });
  }

  @Test
  void provider429RetriesTwiceWithOneAndThreeSecondSchedule() {
    RecordingLogStore logStore = new RecordingLogStore();
    RecordingSleeper sleeper = new RecordingSleeper();
    ScriptedAnthropicClient client =
        new ScriptedAnthropicClient(
            error(AiErrorCode.PROVIDER_429, true),
            error(AiErrorCode.PROVIDER_429, true),
            error(AiErrorCode.PROVIDER_429, true));
    AnthropicService service = serviceWith(client, logStore, sleeper);

    assertThatThrownBy(
            () ->
                service.callClaude(
                    AiFeature.S07_ANALYSIS,
                    promptDefinition(),
                    "user situation",
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(e -> assertThat(((ApiErrorException) e).errorCode()).isEqualTo("provider_429"));

    assertThat(client.callCount()).isEqualTo(3);
    assertThat(sleeper.sleeps()).containsExactly(1000L, 3000L);
    // Three attempts, three rows — the retry rate is now countable in SQL (ADR-011).
    assertThat(logStore.entries()).hasSize(3);
    assertAttemptGroup(logStore);
    assertThat(logStore.entries())
        .allSatisfy(e -> assertThat(e.errorCode()).isEqualTo(AiErrorCode.PROVIDER_429));
  }

  @Test
  void timeoutDoesNotRetry() {
    RecordingLogStore logStore = new RecordingLogStore();
    RecordingSleeper sleeper = new RecordingSleeper();
    ScriptedAnthropicClient client = new ScriptedAnthropicClient(error(AiErrorCode.TIMEOUT, false));
    AnthropicService service = serviceWith(client, logStore, sleeper);

    assertThatThrownBy(
            () ->
                service.callClaude(
                    AiFeature.S07_ANALYSIS,
                    promptDefinition(),
                    "user situation",
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(e -> assertThat(((ApiErrorException) e).errorCode()).isEqualTo("timeout"));

    assertThat(client.callCount()).isEqualTo(1);
    assertThat(sleeper.sleeps()).isEmpty();
    assertThat(logStore.entries())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.status()).isEqualTo(AiRequestStatus.TIMEOUT);
              assertThat(e.attemptNumber()).isEqualTo(1);
              assertThat(e.isFinalAttempt()).isTrue();
              // No response arrived, so the token count is genuinely unknown — NULL, not 0.
              assertThat(e.inputTokens()).isNull();
              assertThat(e.outputTokens()).isNull();
              assertThat(e.estimatedCostUsd()).isNull();
            });
  }

  @Test
  void networkErrorRetriesOnceThenSucceeds() throws Exception {
    RecordingLogStore logStore = new RecordingLogStore();
    RecordingSleeper sleeper = new RecordingSleeper();
    ScriptedAnthropicClient client =
        new ScriptedAnthropicClient(
            error(AiErrorCode.NETWORK, true), MAPPER.readTree(validS07Analysis()));
    AnthropicService service = serviceWith(client, logStore, sleeper);

    service.callClaude(
        AiFeature.S07_ANALYSIS,
        promptDefinition(),
        "user situation",
        UUID.randomUUID(),
        UUID.randomUUID());

    assertThat(client.callCount()).isEqualTo(2);
    assertThat(sleeper.sleeps()).containsExactly(500L);
    assertThat(logStore.entries()).hasSize(2);
    assertAttemptGroup(logStore);
  }

  @Test
  void schemaValidationRetriesOnceWithConstraintReminderThenSucceeds() throws Exception {
    RecordingLogStore logStore = new RecordingLogStore();
    RecordingSleeper sleeper = new RecordingSleeper();
    ScriptedAnthropicClient client =
        new ScriptedAnthropicClient(
            MAPPER.readTree("{\"expressions\":[]}"), MAPPER.readTree(validS07Analysis()));
    AnthropicService service = serviceWith(client, logStore, sleeper);

    JsonNode response =
        service.callClaude(
            AiFeature.S07_ANALYSIS,
            promptDefinition(),
            "user situation",
            UUID.randomUUID(),
            UUID.randomUUID());

    assertThat(response.get("expressions")).hasSize(3);
    assertThat(client.callCount()).isEqualTo(2);
    // First attempt sends the base single message; the retry appends exactly one reminder turn.
    assertThat(client.messagesAt(0)).hasSize(1);
    assertThat(client.messagesAt(1)).hasSize(2);
    assertThat(client.messagesAt(1)[1].content()).contains("s07_analysis_v1");
    assertThat(logStore.entries()).hasSize(2);
    assertAttemptGroup(logStore);
    // prompt_version is the prompt file's version. The constraint reminder is an extra message,
    // not a different prompt, so both attempts carry the same value (ADR-011).
    assertThat(logStore.entries())
        .allSatisfy(e -> assertThat(e.promptVersion()).isEqualTo("s07-v1"));
  }

  @Test
  void schemaValidationFailsAfterSingleRetry() {
    RecordingLogStore logStore = new RecordingLogStore();
    RecordingSleeper sleeper = new RecordingSleeper();
    ScriptedAnthropicClient client =
        new ScriptedAnthropicClient(
            readTree("{\"expressions\":[]}"), readTree("{\"expressions\":[]}"));
    AnthropicService service = serviceWith(client, logStore, sleeper);

    assertThatThrownBy(
            () ->
                service.callClaude(
                    AiFeature.S07_ANALYSIS,
                    promptDefinition(),
                    "user situation",
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            e ->
                assertThat(((ApiErrorException) e).errorCode())
                    .isEqualTo("schema_validation_failed"));

    assertThat(client.callCount()).isEqualTo(2);
    assertThat(logStore.entries()).hasSize(2);
    assertAttemptGroup(logStore);
    assertThat(logStore.entries())
        .allSatisfy(
            e -> {
              assertThat(e.status()).isEqualTo(AiRequestStatus.ERROR);
              assertThat(e.errorCode()).isEqualTo(AiErrorCode.SCHEMA_VALIDATION_FAILED);
            });
  }

  /**
   * Documents the mixed-error-sequence contract (exec-plan 2026-07-13): the retry SCHEDULE is fixed
   * by the first failure while the terminal error_code reflects the last attempt. A first 5xx
   * schedules one 500ms retry; if that retry fails schema validation, no schema reminder retry
   * happens (the 5xx budget is already spent) and the terminal code is the last error
   * (schema_validation_failed). This is deterministic and bounded — the test pins it so the
   * behaviour cannot drift silently.
   */
  @Test
  void mixedFirst5xxThenSchemaFailureUsesFirstErrorScheduleAndLastErrorCode() {
    RecordingLogStore logStore = new RecordingLogStore();
    RecordingSleeper sleeper = new RecordingSleeper();
    ScriptedAnthropicClient client =
        new ScriptedAnthropicClient(
            error(AiErrorCode.PROVIDER_5XX, true), readTree("{\"expressions\":[]}"));
    AnthropicService service = serviceWith(client, logStore, sleeper);

    assertThatThrownBy(
            () ->
                service.callClaude(
                    AiFeature.S07_ANALYSIS,
                    promptDefinition(),
                    "user situation",
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            e ->
                assertThat(((ApiErrorException) e).errorCode())
                    .isEqualTo("schema_validation_failed"));

    // 5xx budget (one 500ms retry) governs; the schema failure gets no reminder retry of its own.
    assertThat(client.callCount()).isEqualTo(2);
    assertThat(sleeper.sleeps()).containsExactly(500L);
    assertThat(client.messagesAt(1)).hasSize(1);
    // Per-attempt rows keep BOTH codes. The pre-ADR-011 single row recorded only the last, so the
    // 5xx that actually started the sequence vanished from error_code distributions.
    assertThat(logStore.entries()).hasSize(2);
    assertAttemptGroup(logStore);
    assertThat(logStore.entries().get(0).errorCode()).isEqualTo(AiErrorCode.PROVIDER_5XX);
    assertThat(logStore.entries().get(1).errorCode())
        .isEqualTo(AiErrorCode.SCHEMA_VALIDATION_FAILED);
  }

  /**
   * A schema failure differs from a transport failure in cost accounting: the provider DID return a
   * response and DID bill for it, so those tokens must survive into the attempt's row. Folding
   * retries into one row lost them entirely, which was the under-counting this change fixes.
   */
  @Test
  void schemaFailureRetryKeepsTheFailedAttemptsTokensInCost() throws Exception {
    RecordingLogStore logStore = new RecordingLogStore();
    RecordingSleeper sleeper = new RecordingSleeper();
    ScriptedAnthropicClient client =
        new ScriptedAnthropicClient(
            new AnthropicResponse(readTree("{\"expressions\":[]}"), 900, 100),
            new AnthropicResponse(MAPPER.readTree(validS07Analysis()), 1500, 1200));
    AnthropicService service = serviceWith(client, logStore, sleeper);

    service.callClaude(
        AiFeature.S07_ANALYSIS,
        promptDefinition(),
        "user situation",
        UUID.randomUUID(),
        UUID.randomUUID());

    assertThat(logStore.entries()).hasSize(2);
    assertAttemptGroup(logStore);
    AiCostCalculator calculator = new AiCostCalculator();
    assertThat(logStore.entries().get(0))
        .satisfies(
            e -> {
              assertThat(e.status()).isEqualTo(AiRequestStatus.ERROR);
              assertThat(e.inputTokens()).isEqualTo(900);
              assertThat(e.outputTokens()).isEqualTo(100);
              assertThat(e.estimatedCostUsd())
                  .isEqualByComparingTo(calculator.llm("claude-sonnet-4-6", 900, 100));
            });

    // The bill is the sum over the group, with no is_final_attempt filter.
    BigDecimal groupCost =
        logStore.entries().stream()
            .map(AiRequestLogEntry::estimatedCostUsd)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(groupCost)
        .isEqualByComparingTo(
            calculator
                .llm("claude-sonnet-4-6", 900, 100)
                .add(calculator.llm("claude-sonnet-4-6", 1500, 1200)));
  }

  /**
   * The success path is unchanged in shape: one attempt, one row, marked final. Pins that the new
   * columns do not perturb the common case.
   */
  @Test
  void firstAttemptSuccessWritesOneFinalAttemptRow() throws Exception {
    RecordingLogStore logStore = new RecordingLogStore();
    AnthropicService service =
        serviceWith(
            new CapturingAnthropicClient(
                new AnthropicResponse(MAPPER.readTree(validS07Analysis()), 10, 20)),
            logStore,
            new RecordingSleeper());

    service.callClaude(
        AiFeature.S07_ANALYSIS,
        promptDefinition(),
        "user situation",
        UUID.randomUUID(),
        UUID.randomUUID());

    assertThat(logStore.entries())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.attemptNumber()).isEqualTo(1);
              assertThat(e.isFinalAttempt()).isTrue();
              assertThat(e.attemptGroupId()).isNotNull();
            });
  }

  /**
   * Group invariants that must hold for every logical call: one shared group id, dense 1-based
   * numbering in call order, and exactly one final attempt — the last one.
   */
  private static void assertAttemptGroup(RecordingLogStore logStore) {
    List<AiRequestLogEntry> entries = logStore.entries();
    assertThat(entries).isNotEmpty();
    assertThat(entries)
        .extracting(AiRequestLogEntry::attemptGroupId)
        .containsOnly(entries.get(0).attemptGroupId());
    for (int i = 0; i < entries.size(); i++) {
      assertThat(entries.get(i).attemptNumber()).isEqualTo(i + 1);
    }
    assertThat(entries.stream().filter(AiRequestLogEntry::isFinalAttempt)).hasSize(1);
    assertThat(entries.get(entries.size() - 1).isFinalAttempt()).isTrue();
  }

  private AnthropicService serviceWith(
      AnthropicClient client, RecordingLogStore logStore, RecordingSleeper sleeper) {
    AnthropicService service =
        new AnthropicService(
            client,
            new AiRequestLogger(logStore),
            new AiCostCalculator(),
            new JsonSchemaValidator(MAPPER));
    service.setSleeper(sleeper);
    return service;
  }

  private static AnthropicClient.AnthropicClientException error(
      AiErrorCode code, boolean retryable) {
    return new AnthropicClient.AnthropicClientException(code.wireName(), code, retryable);
  }

  private static JsonNode readTree(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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

  private static String validS07Analysis() {
    return """
        {
          "expressions": [
            {
              "english": "A",
              "tone_label": "polite",
              "ipa": "/a/",
              "korean_pronunciation": "ei",
              "pronunciation_tip": "Keep it short.",
              "cultural_tip": "Use with close friends."
            },
            {
              "english": "B",
              "tone_label": "gentle",
              "ipa": "/b/",
              "korean_pronunciation": "bi",
              "pronunciation_tip": "Keep it short.",
              "cultural_tip": "Use with friends."
            },
            {
              "english": "C",
              "tone_label": "firm",
              "ipa": "/c/",
              "korean_pronunciation": "si",
              "pronunciation_tip": "Keep it short.",
              "cultural_tip": "Use when the situation repeats."
            }
          ]
        }
        """;
  }

  private static final class RecordingLogStore implements AiRequestLogStore {

    private final List<AiRequestLogEntry> entries = new ArrayList<>();

    @Override
    public void save(AiRequestLogEntry entry) {
      entries.add(entry);
    }

    List<AiRequestLogEntry> entries() {
      return entries;
    }
  }

  private static final class CapturingAnthropicClient implements AnthropicClient {

    private final AnthropicResponse response;
    private Duration timeout;

    private CapturingAnthropicClient(AnthropicResponse response) {
      this.response = response;
    }

    @Override
    public AnthropicResponse sendMessage(
        String modelId, AnthropicMessage[] messages, Duration timeout) {
      this.timeout = timeout;
      return response;
    }

    Duration timeout() {
      return timeout;
    }
  }

  /**
   * Scripted client: each call consumes the next outcome. A JsonNode outcome is returned; an
   * AnthropicClientException outcome is thrown. Records the messages passed to each call so the
   * schema-retry constraint reminder can be asserted. An unexpected extra call fails loudly.
   */
  private static final class ScriptedAnthropicClient implements AnthropicClient {

    private final Deque<Object> outcomes;
    private final List<AnthropicMessage[]> calls = new ArrayList<>();

    private ScriptedAnthropicClient(Object... outcomes) {
      this.outcomes = new ArrayDeque<>(List.of(outcomes));
    }

    @Override
    public AnthropicResponse sendMessage(
        String modelId, AnthropicMessage[] messages, Duration timeout)
        throws AnthropicClientException {
      calls.add(messages);
      if (outcomes.isEmpty()) {
        throw new AssertionError("Unexpected extra call to Anthropic client");
      }
      Object next = outcomes.poll();
      if (next instanceof AnthropicClientException e) {
        throw e;
      }
      if (next instanceof AnthropicResponse response) {
        return response;
      }
      return AnthropicResponse.withoutUsage((JsonNode) next);
    }

    int callCount() {
      return calls.size();
    }

    AnthropicMessage[] messagesAt(int index) {
      return calls.get(index);
    }
  }

  /** Fake sleeper that records each requested backoff instead of waiting. */
  private static final class RecordingSleeper implements AnthropicService.Sleeper {

    private final List<Long> sleeps = new ArrayList<>();

    @Override
    public void sleep(long millis) {
      sleeps.add(millis);
    }

    List<Long> sleeps() {
      return sleeps;
    }
  }
}
