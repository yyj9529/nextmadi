package com.phraselog.ai.client;

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
