import { describe, expect, test } from "bun:test";

import { isDefiniteTurnOutcome } from "./turn-outcome";

describe("isDefiniteTurnOutcome", () => {
  test("success and content rejections settle the logical turn", () => {
    for (const status of [200, 201, 400, 404, 409, 413, 422]) {
      expect(isDefiniteTurnOutcome(status)).toBe(true);
    }
  });

  // 서버가 이 요청을 어디까지 처리했는지 응답만으로는 알 수 없다. 특히 502는 BFF가 90초에 먼저
  // 포기한 경우를 포함하고, 그때 백엔드는 계속 돌아 턴을 커밋할 수 있다.
  test("gateway, timeout, rate-limit and server errors keep the key", () => {
    for (const status of [408, 429, 500, 502, 503, 504]) {
      expect(isDefiniteTurnOutcome(status)).toBe(false);
    }
  });
});
