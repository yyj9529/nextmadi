import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./save-expression";

mock.module("server-only", () => ({}));

const { saveExpression, SaveExpressionError } = await import("./save-expression");

const SECRET = "nextjs-pending-save-internal-secret-0123456789";

function okExpression(analysisId: string, status = 201): Response {
  return Response.json(
    { id: "expr-1", analysis_request_id: analysisId, selected_variant_id: "v1" },
    { status },
  );
}

describe("saveExpression", () => {
  test("mints a user_id internal token and forwards the session_token claim", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return okExpression("analysis-1");
    };

    const result = await saveExpression(
      {
        userId: "user-1",
        analysisRequestId: "analysis-1",
        selectedVariantOrder: 2,
        claimSessionToken: "anon-session-xyz",
      },
      {
        backendBaseUrl: "http://backend.test",
        internalAuthSecret: SECRET,
        fetcher,
      },
    );

    expect(result.alreadySaved).toBe(false);
    expect(calls).toHaveLength(1);

    const request = calls[0];
    expect(request.url).toBe("http://backend.test/api/v1/expressions");
    expect(request.headers.get("idempotency-key")).toBeTruthy();

    // 내부 인증 토큰은 user_id를 담는다(session_token 아님).
    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
    expect(payload.session_token).toBeUndefined();

    // claim 토큰은 본문으로 전달된다.
    const body = await request.json();
    expect(body).toEqual({
      analysis_request_id: "analysis-1",
      selected_variant_order: 2,
      session_token: "anon-session-xyz",
    });
  });

  test("omits session_token from the body when no claim is needed", async () => {
    let captured: Request | null = null;
    const fetcher: FetchLike = async (request) => {
      captured = request;
      return okExpression("analysis-2");
    };

    await saveExpression(
      { userId: "user-2", analysisRequestId: "analysis-2" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    const body = await (captured as unknown as Request).json();
    expect(body).toEqual({ analysis_request_id: "analysis-2" });
  });

  test("treats 409 as already saved", async () => {
    const fetcher: FetchLike = async () => okExpression("analysis-3", 409);

    const result = await saveExpression(
      { userId: "user-3", analysisRequestId: "analysis-3" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    expect(result.alreadySaved).toBe(true);
  });

  test("surfaces a 404 claim failure as a not-found error", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "not_found" }, { status: 404 });

    let thrown: unknown;
    try {
      await saveExpression(
        {
          userId: "user-4",
          analysisRequestId: "analysis-4",
          claimSessionToken: "wrong-token",
        },
        { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
      );
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(SaveExpressionError);
    expect((thrown as InstanceType<typeof SaveExpressionError>).isNotFound).toBe(true);
  });
});
