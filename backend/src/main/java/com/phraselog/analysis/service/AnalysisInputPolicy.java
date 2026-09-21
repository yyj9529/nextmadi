package com.phraselog.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.core.io.ClassPathResource;

/** Shared lexical rules; uncertain meaning is handled by the generation response. */
public final class AnalysisInputPolicy {
  private static final JsonNode RULES = loadRules();

  private AnalysisInputPolicy() {}

  public static String check(String input) {
    String text = Normalizer.normalize(input, Normalizer.Form.NFC).strip();
    String letters = text.replaceAll("[^\\p{L}\\p{N}]", "");
    long jamo =
        letters
            .codePoints()
            .filter(c -> (c >= 0x3131 && c <= 0x3163) || (c >= 0x1100 && c <= 0x11ff))
            .count();
    if (letters.isEmpty()
        || (jamo >= 4 && (double) jamo / letters.length() > 0.5)
        || letters.matches("[ㄱ-ㅎㅏ-ㅣᄀ-ᇿ]+")) return "invalid";
    String lexical = text.toLowerCase(Locale.ROOT).replaceAll("[.!?。！？]+$", "").strip();
    for (JsonNode word : RULES.get("utterances")) {
      if (word.asText().equals(lexical)) return "ready";
    }
    for (JsonNode word : RULES.get("nouns")) {
      if (word.asText().equals(lexical)) return "choose_word_intent";
    }
    for (JsonNode ending : RULES.get("speech_endings")) {
      if (lexical.endsWith(ending.asText())) return "ready";
    }
    return lexical.matches("[a-z]+(?:[-'][a-z]+)*|[가-힣]+") ? "choose_word_intent" : "ready";
  }

  private static JsonNode loadRules() {
    try (var stream = new ClassPathResource("s07_input_policy.json").getInputStream()) {
      return new ObjectMapper().readTree(stream);
    } catch (IOException error) {
      throw new ExceptionInInitializerError(error);
    }
  }
}
