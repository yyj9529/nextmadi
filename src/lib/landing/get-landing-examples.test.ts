import { describe, expect, mock, test } from "bun:test";

import type { FetchLike } from "./get-landing-examples";

mock.module("server-only", () => ({}));

const { getLandingExamples } = await import("./get-landing-examples");

describe("getLandingExamples", () => {
  test("returns the public examples and calls the endpoint without an auth token", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return Response.json(
        {
          examples: [
            { id: "ex-1", korean_text: "줄 새치기한 사람한테 한마디" },
            { id: "ex-2", korean_text: "환불 요청하고 싶어" },
            { id: "ex-3", korean_text: "이웃한테 조용히 해달라고" },
          ],
        },
        { status: 200 },
      );
    };

    const result = await getLandingExamples({
      backendBaseUrl: "http://backend.test",
      fetcher,
    });

    expect(result).toHaveLength(3);
    expect(result[0]).toEqual({ id: "ex-1", korean_text: "줄 새치기한 사람한테 한마디" });

    const request = calls[0];
    expect(new URL(request.url).pathname).toBe("/api/v1/landing/examples");
    expect(request.method).toBe("GET");
    expect(request.headers.get("x-internal-auth")).toBeNull();
  });

  test("caps the sample at 3 and drops malformed items", async () => {
    const fetcher: FetchLike = async () =>
      Response.json(
        {
          examples: [
            { id: "ex-1", korean_text: "하나" },
            { id: 42, korean_text: "숫자 id는 버린다" },
            { korean_text: "id 없음" },
            { id: "ex-2", korean_text: "둘" },
            { id: "ex-3", korean_text: "셋" },
            { id: "ex-4", korean_text: "넷 — 3개 초과라 무시" },
          ],
        },
        { status: 200 },
      );

    const result = await getLandingExamples({
      backendBaseUrl: "http://backend.test",
      fetcher,
    });

    expect(result.map((e) => e.id)).toEqual(["ex-1", "ex-2", "ex-3"]);
  });

  test("returns [] on a non-2xx response (examples section omitted silently)", async () => {
    const fetcher: FetchLike = async () => new Response("", { status: 404 });

    const result = await getLandingExamples({
      backendBaseUrl: "http://backend.test",
      fetcher,
    });

    expect(result).toEqual([]);
  });

  test("returns [] when the fetch throws (network error)", async () => {
    const fetcher: FetchLike = async () => {
      throw new Error("network down");
    };

    const result = await getLandingExamples({
      backendBaseUrl: "http://backend.test",
      fetcher,
    });

    expect(result).toEqual([]);
  });

  test("returns [] when the backend base url is not configured", async () => {
    const result = await getLandingExamples({ backendBaseUrl: undefined });
    expect(result).toEqual([]);
  });
});
