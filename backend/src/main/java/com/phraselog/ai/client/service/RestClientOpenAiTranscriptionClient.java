package com.phraselog.ai.client.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.logging.dto.AiErrorCode;
import java.io.IOException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class RestClientOpenAiTranscriptionClient implements OpenAiTranscriptionClient {

  static final String MODEL = "whisper-1";
  private static final String OPENAI_TRANSCRIPTIONS_URL =
      "https://api.openai.com/v1/audio/transcriptions";
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  private final String apiKey;
  private final ObjectMapper objectMapper;
  private final RestClient restClient;

  @Autowired
  public RestClientOpenAiTranscriptionClient(
      @Value("${openai.api-key:${OPENAI_API_KEY:}}") String apiKey, ObjectMapper objectMapper) {
    this(apiKey, objectMapper, defaultBuilder(DEFAULT_TIMEOUT));
  }

  RestClientOpenAiTranscriptionClient(
      String apiKey, ObjectMapper objectMapper, RestClient.Builder builder) {
    this.apiKey = apiKey;
    this.objectMapper = objectMapper;
    this.restClient = builder.build();
  }

  @Override
  public OpenAiTranscriptionResult transcribe(
      byte[] audioBytes, String filename, String contentType, Duration timeout)
      throws OpenAiTranscriptionException {
    if (apiKey == null || apiKey.isBlank()) {
      throw new OpenAiTranscriptionException(
          "OpenAI API key not configured", AiErrorCode.UNKNOWN, false);
    }

    try {
      ResponseEntity<String> response =
          restClient
              .post()
              .uri(OPENAI_TRANSCRIPTIONS_URL)
              .header("Authorization", "Bearer " + apiKey)
              .contentType(MediaType.MULTIPART_FORM_DATA)
              .body(parts(audioBytes, filename, contentType))
              .retrieve()
              .toEntity(String.class);

      JsonNode responseJson = objectMapper.readTree(response.getBody());
      return OpenAiTranscriptionResult.fromJson(responseJson);
    } catch (HttpServerErrorException e) {
      throw new OpenAiTranscriptionException(
          "OpenAI provider error: " + e.getStatusCode(), AiErrorCode.PROVIDER_5XX, true, e);
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
        throw new OpenAiTranscriptionException(
            "OpenAI rate limited", AiErrorCode.PROVIDER_429, true, e);
      }
      throw new OpenAiTranscriptionException(
          "OpenAI client error: " + e.getStatusCode(), AiErrorCode.UNKNOWN, false, e);
    } catch (ResourceAccessException e) {
      if (e.getCause() instanceof java.net.SocketTimeoutException
          || (e.getMessage() != null && e.getMessage().contains("timeout"))) {
        throw new OpenAiTranscriptionException(
            "OpenAI request timeout", AiErrorCode.TIMEOUT, false, e);
      }
      throw new OpenAiTranscriptionException(
          "Network error calling OpenAI", AiErrorCode.NETWORK, true, e);
    } catch (IOException e) {
      throw new OpenAiTranscriptionException(
          "Failed to parse OpenAI transcription response", AiErrorCode.UNKNOWN, false, e);
    }
  }

  private MultiValueMap<String, Object> parts(
      byte[] audioBytes, String filename, String contentType) {
    MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add("file", audioResource(audioBytes, filename, contentType));
    parts.add("model", MODEL);
    parts.add("response_format", "verbose_json");
    parts.add("timestamp_granularities[]", "segment");
    return parts;
  }

  private static ByteArrayResource audioResource(
      byte[] audioBytes, String filename, String contentType) {
    return new ByteArrayResource(audioBytes) {
      @Override
      public String getFilename() {
        return filename == null || filename.isBlank() ? "audio.webm" : filename;
      }

      @Override
      public String getDescription() {
        return contentType == null || contentType.isBlank() ? "audio/webm" : contentType;
      }
    };
  }

  private static RestClient.Builder defaultBuilder(Duration timeout) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(timeout);
    requestFactory.setReadTimeout(timeout);
    return RestClient.builder().requestFactory(requestFactory);
  }
}
