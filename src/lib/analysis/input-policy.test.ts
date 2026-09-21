import { describe, expect, test } from "bun:test";
import { checkAnalysisInput } from "./input-policy";
import { AnalysisOperation } from "./client-request";

describe("input preflight", () => {
  for (const text of ["물 주세요", "물주세요", "고마워", "나 집주인 히터 고장 말해", "Thanks!", "Help!", "안녕", "I can't help this week."]) {
    test(`clear intent: ${text}`, () => expect(checkAnalysisInput(text)).toBe("ready"));
  }
  for (const text of ["집주인", "landlord", "보증금", "security deposit", "수도꼭지", "abacus"]) {
    test(`choose intent: ${text}`, () => expect(checkAnalysisInput(text)).toBe("choose_word_intent"));
  }
  for (const text of ["ㅁㅈㅇㅁㅇㄴㅁㅇ추더러", "ㄱㅂㅅ", "???", "   "]) {
    test(`meaningless: ${text}`, () => expect(checkAnalysisInput(text)).toBe("invalid"));
  }
  test("network retries keep a key, edits start a new operation", () => {
    const operation = new AnalysisOperation();
    const first = operation.current();
    expect(operation.current()).toBe(first);
    operation.reset();
    expect(operation.current()).not.toBe(first);
  });
});
