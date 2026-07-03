package com.phraselog.practice;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.client.service.JsonSchemaValidator;
import org.junit.jupiter.api.Test;

/** Guards the {@code roleplay_result_v1} contract used by S12b result generation (#62). */
class RoleplayResultSchemaTests {

  private static final String SCHEMA_ID = "roleplay_result_v1";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final JsonSchemaValidator validator = new JsonSchemaValidator(objectMapper);

  @Test
  void acceptsExactlyThreeRecommendedExpressions() throws Exception {
    assertThatCode(() -> validator.validate(SCHEMA_ID, objectMapper.readTree(validResult())))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsTwoRecommendedExpressions() throws Exception {
    assertThatThrownBy(
            () -> validator.validate(SCHEMA_ID, objectMapper.readTree(twoRecommendations())))
        .isInstanceOf(JsonSchemaValidator.JsonSchemaValidationException.class);
  }

  @Test
  void rejectsMissingRecommendedExpressionField() throws Exception {
    assertThatThrownBy(
            () -> validator.validate(SCHEMA_ID, objectMapper.readTree(missingCulturalTip())))
        .isInstanceOf(JsonSchemaValidator.JsonSchemaValidationException.class);
  }

  @Test
  void rejectsExtraTopLevelFields() throws Exception {
    assertThatThrownBy(() -> validator.validate(SCHEMA_ID, objectMapper.readTree(extraField())))
        .isInstanceOf(JsonSchemaValidator.JsonSchemaValidationException.class);
  }

  private static String validResult() {
    return """
        {
          "recommended_expressions": [
            {
              "english": "Could you repeat that?",
              "tone_label": "polite",
              "ipa": "/a/",
              "korean_pronunciation": "could you",
              "pronunciation_tip": "Keep could short.",
              "cultural_tip": "Use this when you need clarification."
            },
            {
              "english": "I want to make sure I understood.",
              "tone_label": "careful",
              "ipa": "/b/",
              "korean_pronunciation": "I want",
              "pronunciation_tip": "Link want to.",
              "cultural_tip": "Useful in careful conversations."
            },
            {
              "english": "Can I say that back to you?",
              "tone_label": "confirming",
              "ipa": "/c/",
              "korean_pronunciation": "can I",
              "pronunciation_tip": "Raise can lightly.",
              "cultural_tip": "Natural before paraphrasing."
            }
          ],
          "awkward_pairs": [
            {
              "user_said": "I no understand",
              "natural_version": "I don't understand",
              "comment": "Use don't before the verb."
            }
          ],
          "pronunciation_focus_words": ["repeat"],
          "coach_encouragement": "오늘은 다시 물어보는 표현을 잘 연습했어요."
        }
        """;
  }

  private static String twoRecommendations() {
    return """
        {
          "recommended_expressions": [
            {
              "english": "Could you repeat that?",
              "tone_label": "polite",
              "ipa": "/a/",
              "korean_pronunciation": "could you",
              "pronunciation_tip": "Keep could short.",
              "cultural_tip": "Use this when you need clarification."
            },
            {
              "english": "I want to make sure I understood.",
              "tone_label": "careful",
              "ipa": "/b/",
              "korean_pronunciation": "I want",
              "pronunciation_tip": "Link want to.",
              "cultural_tip": "Useful in careful conversations."
            }
          ],
          "awkward_pairs": [],
          "pronunciation_focus_words": [],
          "coach_encouragement": "오늘은 다시 물어보는 표현을 잘 연습했어요."
        }
        """;
  }

  private static String missingCulturalTip() {
    return """
        {
          "recommended_expressions": [
            {
              "english": "Could you repeat that?",
              "tone_label": "polite",
              "ipa": "/a/",
              "korean_pronunciation": "could you",
              "pronunciation_tip": "Keep could short."
            },
            {
              "english": "I want to make sure I understood.",
              "tone_label": "careful",
              "ipa": "/b/",
              "korean_pronunciation": "I want",
              "pronunciation_tip": "Link want to.",
              "cultural_tip": "Useful in careful conversations."
            },
            {
              "english": "Can I say that back to you?",
              "tone_label": "confirming",
              "ipa": "/c/",
              "korean_pronunciation": "can I",
              "pronunciation_tip": "Raise can lightly.",
              "cultural_tip": "Natural before paraphrasing."
            }
          ],
          "awkward_pairs": [],
          "pronunciation_focus_words": [],
          "coach_encouragement": "오늘은 다시 물어보는 표현을 잘 연습했어요."
        }
        """;
  }

  private static String extraField() {
    return validResult()
        .replace("\"coach_encouragement\"", "\"unexpected\": true,\n  \"coach_encouragement\"");
  }
}
