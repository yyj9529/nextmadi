package com.phraselog.ai.client.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.client.dto.AnthropicMessage;
import com.phraselog.ai.client.dto.AnthropicResponse;
import com.phraselog.ai.logging.dto.AiErrorCode;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local-only stub for {@link AnthropicClient} that returns canned, schema-valid responses instead
 * of calling the paid Anthropic API. Lets the full analysis / roleplay wiring (schema validation,
 * persistence, GET) be exercised on localhost at zero cost.
 *
 * <p>Activated ONLY when {@code anthropic.mock.enabled=true} (set in {@code
 * application-local.yml}). In prod/CI the property is absent, so {@link RestClientAnthropicClient}
 * is wired instead and this bean does not exist. The two are mutually exclusive via {@link
 * ConditionalOnProperty}.
 *
 * <p>The stub is schema-blind at this seam (it only receives model + messages), so it picks the
 * canned payload by matching a stable marker phrase from each prompt body, falling back to the S07
 * analysis payload. This detection is intentionally simple because it never ships to prod.
 */
@Component
@ConditionalOnProperty(name = "anthropic.mock.enabled", havingValue = "true")
public class MockAnthropicClient implements AnthropicClient {

  private static final Logger log = LoggerFactory.getLogger(MockAnthropicClient.class);

  private final ObjectMapper objectMapper;

  public MockAnthropicClient(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    log.warn(
        "MockAnthropicClient ACTIVE (anthropic.mock.enabled=true) — Claude calls return canned "
            + "responses, NO real API cost. Do not use this profile in prod/CI.");
  }

  @Override
  public AnthropicResponse sendMessage(
      String modelId, AnthropicMessage[] messages, Duration timeout)
      throws AnthropicClientException {
    String prompt = joinContent(messages);
    String payload = cannedPayloadFor(prompt);
    try {
      // No usage: the mock bills nothing, and inventing token counts would put fabricated cost
      // figures in ai_request_logs. Null here is the honest value — and it is precisely why the
      // real client's dropped usage stayed invisible locally until a keyed call was made (#107).
      return AnthropicResponse.withoutUsage(objectMapper.readTree(payload));
    } catch (Exception e) {
      // Should never happen — the canned strings are compile-time constants.
      throw new AnthropicClientException(
          "Mock payload parse failure: " + e.getMessage(), AiErrorCode.UNKNOWN, false, e);
    }
  }

  private static String joinContent(AnthropicMessage[] messages) {
    StringBuilder sb = new StringBuilder();
    if (messages != null) {
      for (AnthropicMessage m : messages) {
        if (m != null && m.content() != null) {
          sb.append(m.content()).append('\n');
        }
      }
    }
    return sb.toString();
  }

  /** Picks a schema-valid canned payload by matching a stable marker from each prompt body. */
  private static String cannedPayloadFor(String prompt) {
    if (prompt.contains("You start a PhraseLog guided roleplay session")) {
      return ROLEPLAY_SESSION_INIT;
    }
    if (prompt.contains("selected PhraseLog coach inside an active roleplay session")) {
      return ROLEPLAY_TURN_RESPONSE;
    }
    if (prompt.contains("whether to show a brief feedback card")) {
      return ROLEPLAY_TURN_FEEDBACK;
    }
    if (prompt.contains("final summary for a completed PhraseLog")) {
      return ROLEPLAY_RESULT;
    }
    // Default: S07 analysis (the primary local-verification target).
    if (prompt.contains("PhraseLog S07 communication coach")) {
      return S07_ANALYSIS.replaceFirst(
          "\\{",
          "{\"result_type\":\"expressions\","
              + "\"assessment\":{\"verdict\":\"suggestion\",\"summary\":\"[MOCK] 분석 예시\","
              + "\"reason\":\"[MOCK] 실제 AI 평가가 아닌 화면 확인용 응답이에요.\"},");
    }
    return S07_ANALYSIS;
  }

  private static final String S07_ANALYSIS =
      """
      {
        "expressions": [
          {
            "english": "[MOCK] Could we push our meeting to tomorrow?",
            "tone_label": "정중한 요청",
            "ipa": "kʊd wi pʊʃ aʊər ˈmiːtɪŋ tə təˈmɑːroʊ",
            "korean_pronunciation": "쿠드 위 푸시 아워 미팅 투 투모로우",
            "pronunciation_tip": "[MOCK] 'push'의 sh 소리를 부드럽게, 끝을 올려 질문 톤으로.",
            "cultural_tip": "[MOCK] 미국 직장에서 회의 조정은 이유를 짧게 붙이면 더 자연스럽다."
          },
          {
            "english": "[MOCK] Any chance we can reschedule for tomorrow?",
            "tone_label": "캐주얼",
            "ipa": "ˈɛni tʃæns wi kən ˌriːˈʃɛdʒuːl fər təˈmɑːroʊ",
            "korean_pronunciation": "애니 챈스 위 캔 리스케줄 포 투모로우",
            "pronunciation_tip": "[MOCK] 'reschedule'은 강세가 'sche'에 온다.",
            "cultural_tip": "[MOCK] 동료 사이에서 가볍게 쓰기 좋은 표현."
          },
          {
            "english": "[MOCK] I need to move our meeting to tomorrow.",
            "tone_label": "단호함",
            "ipa": "aɪ niːd tə muːv aʊər ˈmiːtɪŋ tə təˈmɑːroʊ",
            "korean_pronunciation": "아이 니드 투 무브 아워 미팅 투 투모로우",
            "pronunciation_tip": "[MOCK] 'need to'를 'needa'처럼 이어서 발음.",
            "cultural_tip": "[MOCK] 결정이 이미 내려졌을 때 쓰는 직접적 표현."
          }
        ]
      }
      """;

  private static final String ROLEPLAY_SESSION_INIT =
      """
      {
        "planned_turns": 5,
        "scenario_setup": "[MOCK] You are at a coffee shop and your order came out wrong.",
        "complexity_rationale": "[MOCK] Short, low-stakes exchange suited to a single saved expression."
      }
      """;

  private static final String ROLEPLAY_TURN_RESPONSE =
      """
      {
        "coach_utterance": "[MOCK] Sure, no problem — what can I get started for you?"
      }
      """;

  private static final String ROLEPLAY_TURN_FEEDBACK =
      """
      {
        "show_feedback": false
      }
      """;

  private static final String ROLEPLAY_RESULT =
      """
      {
        "recommended_expressions": [
          {
            "english": "[MOCK] I think there's a mix-up with my order.",
            "tone_label": "정중함",
            "ipa": "aɪ θɪŋk ðɛrz ə ˈmɪksˌʌp wɪð maɪ ˈɔːrdər",
            "korean_pronunciation": "아이 씽크 데어즈 어 믹스업 위드 마이 오더",
            "pronunciation_tip": "[MOCK] 'mix-up'의 강세는 앞음절.",
            "cultural_tip": "[MOCK] 상대를 탓하지 않는 표현이라 서비스 상황에 안전하다."
          },
          {
            "english": "[MOCK] This isn't what I ordered.",
            "tone_label": "직접적",
            "ipa": "ðɪs ˈɪzənt wʌt aɪ ˈɔːrdərd",
            "korean_pronunciation": "디스 이즌트 왓 아이 오더드",
            "pronunciation_tip": "[MOCK] 'isn't'를 축약해 자연스럽게.",
            "cultural_tip": "[MOCK] 분명히 알릴 때 쓰지만 어조를 부드럽게 유지."
          },
          {
            "english": "[MOCK] Could you double-check my order?",
            "tone_label": "협조적",
            "ipa": "kʊd juː ˈdʌbəl tʃɛk maɪ ˈɔːrdər",
            "korean_pronunciation": "쿠드 유 더블체크 마이 오더",
            "pronunciation_tip": "[MOCK] 'double-check'를 한 단어처럼.",
            "cultural_tip": "[MOCK] 직원과 함께 확인하자는 뉘앙스로 마찰이 적다."
          }
        ],
        "awkward_pairs": [
          {
            "user_said": "[MOCK] This is wrong order.",
            "natural_version": "[MOCK] This is the wrong order.",
            "comment": "[MOCK] 관사 'the'가 빠졌어요."
          }
        ],
        "pronunciation_focus_words": ["order", "mix-up"],
        "coach_encouragement": "[MOCK] 오늘 상황에서 필요한 표현을 잘 연습했어요. 다음엔 더 자신 있게!"
      }
      """;
}
