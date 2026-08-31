import { describe, expect, test } from "bun:test";

import { MAX_NICKNAME_LENGTH, validateNickname } from "./nickname";

describe("validateNickname", () => {
  test("accepts a normal nickname and trims surrounding whitespace", () => {
    expect(validateNickname("  지영  ")).toEqual({ ok: true, value: "지영" });
  });

  test("rejects an empty value (#56 결정: 빈 닉네임 금지)", () => {
    expect(validateNickname("")).toEqual({ ok: false, reason: "empty" });
  });

  test("rejects a whitespace-only value", () => {
    expect(validateNickname("   ")).toEqual({ ok: false, reason: "empty" });
  });

  test("accepts exactly the maximum length", () => {
    const value = "가".repeat(MAX_NICKNAME_LENGTH);
    expect(validateNickname(value)).toEqual({ ok: true, value });
  });

  test("rejects one character over the maximum", () => {
    expect(validateNickname("가".repeat(MAX_NICKNAME_LENGTH + 1))).toEqual({
      ok: false,
      reason: "too_long",
    });
  });

  test("counts length after trimming, not before", () => {
    const padded = ` ${"가".repeat(MAX_NICKNAME_LENGTH)} `;
    expect(validateNickname(padded).ok).toBe(true);
  });
});
