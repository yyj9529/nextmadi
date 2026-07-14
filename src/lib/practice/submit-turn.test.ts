import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./submit-turn";

mock.module("server-only", () => ({}));

const { submitTurn, SubmitTurnError } = await import("./submit-turn");

const SECRET = "nextjs-pending-save-internal-secret-0123456789";
const OPTS = { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET };

function consumed(overrides: Record<string, unknown> = {}): Response {
  return Response.json(
    {
      turn_consumed: true,
      retry_prompt: null,
      user_turn: {
        id: "turn-2",
        turn_number: 2,
        speaker: "user",
        text_content: "I'm allergic to peanuts.",
        tts_audio_url: null,
        stt_confidence: 0.9,
        feedback_shown: true,
        created_at: "2026-07-14T09:01:00Z",
      },
      coach_turn: {
        id: "turn-3",
        turn_number: 3,
        speaker: "coach",
        text_content: "Got it.",
        tts_audio_url: null,
        stt_confidence: null,
        feedback_shown: false,
        created_at: "2026-07-14T09:01:05Z",
      },
      feedback: {
        show_feedback: true,
        natural_alternative: "I have a peanut allergy.",
        korean_comment: "이렇게 말하면 더 자연스러워요",
      },
      session_status: "active",
      ...overrides,
    },
    { status: 200 },
  );
}

describe("submitTurn", () => {
  test("mints a user_id token and POSTs text_content + Idempotency-Key", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return consumed();
    };

    const result = await submitTurn(
      { userId: "user-1", sessionId: "session-1", textContent: "I'm allergic to peanuts." },
      { ...OPTS, fetcher, idempotencyKey: "idem-turn" },
    );

    expect(result.turn_consumed).toBe(true);
    expect(result.coach_turn?.turn_number).toBe(3);
    expect(result.feedback?.show_feedback).toBe(true);
    expect(result.session_status).toBe("active");

    const request = calls[0];
    expect(new URL(request.url).pathname).toBe(
      "/api/v1/practice/sessions/session-1/turns",
    );
    expect(request.method).toBe("POST");
    expect(request.headers.get("idempotency-key")).toBe("idem-turn");

    const body = (await request.json()) as Record<string, unknown>;
    expect(body.text_content).toBe("I'm allergic to peanuts.");

    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
  });

  test("passes through turn_consumed=false with a retry prompt", async () => {
    const fetcher: FetchLike = async () =>
      Response.json(
        {
          turn_consumed: false,
          retry_prompt: "잘 안 들렸어요. 다시 말해주세요",
          session_status: "active",
        },
        { status: 200 },
      );

    const result = await submitTurn(
      { userId: "user-1", sessionId: "session-1", textContent: " " },
      { ...OPTS, fetcher },
    );

    expect(result.turn_consumed).toBe(false);
    expect(result.retry_prompt).toBe("잘 안 들렸어요. 다시 말해주세요");
    expect(result.user_turn).toBeNull();
    expect(result.coach_turn).toBeNull();
  });

  test("surfaces session_status=completed", async () => {
    const fetcher: FetchLike = async () =>
      consumed({ session_status: "completed" });
    const result = await submitTurn(
      { userId: "user-1", sessionId: "session-1", textContent: "Thanks." },
      { ...OPTS, fetcher },
    );
    expect(result.session_status).toBe("completed");
  });

  test("404 throws a not-found SubmitTurnError", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "not_found" }, { status: 404 });

    let thrown: unknown;
    try {
      await submitTurn(
        { userId: "user-1", sessionId: "gone", textContent: "hi" },
        { ...OPTS, fetcher },
      );
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeInstanceOf(SubmitTurnError);
    expect((thrown as { isNotFound: boolean }).isNotFound).toBe(true);
  });

  test("409 throws a conflict SubmitTurnError (session not active)", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "session_not_active" }, { status: 409 });

    let thrown: unknown;
    try {
      await submitTurn(
        { userId: "user-1", sessionId: "session-1", textContent: "hi" },
        { ...OPTS, fetcher },
      );
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeInstanceOf(SubmitTurnError);
    expect((thrown as { isConflict: boolean }).isConflict).toBe(true);
    expect((thrown as { isNotFound: boolean }).isNotFound).toBe(false);
  });
});
