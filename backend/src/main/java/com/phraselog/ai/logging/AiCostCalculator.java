package com.phraselog.ai.logging;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Computes {@code estimated_cost_usd} for an AI/STT/TTS call at log time.
 *
 * <p>Rates are the planning-grade figures from {@code docs/AI_PIPELINE.md} ("Cost and latency
 * expectations", verified 2026-05-23). That table flags the OpenAI legacy {@code whisper-1} /
 * {@code tts-1} numbers as planning-grade and asks for re-verification from the provider billing
 * dashboard before/at W4. Treat these constants as subject to that re-verification, not as
 * production-fixed pricing.
 *
 * <p>All results are scaled to 6 decimal places (HALF_UP) to fit {@code NUMERIC(10,6)}.
 */
@Component
public class AiCostCalculator {

  private static final Logger log = LoggerFactory.getLogger(AiCostCalculator.class);

  private static final int USD_SCALE = 6;
  private static final BigDecimal TOKENS_PER_MILLION = new BigDecimal("1000000");
  private static final BigDecimal SECONDS_PER_MINUTE = new BigDecimal("60");
  private static final BigDecimal CHARS_PER_MILLION = new BigDecimal("1000000");

  /** OpenAI Whisper-1: $0.006 per minute of audio (planning-grade, 2026-05-23). */
  private static final BigDecimal WHISPER_USD_PER_MINUTE = new BigDecimal("0.006");

  /** OpenAI TTS-1 standard: $15.00 per 1,000,000 characters (planning-grade, 2026-05-23). */
  private static final BigDecimal TTS1_USD_PER_MILLION_CHARS = new BigDecimal("15.00");

  /** Per-MTok LLM rates keyed by model-id prefix so dated suffixes still resolve. */
  private enum LlmRate {
    SONNET_4_6("claude-sonnet-4-6", new BigDecimal("3.00"), new BigDecimal("15.00")),
    HAIKU_4_5("claude-haiku-4-5", new BigDecimal("1.00"), new BigDecimal("5.00"));

    private final String modelPrefix;
    private final BigDecimal inputUsdPerMillion;
    private final BigDecimal outputUsdPerMillion;

    LlmRate(String modelPrefix, BigDecimal inputUsdPerMillion, BigDecimal outputUsdPerMillion) {
      this.modelPrefix = modelPrefix;
      this.inputUsdPerMillion = inputUsdPerMillion;
      this.outputUsdPerMillion = outputUsdPerMillion;
    }

    static LlmRate resolve(String modelName) {
      if (modelName == null) {
        return null;
      }
      // Longest-prefix wins, so a future shorter prefix declared earlier (e.g. "claude-sonnet-4"
      // vs "claude-sonnet-4-6") can never shadow a more specific model and mis-price it.
      LlmRate best = null;
      for (LlmRate rate : values()) {
        if (modelName.startsWith(rate.modelPrefix)
            && (best == null || rate.modelPrefix.length() > best.modelPrefix.length())) {
          best = rate;
        }
      }
      return best;
    }
  }

  /**
   * Cost of one Claude call. Returns {@code null} (and logs a warning) for an unknown model id so
   * an unrecognised model never silently logs $0 — the column is nullable for that case.
   */
  public BigDecimal llm(String modelName, long inputTokens, long outputTokens) {
    requireNonNegative(inputTokens, "inputTokens");
    requireNonNegative(outputTokens, "outputTokens");
    LlmRate rate = LlmRate.resolve(modelName);
    if (rate == null) {
      log.warn("No pricing for model '{}'; estimated_cost_usd will be null", modelName);
      return null;
    }
    BigDecimal inputCost = rate.inputUsdPerMillion.multiply(BigDecimal.valueOf(inputTokens));
    BigDecimal outputCost = rate.outputUsdPerMillion.multiply(BigDecimal.valueOf(outputTokens));
    return inputCost.add(outputCost).divide(TOKENS_PER_MILLION, USD_SCALE, RoundingMode.HALF_UP);
  }

  /** Cost of one Whisper transcription, billed per minute of audio. */
  public BigDecimal whisperBySeconds(double audioSeconds) {
    if (audioSeconds < 0) {
      throw new IllegalArgumentException("audioSeconds must be >= 0");
    }
    return WHISPER_USD_PER_MINUTE
        .multiply(BigDecimal.valueOf(audioSeconds))
        .divide(SECONDS_PER_MINUTE, USD_SCALE, RoundingMode.HALF_UP);
  }

  /** Cost of one TTS-1 standard synthesis, billed per character of input text. */
  public BigDecimal ttsByCharacters(long characterCount) {
    requireNonNegative(characterCount, "characterCount");
    return TTS1_USD_PER_MILLION_CHARS
        .multiply(BigDecimal.valueOf(characterCount))
        .divide(CHARS_PER_MILLION, USD_SCALE, RoundingMode.HALF_UP);
  }

  private static void requireNonNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must be >= 0");
    }
  }
}
