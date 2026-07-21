import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./get-session";

mock.module("server-only", () => ({}));

const { getSession, SessionNotFoundError } = await import("./get-session");

const SECRET = "nextjs-pending-save-internal-secret-0123456789";
const OPTS = { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET };

function okSession(overrides: Record<string, unknown> = {}): Response {
  return Response.json(
    {
      id: "session-1",
      status: "active",
      planned_turns: 4,
      coach_id: "coach-1",
      expression_id: "expr-1",
      started_at: "2026-07-14T09:00:00Z",
      ended_at: null,
      turns: [
        {
          id: "turn-1",
          turn_number: 1,
          speaker: "coach",
          text_content: "Let's practice.",
          tts_audio_url: null,
          stt_confidence: null,
          feedback_shown: false,
          created_at: "2026-07-14T09:00:00Z",
        },
      ],
      ...overrides,
    },
    { status: 200 },
  );
}

describe("getSession", () => {
  test("mints a user_id token and GETs the session path", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return okSession();
    };

    const result = await getSession(
      { userId: "user-1", sessionId: "session-1" },
      { ...OPTS, fetcher },
    );

    expect(result.id).toBe("session-1");
    expect(result.turns).toHaveLength(1);

    const request = calls[0];
    expect(new URL(request.url).pathname).toBe(
      "/api/v1/practice/sessions/session-1",
    );
    expect(request.method).toBe("GET");

    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
  });

  test("normalizes missing turns to an empty array", async () => {
    const fetcher: FetchLike = async () => okSession({ turns: null });
    const result = await getSession(
      { userId: "user-1", sessionId: "session-1" },
      { ...OPTS, fetcher },
    );
    expect(result.turns).toEqual([]);
  });

  test("defaults missing result_json to null and passes it through when present", async () => {
    const withoutResult = await getSession(
      { userId: "user-1", sessionId: "session-1" },
      { ...OPTS, fetcher: async () => okSession() },
    );
    expect(withoutResult.result_json).toBeNull();

    const resultJson = {
      coach_encouragement: "잘했어요!",
      recommended_expressions: [],
      awkward_pairs: [],
      pronunciation_focus_words: [],
    };
    const withResult = await getSession(
      { userId: "user-1", sessionId: "session-1" },
      { ...OPTS, fetcher: async () => okSession({ result_json: resultJson }) },
    );
    expect(withResult.result_json).toEqual(resultJson);
  });

  test("throws SessionNotFoundError on 404", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "not_found" }, { status: 404 });

    let thrown: unknown;
    try {
      await getSession(
        { userId: "user-1", sessionId: "gone" },
        { ...OPTS, fetcher },
      );
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeInstanceOf(SessionNotFoundError);
  });

  test("throws generic error on other non-2xx", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "boom" }, { status: 502 });

    let thrown: unknown;
    try {
      await getSession(
        { userId: "user-1", sessionId: "session-1" },
        { ...OPTS, fetcher },
      );
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeInstanceOf(Error);
    expect(thrown).not.toBeInstanceOf(SessionNotFoundError);
  });
});
