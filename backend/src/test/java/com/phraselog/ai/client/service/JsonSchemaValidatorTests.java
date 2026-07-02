package com.phraselog.ai.client.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonSchemaValidatorTests {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final JsonSchemaValidator validator = new JsonSchemaValidator(objectMapper);

  @Test
  void acceptsValidS07AnalysisResponseFromClasspathSchema() throws Exception {
    validator.validate("s07_analysis_v1", objectMapper.readTree(validS07Analysis()));
  }

  @Test
  void rejectsS07AnalysisResponseWithMissingRequiredVariantField() throws Exception {
    String missingEnglish =
        """
        {
          "expressions": [
            {
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

    assertThatThrownBy(
            () -> validator.validate("s07_analysis_v1", objectMapper.readTree(missingEnglish)))
        .isInstanceOf(JsonSchemaValidator.JsonSchemaValidationException.class)
        .hasRootCauseMessage("#/expressions/0: required key [english] not found");
  }

  @Test
  void acceptsValidRoleplayTurnResponse() throws Exception {
    validator.validate(
        "roleplay_turn_response_v1",
        objectMapper.readTree(
            """
            {
              "coach_utterance": "That sounds frustrating. What did you want to say next?"
            }
            """));
  }

  @Test
  void acceptsRoleplayFeedbackFalseWithoutExtraFields() throws Exception {
    validator.validate(
        "roleplay_turn_feedback_v1",
        objectMapper.readTree(
            """
            {
              "show_feedback": false
            }
            """));
  }

  @Test
  void acceptsValidRoleplayResultResponse() throws Exception {
    validator.validate(
        "roleplay_result_v1",
        objectMapper.readTree(
            """
            {
              "recommended_expressions": [
                {"english":"Could you repeat that?","tone_label":"polite","ipa":"/a/","korean_pronunciation":"could","pronunciation_tip":"short could","cultural_tip":"clarification"},
                {"english":"I want to make sure I understood.","tone_label":"careful","ipa":"/b/","korean_pronunciation":"want","pronunciation_tip":"link words","cultural_tip":"careful check"},
                {"english":"Can I say that back to you?","tone_label":"confirming","ipa":"/c/","korean_pronunciation":"can","pronunciation_tip":"light can","cultural_tip":"paraphrase"}
              ],
              "awkward_pairs": [],
              "pronunciation_focus_words": [],
              "coach_encouragement": "Nice work."
            }
            """));
  }

  @Test
  void rejectsRoleplayFeedbackTrueWithoutKoreanComment() throws Exception {
    assertThatThrownBy(
            () ->
                validator.validate(
                    "roleplay_turn_feedback_v1",
                    objectMapper.readTree(
                        """
                        {
                          "show_feedback": true,
                          "natural_alternative": "Could you repeat that?"
                        }
                        """)))
        .isInstanceOf(JsonSchemaValidator.JsonSchemaValidationException.class);
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
}
