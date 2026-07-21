package com.phraselog.ai.client.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.phraselog.ai.logging.dto.AiErrorCode;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * {@link RestClientOpenAiTranscriptionClient}와 동일한 RestClient/타임아웃/에러매핑 골격을 사용하는 OpenAI TTS 클라이언트.
 *
 * <p>요청 본문은 {@code {model, voice, input}} JSON, 응답은 바이너리 MP3. 기본 응답 포맷이 mp3이므로 별도 {@code
 * response_format}을 지정하지 않는다.
 */
// openai.mock.enabled=true(로컬 전용, application-local.yml)면 이 실호출 클라이언트 대신
// MockOpenAiTtsClient가 배선된다. 기본/prod/CI는 속성이 없어 matchIfMissing으로 실호출을 쓴다(#117).
@Component
@ConditionalOnProperty(name = "openai.mock.enabled", havingValue = "false", matchIfMissing = true)
public class RestClientOpenAiTtsClient implements OpenAiTtsClient {

  static final String MODEL = "tts-1";
  private static final String OPENAI_SPEECH_URL = "https://api.openai.com/v1/audio/speech";
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

  private final String apiKey;
  private final ObjectMapper objectMapper;
  private final RestClient restClient;

  @Autowired
  public RestClientOpenAiTtsClient(
      @Value("${openai.api-key:${OPENAI_API_KEY:}}") String apiKey, ObjectMapper objectMapper) {
    this(apiKey, objectMapper, defaultBuilder(DEFAULT_TIMEOUT));
  }

  RestClientOpenAiTtsClient(String apiKey, ObjectMapper objectMapper, RestClient.Builder builder) {
    this.apiKey = apiKey;
    this.objectMapper = objectMapper;
    this.restClient = builder.build();
  }

  @Override
  public byte[] synthesize(String model, String voice, String input, Duration timeout)
      throws OpenAiTtsException {
    if (apiKey == null || apiKey.isBlank()) {
      throw new OpenAiTtsException("OpenAI API key not configured", AiErrorCode.UNKNOWN, false);
    }

    try {
      ResponseEntity<byte[]> response =
          restClient
              .post()
              .uri(OPENAI_SPEECH_URL)
              .header("Authorization", "Bearer " + apiKey)
              .contentType(MediaType.APPLICATION_JSON)
              .body(requestBody(model, voice, input))
              .retrieve()
              .toEntity(byte[].class);
      return response.getBody();
    } catch (HttpServerErrorException e) {
      throw new OpenAiTtsException(
          "OpenAI provider error: " + e.getStatusCode(), AiErrorCode.PROVIDER_5XX, true, e);
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
        throw new OpenAiTtsException("OpenAI rate limited", AiErrorCode.PROVIDER_429, true, e);
      }
      throw new OpenAiTtsException(
          "OpenAI client error: " + e.getStatusCode(), AiErrorCode.UNKNOWN, false, e);
    } catch (ResourceAccessException e) {
      if (e.getCause() instanceof java.net.SocketTimeoutException
          || (e.getMessage() != null && e.getMessage().contains("timeout"))) {
        throw new OpenAiTtsException("OpenAI request timeout", AiErrorCode.TIMEOUT, false, e);
      }
      throw new OpenAiTtsException("Network error calling OpenAI", AiErrorCode.NETWORK, true, e);
    }
  }

  private String requestBody(String model, String voice, String input) {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", model);
    body.put("voice", voice);
    body.put("input", input);
    return body.toString();
  }

  private static RestClient.Builder defaultBuilder(Duration timeout) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(timeout);
    requestFactory.setReadTimeout(timeout);
    return RestClient.builder().requestFactory(requestFactory);
  }
}
