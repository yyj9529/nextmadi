import { afterEach, beforeEach, describe, expect, test } from "bun:test";

import {
  PENDING_SAVE_KEY,
  clearPendingSave,
  parsePendingSave,
  readPendingSave,
  resolvePostAuthDestination,
  resolvePostOnboardingDestination,
  writePendingSave,
} from "./pending-save";

// bun test 환경에는 DOM이 없으므로 sessionStorage를 메모리로 폴리필한다.
class MemoryStorage {
  private map = new Map<string, string>();
  getItem(key: string): string | null {
    return this.map.has(key) ? (this.map.get(key) as string) : null;
  }
  setItem(key: string, value: string): void {
    this.map.set(key, value);
  }
  removeItem(key: string): void {
    this.map.delete(key);
  }
}

beforeEach(() => {
  (globalThis as { window?: unknown }).window = {
    sessionStorage: new MemoryStorage(),
  };
});

afterEach(() => {
  delete (globalThis as { window?: unknown }).window;
});

describe("pending_save serialization", () => {
  test("write then read round-trips the typed value", () => {
    writePendingSave({
      analysisRequestId: "a1b2",
      selectedVariantOrder: 2,
    });

    expect(readPendingSave()).toEqual({
      analysisRequestId: "a1b2",
      selectedVariantOrder: 2,
    });
  });

  test("serializes to the snake_case API contract", () => {
    writePendingSave({ analysisRequestId: "a1b2", selectedVariantOrder: 1 });

    const raw = (
      globalThis as unknown as {
        window: { sessionStorage: { getItem(key: string): string | null } };
      }
    ).window.sessionStorage.getItem(PENDING_SAVE_KEY);
    expect(JSON.parse(raw as string)).toEqual({
      analysis_request_id: "a1b2",
      selected_variant_order: 1,
    });
  });

  test("clear removes the stored value", () => {
    writePendingSave({ analysisRequestId: "a1b2", selectedVariantOrder: 1 });
    clearPendingSave();
    expect(readPendingSave()).toBeNull();
  });

  test("read returns null when nothing is stored", () => {
    expect(readPendingSave()).toBeNull();
  });
});

describe("parsePendingSave validation", () => {
  test("rejects malformed JSON", () => {
    expect(parsePendingSave("{not json")).toBeNull();
  });

  test("rejects a missing analysis id", () => {
    expect(parsePendingSave(JSON.stringify({ selected_variant_order: 1 }))).toBeNull();
  });

  test("rejects an out-of-range variant order", () => {
    expect(
      parsePendingSave(
        JSON.stringify({ analysis_request_id: "x", selected_variant_order: 9 }),
      ),
    ).toBeNull();
  });

  test("accepts a valid contract blob", () => {
    expect(
      parsePendingSave(
        JSON.stringify({ analysis_request_id: "x", selected_variant_order: 3 }),
      ),
    ).toEqual({ analysisRequestId: "x", selectedVariantOrder: 3 });
  });
});

describe("resolvePostAuthDestination", () => {
  test("not onboarded goes to coach selection first", () => {
    expect(
      resolvePostAuthDestination({
        isOnboarded: false,
        pendingSave: { analysisRequestId: "a1", selectedVariantOrder: 1 },
      }),
    ).toEqual({ path: "/welcome/coach", resume: false });
  });

  test("onboarded with a pending save returns to the S07 saved state", () => {
    expect(
      resolvePostAuthDestination({
        isOnboarded: true,
        pendingSave: { analysisRequestId: "a1", selectedVariantOrder: 1 },
      }),
    ).toEqual({ path: "/save/result/a1", resume: true });
  });

  test("onboarded with no pending save goes home", () => {
    expect(
      resolvePostAuthDestination({ isOnboarded: true, pendingSave: null }),
    ).toEqual({ path: "/home", resume: false });
  });
});

describe("resolvePostOnboardingDestination", () => {
  test("after coach selection with a pending save returns to the S07 saved state", () => {
    expect(
      resolvePostOnboardingDestination({
        analysisRequestId: "a1",
        selectedVariantOrder: 1,
      }),
    ).toEqual({ path: "/save/result/a1", resume: true });
  });

  test("after coach selection with no pending save goes home", () => {
    expect(resolvePostOnboardingDestination(null)).toEqual({
      path: "/home",
      resume: false,
    });
  });
});
