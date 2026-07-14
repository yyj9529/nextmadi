import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./start-session";

mock.module("server-only", () => ({}));

const { startSession, StartSessionError } = await import("./start-session");

const SECRET = "nextjs-pending-save-internal-secret-0123456789";
const OPTS = { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET };

function created(overrides: Record<string, unknown> = {}): Response {
  return Response.json(
    {
      id: "session-1",
      status: "active",
      planned_turns: 4,
      coach_id: "coach-1",
      expression_id: "expr-1",
      started_at: "2026-07-14T09:00:00Z",
      opening_turn: {
        id: "turn-1",
        turn_number: 1,
        speaker: "coach",
        text_content: "Let's practice ordering at a restaurant.",
        tts_audio_url: null,
        stt_confidence: null,
        feedback_shown: false,
        created_at: "2026-07-14T09:00:00Z",
      },
      ...overrides,
    },
    { status: 201 },
  );
}

describe("startSession", () => {
  test("mints a user_id token and POSTs expression_id + Idempotency-Key", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return created();
    };

    const result = await startSession(
      { userId: "user-1", expressionId: "expr-1" },
      { ...OPTS, fetcher, idempotencyKey: "idem-1" },
    );

    expect(result.id).toBe("session-1");
    expect(result.planned_turns).toBe(4);
    expect(result.opening_turn?.turn_number).toBe(1);

    const request = calls[0];
    const url = new URL(request.url);
    expect(url.pathname).toBe("/api/v1/practice/sessions");
    expect(request.method).toBe("POST");
    expect(request.headers.get("idempotency-key")).toBe("idem-1");

    const body = (await request.json()) as Record<string, unknown>;
    expect(body.expression_id).toBe("expr-1");
    expect(body.coach_id).toBeUndefined();

    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
  });

  test("forwards coach_id when provided (coach switch)", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return created({ coach_id: "coach-2" });
    };

    const result = await startSession(
      { userId: "user-1", expressionId: "expr-1", coachId: "coach-2" },
      { ...OPTS, fetcher },
    );

    expect(result.coach_id).toBe("coach-2");
    const body = (await calls[0].json()) as Record<string, unknown>;
    expect(body.coach_id).toBe("coach-2");
  });

  test("normalizes a missing opening_turn to null", async () => {
    const fetcher: FetchLike = async () => created({ opening_turn: undefined });
    const result = await startSession(
      { userId: "user-1", expressionId: "expr-1" },
      { ...OPTS, fetcher },
    );
    expect(result.opening_turn).toBeNull();
  });

  test("429 throws a rate-limited StartSessionError", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "rate_limit_exceeded" }, { status: 429 });

    let thrown: unknown;
    try {
      await startSession(
        { userId: "user-1", expressionId: "expr-1" },
        { ...OPTS, fetcher },
      );
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeInstanceOf(StartSessionError);
    expect((thrown as { isRateLimited: boolean }).isRateLimited).toBe(true);
  });

  test("404 throws a not-found StartSessionError", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "not_found" }, { status: 404 });

    let thrown: unknown;
    try {
      await startSession(
        { userId: "user-1", expressionId: "gone" },
        { ...OPTS, fetcher },
      );
    } catch (error) {
      thrown = error;
    }
    expect((thrown as { isNotFound: boolean }).isNotFound).toBe(true);
  });
});
