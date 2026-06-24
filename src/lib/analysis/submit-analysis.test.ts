import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./submit-analysis";

mock.module("server-only", () => ({}));

const { submitAnalysis, SubmitAnalysisError } = await import("./submit-analysis");

const SECRET = "nextjs-pending-save-internal-secret-0123456789";

function created(id: string): Response {
  return Response.json(
    {
      id,
      input_text: "줄 새치기한 사람한테 한마디 하고 싶었어요",
      variants: [
        { id: "v1", variant_order: 1, english_text: "Excuse me." },
        { id: "v2", variant_order: 2, english_text: "Hey." },
        { id: "v3", variant_order: 3, english_text: "Please move." },
      ],
      created_at: "2026-06-24T09:00:00Z",
    },
    { status: 201 },
  );
}

describe("submitAnalysis", () => {
  test("mints a user_id token, POSTs input_text, returns analysisRequestId", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return created("analysis-1");
    };

    const result = await submitAnalysis(
      { inputText: "친구한테 서운한 마음을 표현하고 싶어요", userId: "user-1" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    expect(result.analysisRequestId).toBe("analysis-1");
    expect(calls).toHaveLength(1);
    const request = calls[0];
    expect(request.method).toBe("POST");
    expect(request.url).toBe("http://backend.test/api/v1/analysis");
    expect(request.headers.get("idempotency-key")).toBeTruthy();
    // 인증 호출엔 IP를 싣지 않는다.
    expect(request.headers.get("x-client-ip")).toBeNull();

    const body = (await request.json()) as { input_text: string };
    expect(body.input_text).toBe("친구한테 서운한 마음을 표현하고 싶어요");

    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
    expect(payload.session_token).toBeUndefined();
  });

  test("uses session_token claim and forwards x-client-ip for anonymous calls", async () => {
    let captured: Request | null = null;
    const fetcher: FetchLike = async (request) => {
      captured = request;
      return created("analysis-2");
    };

    await submitAnalysis(
      {
        inputText: "마트에서 한마디 하고 싶었어요",
        sessionToken: "anon-session-xyz",
        clientIp: "203.0.113.7",
        landingExampleId: "example-9",
      },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    const request = captured as unknown as Request;
    expect(request.headers.get("x-client-ip")).toBe("203.0.113.7");

    const body = (await request.json()) as {
      input_text: string;
      landing_example_id?: string;
    };
    expect(body.landing_example_id).toBe("example-9");

    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.session_token).toBe("anon-session-xyz");
    expect(payload.user_id).toBeUndefined();
  });

  test("429 surfaces as a rate-limited SubmitAnalysisError", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "rate_limit_exceeded" }, { status: 429 });

    try {
      await submitAnalysis(
        { inputText: "테스트", sessionToken: "anon-1" },
        { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
      );
      throw new Error("expected submitAnalysis to throw");
    } catch (error) {
      expect(error).toBeInstanceOf(SubmitAnalysisError);
      expect((error as InstanceType<typeof SubmitAnalysisError>).isRateLimited).toBe(
        true,
      );
    }
  });

  test("rejects when both userId and sessionToken are given (XOR)", async () => {
    const fetcher: FetchLike = async () => created("x");
    await expect(
      submitAnalysis(
        { inputText: "테스트", userId: "user-1", sessionToken: "anon-1" },
        { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
      ),
    ).rejects.toThrow(/exactly one/);
  });
});
