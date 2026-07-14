import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./delete-expression";

mock.module("server-only", () => ({}));

const { deleteExpression, DeleteExpressionError } = await import(
  "./delete-expression"
);

const SECRET = "nextjs-pending-save-internal-secret-0123456789";

describe("deleteExpression", () => {
  test("mints a user_id internal token and DELETEs the detail path", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return new Response(null, { status: 204 });
    };

    await deleteExpression(
      { userId: "user-1", expressionId: "expr-1" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    const request = calls[0];
    const url = new URL(request.url);
    expect(url.pathname).toBe("/api/v1/expressions/expr-1");
    expect(request.method).toBe("DELETE");

    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
  });

  test("throws DeleteExpressionError with isNotFound on 404", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "not_found" }, { status: 404 });

    let thrown: unknown;
    try {
      await deleteExpression(
        { userId: "user-2", expressionId: "gone" },
        {
          backendBaseUrl: "http://backend.test",
          internalAuthSecret: SECRET,
          fetcher,
        },
      );
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(DeleteExpressionError);
    expect((thrown as InstanceType<typeof DeleteExpressionError>).isNotFound).toBe(
      true,
    );
  });

  test("throws DeleteExpressionError on other non-2xx", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "boom" }, { status: 502 });

    let thrown: unknown;
    try {
      await deleteExpression(
        { userId: "user-3", expressionId: "expr-3" },
        {
          backendBaseUrl: "http://backend.test",
          internalAuthSecret: SECRET,
          fetcher,
        },
      );
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(DeleteExpressionError);
    expect((thrown as InstanceType<typeof DeleteExpressionError>).isNotFound).toBe(
      false,
    );
  });
});
