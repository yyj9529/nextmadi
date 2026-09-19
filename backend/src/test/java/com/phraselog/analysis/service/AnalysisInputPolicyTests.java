package com.phraselog.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnalysisInputPolicyTests {
  @Test
  void keepsShortAndImperfectUtterances() {
    for (String text :
        new String[] {"물 주세요", "물주세요", "고마워", "나 집주인 히터 고장 말해", "Thanks!", "Help!", "안녕"}) {
      assertThat(AnalysisInputPolicy.check(text)).as(text).isEqualTo("ready");
    }
  }

  @Test
  void wordsNeedAChoice() {
    for (String text : new String[] {"집주인", "landlord", "security deposit", "수도꼭지", "abacus"}) {
      assertThat(AnalysisInputPolicy.check(text)).as(text).isEqualTo("choose_word_intent");
    }
  }

  @Test
  void meaninglessInputNeverNeedsAnAiCall() {
    for (String text : new String[] {"ㅁㅈㅇㅁㅇㄴㅁㅇ추더러", "ㄱㅂㅅ", "???", "   "}) {
      assertThat(AnalysisInputPolicy.check(text)).as(text).isEqualTo("invalid");
    }
  }
}
