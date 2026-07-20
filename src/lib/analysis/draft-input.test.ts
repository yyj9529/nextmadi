import { afterEach, beforeEach, describe, expect, test } from "bun:test";

import {
  DRAFT_INPUT_KEY,
  DRAFT_TTL_MS,
  clearDraftInput,
  parseDraftInput,
  readDraftInput,
  writeDraftInput,
} from "./draft-input";

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

function rawDraft(): string | null {
  return (
    globalThis as unknown as {
      window: { sessionStorage: { getItem(key: string): string | null } };
    }
  ).window.sessionStorage.getItem(DRAFT_INPUT_KEY);
}

beforeEach(() => {
  (globalThis as { window?: unknown }).window = {
    sessionStorage: new MemoryStorage(),
  };
});

afterEach(() => {
  delete (globalThis as { window?: unknown }).window;
});

describe("draft input persistence", () => {
  test("write then read within TTL round-trips the text", () => {
    writeDraftInput("줄 새치기한 사람한테 한마디 하고 싶었어요", 1000);
    expect(readDraftInput(1000)).toBe("줄 새치기한 사람한테 한마디 하고 싶었어요");
  });

  test("serializes text with a saved_at timestamp", () => {
    writeDraftInput("hello", 4242);
    expect(JSON.parse(rawDraft() as string)).toEqual({
      text: "hello",
      saved_at: 4242,
    });
  });

  test("returns text at exactly the TTL boundary", () => {
    writeDraftInput("edge", 0);
    expect(readDraftInput(DRAFT_TTL_MS)).toBe("edge");
  });

  test("drops and clears a draft older than the 5-minute TTL", () => {
    writeDraftInput("stale", 0);
    expect(readDraftInput(DRAFT_TTL_MS + 1)).toBeNull();
    // stale 초안은 읽는 시점에 제거되어 이후 읽기도 null이다.
    expect(rawDraft()).toBeNull();
  });

  test("writing an empty string removes any existing draft", () => {
    writeDraftInput("something", 1000);
    writeDraftInput("", 2000);
    expect(rawDraft()).toBeNull();
    expect(readDraftInput(2000)).toBeNull();
  });

  test("clearDraftInput removes the stored draft", () => {
    writeDraftInput("to be cleared", 1000);
    clearDraftInput();
    expect(readDraftInput(1000)).toBeNull();
  });

  test("read returns null when nothing is stored", () => {
    expect(readDraftInput(1000)).toBeNull();
  });

  test("parseDraftInput rejects malformed payloads", () => {
    expect(parseDraftInput("not json")).toBeNull();
    expect(parseDraftInput("null")).toBeNull();
    expect(parseDraftInput(JSON.stringify({ text: "", saved_at: 1 }))).toBeNull();
    expect(parseDraftInput(JSON.stringify({ text: "x" }))).toBeNull();
    expect(
      parseDraftInput(JSON.stringify({ text: "x", saved_at: "nope" })),
    ).toBeNull();
  });

  test("no window (SSR/test) degrades to no-op and null", () => {
    delete (globalThis as { window?: unknown }).window;
    expect(() => writeDraftInput("x", 1)).not.toThrow();
    expect(readDraftInput(1)).toBeNull();
    expect(() => clearDraftInput()).not.toThrow();
  });
});
