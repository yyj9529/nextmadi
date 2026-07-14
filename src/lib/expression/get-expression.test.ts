import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./get-expression";

mock.module("server-only", () => ({}));

const { getExpression, ExpressionNotFoundError } = await import(
  "./get-expression"
);

const SECRET = "nextjs-pending-save-internal-secret-0123456789";

function okDetail(overrides: Record<string, unknown> = {}): Response {
  return Response.json(
    {
      id: "expr-1",
      source_type: "analysis",
      original_situation: "줄 새치기 상황",
      selected_variant_id: "variant-1",
      variants: [
        {
          id: "variant-1",
          variant_order: 1,
          tone_label: "정중한",
          english_text: "Excuse me, I think there's a line.",
          ipa: "/.../",
          korean_pronunciation: "익스큐즈 미",
          pronunciation_tip: null,
          cultural_tip: null,
          tts_audio_url: null,
        },
      ],
      review_card_id: "card-1",
      next_review_at: "2026-06-15T09:00:00Z",
      created_at: "2026-06-12T09:00:00Z",
      ...overrides,
    },
    { status: 200 },
  );
}

describe("getExpression", () => {
  test("mints a user_id internal token and GETs the detail path", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return okDetail();
    };

    const result = await getExpression(
      { userId: "user-1", expressionId: "expr-1" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    expect(result.id).toBe("expr-1");
    expect(result.variants).toHaveLength(1);
    expect(result.review_card_id).toBe("card-1");

    const request = calls[0];
    const url = new URL(request.url);
    expect(url.pathname).toBe("/api/v1/expressions/expr-1");
    expect(request.method).toBe("GET");

    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
    expect(payload.session_token).toBeUndefined();
  });

  test("normalizes null review_card_id and missing variants", async () => {
    const fetcher: FetchLike = async () =>
      okDetail({ review_card_id: null, next_review_at: null, variants: null });

    const result = await getExpression(
      { userId: "user-2", expressionId: "expr-2" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    expect(result.review_card_id).toBeNull();
    expect(result.next_review_at).toBeNull();
    expect(result.variants).toEqual([]);
  });

  test("throws ExpressionNotFoundError on 404", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "not_found" }, { status: 404 });

    let thrown: unknown;
    try {
      await getExpression(
        { userId: "user-3", expressionId: "gone" },
        {
          backendBaseUrl: "http://backend.test",
          internalAuthSecret: SECRET,
          fetcher,
        },
      );
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(ExpressionNotFoundError);
  });

  test("throws generic error on other non-2xx", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "boom" }, { status: 502 });

    let thrown: unknown;
    try {
      await getExpression(
        { userId: "user-4", expressionId: "expr-4" },
        {
          backendBaseUrl: "http://backend.test",
          internalAuthSecret: SECRET,
          fetcher,
        },
      );
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(Error);
    expect(thrown).not.toBeInstanceOf(ExpressionNotFoundError);
  });
});
