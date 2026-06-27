package com.phraselog.ai.client.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.logging.dto.AiErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientOpenAiTtsClientTests {

  private static final byte[] MP3 = {(byte) 0xFF, (byte) 0xFB, 0x10, 0x00};

  @Test
  void postsSpeechJsonRequestAndReturnsAudioBytes() throws Exception {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClientOpenAiTtsClient client =
        new RestClientOpenAiTtsClient("test-key", new ObjectMapper(), builder);

    server
        .expect(requestTo("https://api.openai.com/v1/audio/speech"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-key"))
        .andExpect(content().string(containsString("\"model\":\"tts-1\"")))
        .andExpect(content().string(containsString("\"voice\":\"mia\"")))
        .andExpect(content().string(containsString("\"input\":\"hello there\"")))
        .andRespond(withSuccess(MP3, MediaType.parseMediaType("audio/mpeg")));

    byte[] audio = client.synthesize("tts-1", "mia", "hello there", Duration.ofSeconds(15));

    assertThat(audio).isEqualTo(MP3);
    server.verify();
  }

  @Test
  void mapsServerErrorToProvider5xx() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClientOpenAiTtsClient client =
        new RestClientOpenAiTtsClient("test-key", new ObjectMapper(), builder);

    server
        .expect(requestTo("https://api.openai.com/v1/audio/speech"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

    assertThatThrownBy(() -> client.synthesize("tts-1", "mia", "hi", Duration.ofSeconds(15)))
        .isInstanceOf(OpenAiTtsClient.OpenAiTtsException.class)
        .satisfies(
            error -> {
              OpenAiTtsClient.OpenAiTtsException ttsError =
                  (OpenAiTtsClient.OpenAiTtsException) error;
              assertThat(ttsError.errorCode()).isEqualTo(AiErrorCode.PROVIDER_5XX);
              assertThat(ttsError.isRetryable()).isTrue();
            });
  }

  @Test
  void mapsRateLimitToProvider429() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClientOpenAiTtsClient client =
        new RestClientOpenAiTtsClient("test-key", new ObjectMapper(), builder);

    server
        .expect(requestTo("https://api.openai.com/v1/audio/speech"))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

    assertThatThrownBy(() -> client.synthesize("tts-1", "mia", "hi", Duration.ofSeconds(15)))
        .isInstanceOf(OpenAiTtsClient.OpenAiTtsException.class)
        .satisfies(
            error ->
                assertThat(((OpenAiTtsClient.OpenAiTtsException) error).errorCode())
                    .isEqualTo(AiErrorCode.PROVIDER_429));
  }

  @Test
  void blankApiKeyFailsFastWithoutCallingProvider() {
    RestClientOpenAiTtsClient client =
        new RestClientOpenAiTtsClient("", new ObjectMapper(), RestClient.builder());

    assertThatThrownBy(() -> client.synthesize("tts-1", "mia", "hi", Duration.ofSeconds(15)))
        .isInstanceOf(OpenAiTtsClient.OpenAiTtsException.class)
        .satisfies(
            error -> {
              OpenAiTtsClient.OpenAiTtsException ttsError =
                  (OpenAiTtsClient.OpenAiTtsException) error;
              assertThat(ttsError.errorCode()).isEqualTo(AiErrorCode.UNKNOWN);
              assertThat(ttsError.isRetryable()).isFalse();
            });
  }
}
