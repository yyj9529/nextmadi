import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type {
  FetchLike,
  SaveRoleplayExpressionError as SaveRoleplayExpressionErrorType,
} from "./save-roleplay-expression";

mock.module("server-only", () => ({}));

const { saveRoleplayExpression, SaveRoleplayExpressionError } = await import(
  "./save-roleplay-expression"
);

const SECRET = "nextjs-pending-save-internal-secret-0123456789";
const OPTS = { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET };

describe("saveRoleplayExpression", () => {
  test("mints a user_id token, POSTs index + Idempotency-Key", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return Response.json({ id: "expr-1" }, { status: 201 });
    };

    const result = await saveRoleplayExpression(
      { userId: "user-1", sessionId: "session-1", recommendedExpressionIndex: 2 },
      { ...OPTS, fetcher, idempotencyKey: "key-1" },
    );

    expect(result).toEqual({ id: "expr-1", alreadySaved: false });

    const request = calls[0];
    expect(new URL(request.url).pathname).toBe(
      "/api/v1/practice/sessions/session-1/save-expression",
    );
    expect(request.method).toBe("POST");
    expect(request.headers.get("idempotency-key")).toBe("key-1");
    const body = (await request.json()) as { recommended_expression_index: number };
    expect(body.recommended_expression_index).toBe(2);

    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
  });

  test("treats 409 with an expression body as alreadySaved (idempotent success)", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ id: "expr-1" }, { status: 409 });

    const result = await saveRoleplayExpression(
      { userId: "user-1", sessionId: "session-1", recommendedExpressionIndex: 0 },
      { ...OPTS, fetcher },
    );

    expect(result).toEqual({ id: "expr-1", alreadySaved: true });
  });

  test("throws on 409 without an expression body (no cached result / not completed)", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "no_result" }, { status: 409 });

    let thrown: unknown;
    try {
      await saveRoleplayExpression(
        { userId: "user-1", sessionId: "session-1", recommendedExpressionIndex: 0 },
        { ...OPTS, fetcher },
      );
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeInstanceOf(SaveRoleplayExpressionError);
  });

  test("maps 404 to isNotFound", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "not_found" }, { status: 404 });

    let thrown: unknown;
    try {
      await saveRoleplayExpression(
        { userId: "user-1", sessionId: "gone", recommendedExpressionIndex: 0 },
        { ...OPTS, fetcher },
      );
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeInstanceOf(SaveRoleplayExpressionError);
    expect((thrown as SaveRoleplayExpressionErrorType).isNotFound).toBe(true);
  });
});
