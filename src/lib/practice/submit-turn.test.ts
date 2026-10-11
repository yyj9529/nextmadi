import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./submit-turn";

mock.module("server-only", () => ({}));

const { submitTurn, submitAudioTurn, SubmitTurnError } = await import("./submit-turn");

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

describe("submitAudioTurn", () => {
  const audio = new Blob([new Uint8Array([1, 2, 3, 4])], { type: "audio/webm" });

  test("POSTs the recording as a multipart audio part with the user token and key", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return consumed();
    };

    const result = await submitAudioTurn(
      { userId: "user-1", sessionId: "session-1", audio },
      { ...OPTS, fetcher, idempotencyKey: "idem-audio" },
    );

    expect(result.turn_consumed).toBe(true);
    expect(result.coach_turn?.turn_number).toBe(3);

    const request = calls[0];
    expect(new URL(request.url).pathname).toBe(
      "/api/v1/practice/sessions/session-1/turns",
    );
    expect(request.method).toBe("POST");
    expect(request.headers.get("idempotency-key")).toBe("idem-audio");
    // The boundary is generated by fetch; a hand-set content-type would drop it and the
    // backend could not split the parts.
    expect(request.headers.get("content-type")).toMatch(
      /^multipart\/form-data; boundary=/,
    );

    const part = (await request.formData()).get("audio");
    expect(part).toBeInstanceOf(Blob);
    expect((part as Blob).size).toBe(4);

    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
  });

  test("passes through turn_consumed=false with a retry prompt", async () => {
    const fetcher: FetchLike = async () =>
      Response.json(
        {
          turn_consumed: false,
          retry_prompt: "I couldn't hear you well. Please say that again",
          session_status: "active",
        },
        { status: 200 },
      );

    const result = await submitAudioTurn(
      { userId: "user-1", sessionId: "session-1", audio },
      { ...OPTS, fetcher },
    );

    expect(result.turn_consumed).toBe(false);
    expect(result.retry_prompt).toBe("I couldn't hear you well. Please say that again");
  });

  test("keeps the backend status and error_code on failures", async () => {
    const cases: Array<[number, string, string]> = [
      [422, "empty_transcript", "isEmptyTranscript"],
      [400, "validation_failed", "isInvalidAudio"],
      [408, "timeout", "isTimeout"],
      [413, "payload_too_large", "isPayloadTooLarge"],
      [429, "provider_429", "isRateLimited"],
    ];

    for (const [status, errorCode, flag] of cases) {
      const fetcher: FetchLike = async () =>
        Response.json({ error_code: errorCode }, { status });

      let thrown: unknown;
      try {
        await submitAudioTurn(
          { userId: "user-1", sessionId: "session-1", audio },
          { ...OPTS, fetcher },
        );
      } catch (error) {
        thrown = error;
      }
      expect(thrown).toBeInstanceOf(SubmitTurnError);
      const error = thrown as SubmitTurnErrorShape;
      expect(error.status).toBe(status);
      expect(error.errorCode).toBe(errorCode);
      expect(error[flag as keyof SubmitTurnErrorShape]).toBe(true);
    }
  });

  test("a non-JSON error body still throws with the status", async () => {
    const fetcher: FetchLike = async () => new Response("bad gateway", { status: 502 });

    let thrown: unknown;
    try {
      await submitAudioTurn(
        { userId: "user-1", sessionId: "session-1", audio },
        { ...OPTS, fetcher },
      );
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeInstanceOf(SubmitTurnError);
    expect((thrown as SubmitTurnErrorShape).status).toBe(502);
    expect((thrown as SubmitTurnErrorShape).errorCode).toBeNull();
  });
});

type SubmitTurnErrorShape = {
  status: number;
  errorCode: string | null;
  isEmptyTranscript: boolean;
  isInvalidAudio: boolean;
  isTimeout: boolean;
  isPayloadTooLarge: boolean;
  isRateLimited: boolean;
};

