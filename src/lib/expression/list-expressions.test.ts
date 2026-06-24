import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./list-expressions";

mock.module("server-only", () => ({}));

const { listExpressions, ListExpressionsError } = await import(
  "./list-expressions"
);

const SECRET = "nextjs-pending-save-internal-secret-0123456789";

function okList(): Response {
  return Response.json(
    {
      items: [
        {
          id: "expr-1",
          original_situation: "줄 새치기 상황",
          english_text: "Excuse me, I think there's a line.",
          tone_label: "정중한",
          created_at: "2026-06-12T07:00:00Z",
        },
      ],
      next_cursor: "cursor-2",
    },
    { status: 200 },
  );
}

describe("listExpressions", () => {
  test("mints a user_id internal token and forwards q/cursor/limit", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return okList();
    };

    const result = await listExpressions(
      { userId: "user-1", q: "서운", cursor: "cursor-1", limit: 20 },
      {
        backendBaseUrl: "http://backend.test",
        internalAuthSecret: SECRET,
        fetcher,
      },
    );

    expect(result.items).toHaveLength(1);
    expect(result.next_cursor).toBe("cursor-2");
    expect(calls).toHaveLength(1);

    const request = calls[0];
    const url = new URL(request.url);
    expect(url.pathname).toBe("/api/v1/expressions");
    expect(url.searchParams.get("q")).toBe("서운");
    expect(url.searchParams.get("cursor")).toBe("cursor-1");
    expect(url.searchParams.get("limit")).toBe("20");
    expect(request.method).toBe("GET");

    // 내부 인증 토큰은 user_id를 담는다.
    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
    expect(payload.session_token).toBeUndefined();
  });

  test("omits optional params when absent", async () => {
    let captured: Request | null = null;
    const fetcher: FetchLike = async (request) => {
      captured = request;
      return okList();
    };

    await listExpressions(
      { userId: "user-2" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    const url = new URL((captured as unknown as Request).url);
    expect(url.searchParams.has("q")).toBe(false);
    expect(url.searchParams.has("cursor")).toBe(false);
    expect(url.searchParams.has("limit")).toBe(false);
  });

  test("defaults next_cursor to null and items to empty", async () => {
    const fetcher: FetchLike = async () => Response.json({}, { status: 200 });

    const result = await listExpressions(
      { userId: "user-3" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    expect(result.items).toEqual([]);
    expect(result.next_cursor).toBeNull();
  });

  test("throws ListExpressionsError on non-2xx", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "boom" }, { status: 502 });

    let thrown: unknown;
    try {
      await listExpressions(
        { userId: "user-4" },
        {
          backendBaseUrl: "http://backend.test",
          internalAuthSecret: SECRET,
          fetcher,
        },
      );
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(ListExpressionsError);
    expect((thrown as InstanceType<typeof ListExpressionsError>).status).toBe(
      502,
    );
  });
});
