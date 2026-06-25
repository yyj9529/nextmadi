package com.phraselog.ai.client.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OpenAiTranscriptionLiveTest {

  @Disabled("Live OpenAI acceptance: set OPENAI_API_KEY and provide a local WebM/Opus fixture.")
  @Test
  void transcribesRealWebmOpusFixture() throws Exception {
    String apiKey = System.getenv("OPENAI_API_KEY");
    byte[] audio = Files.readAllBytes(Path.of("src/test/resources/audio/stt-smoke.webm"));
    RestClientOpenAiTranscriptionClient client =
        new RestClientOpenAiTranscriptionClient(apiKey, new ObjectMapper(), RestClient.builder());

    OpenAiTranscriptionResult result =
        client.transcribe(audio, "stt-smoke.webm", "audio/webm", Duration.ofSeconds(30));

    assertThat(result.text()).isNotBlank();
    assertThat(result.text().toLowerCase()).contains("hello");
  }
}
