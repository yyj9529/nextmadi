import { describe, expect, mock, test } from "bun:test";

import { withoutEnv } from "../testing/without-env";
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
    // 인자가 없으면 process.env로 폴백하므로, 미설정 경로는 그 변수를 지워야 검증된다.
    // fetcher는 비어 있지 않은 응답을 준다 — 앰비언트 env가 다시 새어 들어와 요청이 실제로
    // 나가면 결과가 []가 아니게 되어 테스트가 깨진다. 빈 응답이면 그 누출이 가려진다.
    let called = false;
    const fetcher: FetchLike = async () => {
      called = true;
      return Response.json(
        { examples: [{ id: "leaked", korean_text: "요청이 나갔다" }] },
        { status: 200 },
      );
    };

    await withoutEnv("PHRASELOG_BACKEND_BASE_URL", async () => {
      const result = await getLandingExamples({ fetcher });
      expect(result).toEqual([]);
      expect(called).toBe(false);
    });
  });
});
