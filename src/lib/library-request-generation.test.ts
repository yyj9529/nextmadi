import { describe, expect, test } from "bun:test";

import { isCurrentLibraryRequest } from "./library-request-generation";

describe("isCurrentLibraryRequest", () => {
  test("accepts the latest request generation", () => {
    expect(isCurrentLibraryRequest(3, 3)).toBe(true);
  });

  test("rejects stale request generations", () => {
    expect(isCurrentLibraryRequest(2, 3)).toBe(false);
  });
});
