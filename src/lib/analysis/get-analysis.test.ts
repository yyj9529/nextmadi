import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./get-analysis";

mock.module("server-only", () => ({}));

const { getAnalysis, AnalysisNotFoundError } = await import("./get-analysis");

const SECRET = "nextjs-pending-save-internal-secret-0123456789";

function okAnalysis(id: string): Response {
  return Response.json(
    {
      id,
      input_text: "줄 새치기한 사람한테 한마디 하고 싶었어요",
      variants: [
        { id: "v1", variant_order: 1, english_text: "Excuse me." },
        { id: "v2", variant_order: 2, english_text: "Hey." },
        { id: "v3", variant_order: 3, english_text: "Please move." },
      ],
      created_at: "2026-06-22T09:00:00Z",
    },
    { status: 200 },
  );
}

describe("getAnalysis", () => {
  test("mints a user_id internal token and GETs the analysis", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return okAnalysis("analysis-1");
    };

    const result = await getAnalysis(
      { analysisRequestId: "analysis-1", userId: "user-1" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    expect(result.variants).toHaveLength(3);
    expect(calls).toHaveLength(1);
    const request = calls[0];
    expect(request.method).toBe("GET");
    expect(request.url).toBe("http://backend.test/api/v1/analysis/analysis-1");

    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
    expect(payload.session_token).toBeUndefined();
  });

  test("uses the session_token claim for pre-signup access", async () => {
    let captured: Request | null = null;
    const fetcher: FetchLike = async (request) => {
      captured = request;
      return okAnalysis("analysis-2");
    };

    await getAnalysis(
      { analysisRequestId: "analysis-2", sessionToken: "anon-xyz" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    const token = (captured as unknown as Request).headers.get(
      "x-internal-auth",
    ) as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.session_token).toBe("anon-xyz");
    expect(payload.user_id).toBeUndefined();
  });

  test("throws AnalysisNotFoundError on 404", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "not_found" }, { status: 404 });

    let thrown: unknown;
    try {
      await getAnalysis(
        { analysisRequestId: "missing", userId: "user-1" },
        { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
      );
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeInstanceOf(AnalysisNotFoundError);
  });

  test("rejects when both userId and sessionToken are given (XOR)", async () => {
    const fetcher: FetchLike = async () => okAnalysis("x");
    await expect(
      getAnalysis(
        { analysisRequestId: "x", userId: "u", sessionToken: "s" },
        {
          backendBaseUrl: "http://backend.test",
          internalAuthSecret: SECRET,
          fetcher,
        },
      ),
    ).rejects.toThrow();
  });

  test("url-encodes the analysis id", async () => {
    let captured: Request | null = null;
    const fetcher: FetchLike = async (request) => {
      captured = request;
      return okAnalysis("a/b");
    };
    await getAnalysis(
      { analysisRequestId: "a/b", userId: "user-1" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );
    expect((captured as unknown as Request).url).toBe(
      "http://backend.test/api/v1/analysis/a%2Fb",
    );
  });
});
