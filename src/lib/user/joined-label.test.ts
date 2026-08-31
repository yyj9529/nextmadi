import { describe, expect, test } from "bun:test";

import { formatJoinedLabel } from "./joined-label";

// 이 테스트는 실행 환경의 로컬 시간대에 의존한다. 시간대에 무관하게 참인 성질만 검증한다.
describe("formatJoinedLabel", () => {
  test("formats as YYYY.MM.DD with zero padding", () => {
    expect(formatJoinedLabel("2026-06-21T12:00:00+09:00")).toMatch(
      /^\d{4}\.\d{2}\.\d{2}$/,
    );
  });

  test("returns an empty string for an unparseable value", () => {
    expect(formatJoinedLabel("not-a-date")).toBe("");
    expect(formatJoinedLabel("")).toBe("");
  });

  test("resolves the same instant identically regardless of the offset it is written with", () => {
    // 같은 시점을 +09:00과 Z로 표기한 두 문자열 — 로컬 시간대가 무엇이든 결과가 같아야 한다.
    // getUTC*로 포맷하던 버그(하루 밀림)는 이 성질을 만족해도 통과하므로, 아래 경계 테스트가 잡는다.
    expect(formatJoinedLabel("2026-06-21T05:00:00+09:00")).toBe(
      formatJoinedLabel("2026-06-20T20:00:00Z"),
    );
  });

  test("uses the local calendar day, not the UTC one", () => {
    // UTC 기준 6/20 20:00 = KST 6/21 05:00. 로컬 시간대에 따라 답이 갈리므로,
    // '로컬 getter로 계산했는가'만 확인한다(UTC 하드코딩이면 이 등식이 깨진다).
    const iso = "2026-06-20T20:00:00Z";
    const local = new Date(iso);
    const expected = `${local.getFullYear()}.${String(
      local.getMonth() + 1,
    ).padStart(2, "0")}.${String(local.getDate()).padStart(2, "0")}`;

    expect(formatJoinedLabel(iso)).toBe(expected);
  });
});
