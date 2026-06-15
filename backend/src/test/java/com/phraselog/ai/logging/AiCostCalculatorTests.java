package com.phraselog.ai.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AiCostCalculatorTests {

  private final AiCostCalculator calculator = new AiCostCalculator();

  @Test
  void sonnetCostMatchesAiPipelineExample() {
    // AI_PIPELINE.md S07 analysis estimate: ~900 input + ~270 output tokens = $0.006750.
    assertThat(calculator.llm("claude-sonnet-4-6", 900, 270)).isEqualByComparingTo("0.006750");
  }

  @Test
  void haikuCostMatchesAiPipelineExample() {
    // AI_PIPELINE.md roleplay_turn_feedback estimate: ~3000 input + ~500 output = $0.005500.
    assertThat(calculator.llm("claude-haiku-4-5", 3000, 500)).isEqualByComparingTo("0.005500");
  }

  @Test
  void llmResolvesModelWithDatedSuffix() {
    assertThat(calculator.llm("claude-haiku-4-5-20251001", 3000, 500))
        .isEqualByComparingTo("0.005500");
  }

  @Test
  void llmReturnsNullForUnknownModel() {
    assertThat(calculator.llm("gpt-4o", 1000, 1000)).isNull();
  }

  @Test
  void llmResolvesExactKnownModelIds() {
    // Guards against a future shorter prefix shadowing a more specific model id.
    assertThat(calculator.llm("claude-sonnet-4-6", 1_000_000, 0)).isEqualByComparingTo("3.000000");
    assertThat(calculator.llm("claude-haiku-4-5", 1_000_000, 0)).isEqualByComparingTo("1.000000");
  }

  @Test
  void whisperCostMatchesAiPipelineExample() {
    // AI_PIPELINE.md: 30-second utterance at $0.006/min = $0.003000.
    assertThat(calculator.whisperBySeconds(30)).isEqualByComparingTo("0.003000");
  }

  @Test
  void ttsCostMatchesAiPipelineExample() {
    // AI_PIPELINE.md: 80-character utterance at $15/1M chars = $0.001200.
    assertThat(calculator.ttsByCharacters(80)).isEqualByComparingTo("0.001200");
  }

  @Test
  void costIsScaledToSixDecimalsForNumeric10x6() {
    assertThat(calculator.ttsByCharacters(80).scale()).isEqualTo(6);
  }

  @Test
  void rejectsNegativeTokens() {
    assertThatThrownBy(() -> calculator.llm("claude-sonnet-4-6", -1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
