package com.phraselog.practice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.client.service.AnthropicService;
import com.phraselog.ai.logging.dto.AiFeature;
import com.phraselog.ai.prompt.dto.PromptDefinition;
import com.phraselog.ai.prompt.service.PromptLoader;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.practice.dto.PracticeTurnResponse;
import com.phraselog.practice.dto.SubmitPracticeTurnRequest;
import com.phraselog.practice.dto.SubmitPracticeTurnResponse;
import com.phraselog.practice.dto.TurnFeedbackResponse;
import com.phraselog.practice.repository.AppendTurnPairCommand;
import com.phraselog.practice.repository.PracticeSessionContext;
import com.phraselog.practice.repository.PracticeTurnRepository;
import com.phraselog.practice.repository.PracticeTurnRequestRow;
import com.phraselog.transcription.dto.TranscriptionResponse;
import com.phraselog.transcription.service.TranscriptionService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

class PracticeTurnServiceTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private PracticeTurnRepository repository;
  private AnthropicService anthropicService;
  private PromptLoader promptLoader;
  private TranscriptionService transcriptionService;
  private PracticeAudioService audioService;
  private PracticeTurnService service;

  @BeforeEach
  void setUp() {
    repository = mock(PracticeTurnRepository.class);
    anthropicService = mock(AnthropicService.class);
    promptLoader = mock(PromptLoader.class);
    transcriptionService = mock(TranscriptionService.class);
    audioService = mock(PracticeAudioService.class);
    service =
        new PracticeTurnService(
            repository, anthropicService, promptLoader, transcriptionService, audioService, MAPPER);

    when(promptLoader.load("roleplay/turn", 1)).thenReturn(prompt("roleplay_turn_response_v1"));
    when(promptLoader.load("roleplay/feedback", 1)).thenReturn(prompt("roleplay_turn_feedback_v1"));
  }

  @Test
  void normalTextTurnConsumesUserTurnAndReturnsActiveStatus() throws Exception {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());
    UUID sessionId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    PracticeTurnRequestRow request = new PracticeTurnRequestRow(UUID.randomUUID(), correlationId);
    PracticeSessionContext context = context(sessionId, UUID.fromString(principal.userId()), 3, 0);
    when(repository.findSessionForUser(sessionId, UUID.fromString(principal.userId())))
        .thenReturn(Optional.of(context));
    when(repository.findReplay(sessionId, idempotencyKey)).thenReturn(Optional.empty());
    when(repository.reserveRequest(sessionId, idempotencyKey, correlationId)).thenReturn(request);
    when(anthropicService.callClaude(
            eq(AiFeature.ROLEPLAY_TURN_RESPONSE), any(), any(), any(), eq(correlationId)))
        .thenReturn(MAPPER.readTree("{\"coach_utterance\":\"Tell me more.\"}"));
    when(anthropicService.callClaude(
            eq(AiFeature.ROLEPLAY_TURN_FEEDBACK), any(), any(), any(), eq(correlationId)))
        .thenReturn(
            MAPPER.readTree(
                """
                {
                  "show_feedback": true,
                  "natural_alternative": "Could you say that again?",
                  "korean_comment": "Try this version."
                }
                """));
    SubmitPracticeTurnResponse stored =
        consumedResponseWithFeedback("Could you repeat that?", "Tell me more.", "active");
    when(repository.appendTurnPair(eq(request), any())).thenReturn(stored);

    SubmitPracticeTurnResponse response =
        service.submitTextTurn(
            principal,
            sessionId.toString(),
            new SubmitPracticeTurnRequest("Could you repeat that?"),
            idempotencyKey.toString(),
            () -> correlationId);

    assertThat(response.turnConsumed()).isTrue();
    assertThat(response.sessionStatus()).isEqualTo("active");
    assertThat(response.feedback()).isNotNull();
    verify(repository).appendTurnPair(eq(request), any());
  }

  @Test
  void lowConfidenceVoiceTurnDoesNotConsumeTurnOrCallLlm() {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());
    UUID sessionId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    PracticeTurnRequestRow request = new PracticeTurnRequestRow(UUID.randomUUID(), correlationId);
    PracticeSessionContext context = context(sessionId, UUID.fromString(principal.userId()), 3, 0);
    MockMultipartFile audio =
        new MockMultipartFile("audio", "voice.webm", "audio/webm", new byte[] {1, 2, 3});
    when(repository.findSessionForUser(sessionId, UUID.fromString(principal.userId())))
        .thenReturn(Optional.of(context));
    when(repository.findReplay(sessionId, idempotencyKey)).thenReturn(Optional.empty());
    when(repository.reserveRequest(sessionId, idempotencyKey, correlationId)).thenReturn(request);
    when(transcriptionService.transcribe(principal, audio, correlationId))
        .thenReturn(new TranscriptionResponse("Could you repeat that?", new BigDecimal("0.400")));
    SubmitPracticeTurnResponse noTurn =
        SubmitPracticeTurnResponse.notConsumed("I couldn't hear you well. Please say that again");
    when(repository.completeWithoutTurn(eq(request), any())).thenReturn(noTurn);

    SubmitPracticeTurnResponse response =
        service.submitAudioTurn(
            principal, sessionId.toString(), audio, idempotencyKey.toString(), () -> correlationId);

    assertThat(response.turnConsumed()).isFalse();
    assertThat(response.retryPrompt()).contains("Please say that again");
    verify(anthropicService, never()).callClaude(any(), any(), any(), any(), any());
    verify(audioService, never()).synthesize(any(), any(), any(), any());
  }

  @Test
  void haikuFailureSkipsFeedbackAndStillStoresCoachTurn() throws Exception {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());
    UUID sessionId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    PracticeTurnRequestRow request = new PracticeTurnRequestRow(UUID.randomUUID(), correlationId);
    PracticeSessionContext context = context(sessionId, UUID.fromString(principal.userId()), 3, 0);
    when(repository.findSessionForUser(sessionId, UUID.fromString(principal.userId())))
        .thenReturn(Optional.of(context));
    when(repository.findReplay(sessionId, idempotencyKey)).thenReturn(Optional.empty());
    when(repository.reserveRequest(sessionId, idempotencyKey, correlationId)).thenReturn(request);
    when(anthropicService.callClaude(
            eq(AiFeature.ROLEPLAY_TURN_RESPONSE), any(), any(), any(), eq(correlationId)))
        .thenReturn(MAPPER.readTree("{\"coach_utterance\":\"Tell me more.\"}"));
    when(anthropicService.callClaude(
            eq(AiFeature.ROLEPLAY_TURN_FEEDBACK), any(), any(), any(), eq(correlationId)))
        .thenThrow(
            new ApiErrorException(
                HttpStatus.REQUEST_TIMEOUT, "timeout", "timeout", "feedback timed out", true));
    SubmitPracticeTurnResponse stored =
        consumedResponse("Could you repeat that?", "Tell me more.", "active");
    when(repository.appendTurnPair(eq(request), any())).thenReturn(stored);

    SubmitPracticeTurnResponse response =
        service.submitTextTurn(
            principal,
            sessionId.toString(),
            new SubmitPracticeTurnRequest("Could you repeat that?"),
            idempotencyKey.toString(),
            () -> correlationId);

    assertThat(response.turnConsumed()).isTrue();
    assertThat(response.feedback()).isNull();
    verify(repository).appendTurnPair(eq(request), any());
  }

  @Test
  void ttsFailureStoresTextOnlyCoachTurn() throws Exception {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());
    UUID sessionId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    PracticeTurnRequestRow request = new PracticeTurnRequestRow(UUID.randomUUID(), correlationId);
    PracticeSessionContext context = context(sessionId, UUID.fromString(principal.userId()), 3, 0);
    when(repository.findSessionForUser(sessionId, UUID.fromString(principal.userId())))
        .thenReturn(Optional.of(context));
    when(repository.findReplay(sessionId, idempotencyKey)).thenReturn(Optional.empty());
    when(repository.reserveRequest(sessionId, idempotencyKey, correlationId)).thenReturn(request);
    when(anthropicService.callClaude(
            eq(AiFeature.ROLEPLAY_TURN_RESPONSE), any(), any(), any(), eq(correlationId)))
        .thenReturn(MAPPER.readTree("{\"coach_utterance\":\"Tell me more.\"}"));
    when(anthropicService.callClaude(
            eq(AiFeature.ROLEPLAY_TURN_FEEDBACK), any(), any(), any(), eq(correlationId)))
        .thenReturn(MAPPER.readTree("{\"show_feedback\":false}"));
    when(audioService.synthesize(any(), eq("Tell me more."), eq("shimmer"), eq(correlationId)))
        .thenThrow(new RuntimeException("tts unavailable"));
    SubmitPracticeTurnResponse stored =
        consumedResponse("Could you repeat that?", "Tell me more.", "active");
    when(repository.appendTurnPair(eq(request), any())).thenReturn(stored);

    service.submitTextTurn(
        principal,
        sessionId.toString(),
        new SubmitPracticeTurnRequest("Could you repeat that?"),
        idempotencyKey.toString(),
        () -> correlationId);

    ArgumentCaptor<AppendTurnPairCommand> command =
        ArgumentCaptor.forClass(AppendTurnPairCommand.class);
    verify(repository).appendTurnPair(eq(request), command.capture());
    assertThat(command.getValue().coachText()).isEqualTo("Tell me more.");
    assertThat(command.getValue().coachAudioUrl()).isNull();
    assertThat(command.getValue().ttsAudioCacheId()).isNull();
  }

  @Test
  void turnResponseFailureMarksRequestFailedAndDoesNotConsumeTurn() throws Exception {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());
    UUID sessionId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    PracticeTurnRequestRow request = new PracticeTurnRequestRow(UUID.randomUUID(), correlationId);
    PracticeSessionContext context = context(sessionId, UUID.fromString(principal.userId()), 3, 0);
    when(repository.findSessionForUser(sessionId, UUID.fromString(principal.userId())))
        .thenReturn(Optional.of(context));
    when(repository.findReplay(sessionId, idempotencyKey)).thenReturn(Optional.empty());
    when(repository.reserveRequest(sessionId, idempotencyKey, correlationId)).thenReturn(request);
    ApiErrorException timeout =
        new ApiErrorException(
            HttpStatus.REQUEST_TIMEOUT, "timeout", "timeout", "turn response timed out", true);
    when(anthropicService.callClaude(
            eq(AiFeature.ROLEPLAY_TURN_RESPONSE), any(), any(), any(), eq(correlationId)))
        .thenThrow(timeout);

    assertThatThrownBy(
            () ->
                service.submitTextTurn(
                    principal,
                    sessionId.toString(),
                    new SubmitPracticeTurnRequest("Could you repeat that?"),
                    idempotencyKey.toString(),
                    () -> correlationId))
        .isSameAs(timeout);

    verify(repository).markFailed(request.id());
    verify(repository, never()).appendTurnPair(any(), any());
    verify(audioService, never()).synthesize(any(), any(), any(), any());
  }

  @Test
  void finalConsumedUserTurnReturnsCompletedStatus() throws Exception {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());
    UUID sessionId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    PracticeTurnRequestRow request = new PracticeTurnRequestRow(UUID.randomUUID(), correlationId);
    PracticeSessionContext context = context(sessionId, UUID.fromString(principal.userId()), 2, 1);
    when(repository.findSessionForUser(sessionId, UUID.fromString(principal.userId())))
        .thenReturn(Optional.of(context));
    when(repository.findReplay(sessionId, idempotencyKey)).thenReturn(Optional.empty());
    when(repository.reserveRequest(sessionId, idempotencyKey, correlationId)).thenReturn(request);
    when(anthropicService.callClaude(
            eq(AiFeature.ROLEPLAY_TURN_RESPONSE), any(), any(), any(), eq(correlationId)))
        .thenReturn(MAPPER.readTree("{\"coach_utterance\":\"That was a good way to close it.\"}"));
    when(anthropicService.callClaude(
            eq(AiFeature.ROLEPLAY_TURN_FEEDBACK), any(), any(), any(), eq(correlationId)))
        .thenReturn(MAPPER.readTree("{\"show_feedback\":false}"));
    SubmitPracticeTurnResponse stored =
        consumedResponse(
            "Thank you for explaining.", "That was a good way to close it.", "completed");
    when(repository.appendTurnPair(eq(request), any())).thenReturn(stored);

    SubmitPracticeTurnResponse response =
        service.submitTextTurn(
            principal,
            sessionId.toString(),
            new SubmitPracticeTurnRequest("Thank you for explaining."),
            idempotencyKey.toString(),
            () -> correlationId);

    assertThat(response.sessionStatus()).isEqualTo("completed");
  }

  @Test
  void completedIdempotencyKeyReplaysStoredResultWithoutProviderCalls() {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());
    UUID sessionId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    PracticeSessionContext context = context(sessionId, UUID.fromString(principal.userId()), 3, 1);
    SubmitPracticeTurnResponse replay =
        consumedResponse("Could you repeat that?", "Tell me more.", "active");
    when(repository.findSessionForUser(sessionId, UUID.fromString(principal.userId())))
        .thenReturn(Optional.of(context));
    when(repository.findReplay(sessionId, idempotencyKey)).thenReturn(Optional.of(replay));

    SubmitPracticeTurnResponse response =
        service.submitTextTurn(
            principal,
            sessionId.toString(),
            new SubmitPracticeTurnRequest("Could you repeat that?"),
            idempotencyKey.toString());

    assertThat(response).isSameAs(replay);
    verify(repository, never()).reserveRequest(any(), any(), any());
    verify(anthropicService, never()).callClaude(any(), any(), any(), any(), any());
    verify(audioService, never()).synthesize(any(), any(), any(), any());
  }

  @Test
  void blankTextTurnDoesNotConsumeTurnOrCallProviders() {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());
    UUID sessionId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    PracticeTurnRequestRow request = new PracticeTurnRequestRow(UUID.randomUUID(), correlationId);
    PracticeSessionContext context = context(sessionId, UUID.fromString(principal.userId()), 3, 0);
    SubmitPracticeTurnResponse noTurn =
        SubmitPracticeTurnResponse.notConsumed("I couldn't hear you well. Please say that again");
    when(repository.findSessionForUser(sessionId, UUID.fromString(principal.userId())))
        .thenReturn(Optional.of(context));
    when(repository.findReplay(sessionId, idempotencyKey)).thenReturn(Optional.empty());
    when(repository.reserveRequest(sessionId, idempotencyKey, correlationId)).thenReturn(request);
    when(repository.completeWithoutTurn(eq(request), any())).thenReturn(noTurn);

    SubmitPracticeTurnResponse response =
        service.submitTextTurn(
            principal,
            sessionId.toString(),
            new SubmitPracticeTurnRequest("   "),
            idempotencyKey.toString(),
            () -> correlationId);

    assertThat(response.turnConsumed()).isFalse();
    verify(anthropicService, never()).callClaude(any(), any(), any(), any(), any());
    verify(audioService, never()).synthesize(any(), any(), any(), any());
  }

  @Test
  void inactiveSessionReturnsConflictBeforeReservingRequest() {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());
    UUID sessionId = UUID.randomUUID();
    PracticeSessionContext context =
        new PracticeSessionContext(
            sessionId,
            UUID.fromString(principal.userId()),
            3,
            3,
            "completed",
            "doctor appointment",
            "Could you repeat that?",
            "Mia",
            "warm coach",
            "shimmer",
            List.of());
    when(repository.findSessionForUser(sessionId, UUID.fromString(principal.userId())))
        .thenReturn(Optional.of(context));

    assertThatThrownBy(
            () ->
                service.submitTextTurn(
                    principal,
                    sessionId.toString(),
                    new SubmitPracticeTurnRequest("Hi"),
                    UUID.randomUUID().toString()))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error -> {
              ApiErrorException api = (ApiErrorException) error;
              assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
            });

    verify(repository, never()).reserveRequest(any(), any(), any());
  }

  @Test
  void missingSessionReturns404BeforeProviderCalls() {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());
    UUID sessionId = UUID.randomUUID();
    when(repository.findSessionForUser(sessionId, UUID.fromString(principal.userId())))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.submitTextTurn(
                    principal,
                    sessionId.toString(),
                    new SubmitPracticeTurnRequest("Hi"),
                    UUID.randomUUID().toString()))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error -> {
              ApiErrorException api = (ApiErrorException) error;
              assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
            });

    verify(anthropicService, never()).callClaude(any(), any(), any(), any(), any());
  }

  @Test
  void missingIdempotencyKeyReturns400BeforeSessionLookup() {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofUser(UUID.randomUUID().toString());

    assertThatThrownBy(
            () ->
                service.submitTextTurn(
                    principal,
                    UUID.randomUUID().toString(),
                    new SubmitPracticeTurnRequest("Hi"),
                    null))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error -> {
              ApiErrorException api = (ApiErrorException) error;
              assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            });

    verify(repository, never()).findSessionForUser(any(), any());
  }

  @Test
  void sessionTokenPrincipalCannotSubmitTurn() {
    InternalAuthPrincipal principal = InternalAuthPrincipal.ofSession(UUID.randomUUID().toString());

    assertThatThrownBy(
            () ->
                service.submitTextTurn(
                    principal,
                    UUID.randomUUID().toString(),
                    new SubmitPracticeTurnRequest("Hi"),
                    UUID.randomUUID().toString()))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error -> {
              ApiErrorException api = (ApiErrorException) error;
              assertThat(api.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
            });

    verify(repository, never()).findSessionForUser(any(), any());
  }

  private static PromptDefinition prompt(String schema) {
    return new PromptDefinition(
        "roleplay", "roleplay-v1", "claude-sonnet-4-6", schema, "2026-06-12", null, "prompt body");
  }

  private static PracticeSessionContext context(
      UUID sessionId, UUID userId, int plannedTurns, int consumedUserTurns) {
    return new PracticeSessionContext(
        sessionId,
        userId,
        plannedTurns,
        consumedUserTurns,
        "active",
        "doctor appointment",
        "Could you repeat that?",
        "Mia",
        "warm coach",
        "shimmer",
        List.of());
  }

  private static SubmitPracticeTurnResponse consumedResponse(
      String userText, String coachText, String status) {
    PracticeTurnResponse userTurn =
        new PracticeTurnResponse(
            UUID.randomUUID(),
            2,
            "user",
            userText,
            null,
            null,
            false,
            OffsetDateTime.parse("2026-06-26T00:00:00Z"));
    PracticeTurnResponse coachTurn =
        new PracticeTurnResponse(
            UUID.randomUUID(),
            3,
            "coach",
            coachText,
            null,
            null,
            false,
            OffsetDateTime.parse("2026-06-26T00:00:01Z"));
    return SubmitPracticeTurnResponse.consumed(userTurn, coachTurn, null, status);
  }

  private static SubmitPracticeTurnResponse consumedResponseWithFeedback(
      String userText, String coachText, String status) {
    PracticeTurnResponse userTurn =
        new PracticeTurnResponse(
            UUID.randomUUID(),
            2,
            "user",
            userText,
            null,
            null,
            true,
            OffsetDateTime.parse("2026-06-26T00:00:00Z"));
    PracticeTurnResponse coachTurn =
        new PracticeTurnResponse(
            UUID.randomUUID(),
            3,
            "coach",
            coachText,
            null,
            null,
            false,
            OffsetDateTime.parse("2026-06-26T00:00:01Z"));
    return SubmitPracticeTurnResponse.consumed(
        userTurn,
        coachTurn,
        new TurnFeedbackResponse(true, "Could you say that again?", "Try this version."),
        status);
  }
}
