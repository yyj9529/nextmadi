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

class RestClientOpenAiTranscriptionClientTests {

  @Test
  void postsWhisperVerboseJsonMultipartRequest() throws Exception {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClientOpenAiTranscriptionClient client =
        new RestClientOpenAiTranscriptionClient("test-key", new ObjectMapper(), builder);

    server
        .expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-key"))
        .andExpect(content().string(containsString("name=\"model\"")))
        .andExpect(content().string(containsString("whisper-1")))
        .andExpect(content().string(containsString("name=\"response_format\"")))
        .andExpect(content().string(containsString("verbose_json")))
        .andExpect(content().string(containsString("name=\"timestamp_granularities[]\"")))
        .andRespond(
            withSuccess(
                """
                {
                  "text": "hello",
                  "usage": { "type": "duration", "seconds": 1.5 },
                  "segments": [{ "avg_logprob": -0.25 }]
                }
                """,
                MediaType.APPLICATION_JSON));

    OpenAiTranscriptionResult result =
        client.transcribe(new byte[] {1, 2, 3}, "voice.webm", "audio/webm", Duration.ofSeconds(30));

    assertThat(result.text()).isEqualTo("hello");
    assertThat(result.usageSeconds()).hasValue(1.5);
    assertThat(result.lowestSegmentAvgLogprob()).hasValue(-0.25);
    server.verify();
  }

  @Test
  void mapsRateLimitToProvider429() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClientOpenAiTranscriptionClient client =
        new RestClientOpenAiTranscriptionClient("test-key", new ObjectMapper(), builder);

    server
        .expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

    assertThatThrownBy(
            () ->
                client.transcribe(
                    new byte[] {1, 2, 3}, "voice.webm", "audio/webm", Duration.ofSeconds(30)))
        .isInstanceOf(OpenAiTranscriptionClient.OpenAiTranscriptionException.class)
        .satisfies(
            error -> {
              OpenAiTranscriptionClient.OpenAiTranscriptionException openAiError =
                  (OpenAiTranscriptionClient.OpenAiTranscriptionException) error;
              assertThat(openAiError.errorCode()).isEqualTo(AiErrorCode.PROVIDER_429);
              assertThat(openAiError.isRetryable()).isTrue();
            });
  }
}
