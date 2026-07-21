import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type {
  FetchLike,
  GenerateResultError as GenerateResultErrorType,
} from "./generate-result";

mock.module("server-only", () => ({}));

const { generateResult, GenerateResultError } = await import(
  "./generate-result"
);

const SECRET = "nextjs-pending-save-internal-secret-0123456789";
const OPTS = { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET };

function okResult(): Response {
  return Response.json(
    {
      coach_encouragement: "잘했어요!",
      recommended_expressions: [
        {
          english: "I'm allergic to peanuts.",
          tone_label: "기본",
          ipa: "/.../",
          korean_pronunciation: "아임 얼러직",
          pronunciation_tip: "th 발음",
          cultural_tip: "정중하게",
        },
      ],
      awkward_pairs: [],
      pronunciation_focus_words: ["th"],
    },
    { status: 200 },
  );
}

describe("generateResult", () => {
  test("mints a user_id token and POSTs the result path", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return okResult();
    };

    const result = await generateResult(
      { userId: "user-1", sessionId: "session-1" },
      { ...OPTS, fetcher },
    );

    expect(result.coach_encouragement).toBe("잘했어요!");
    expect(result.recommended_expressions).toHaveLength(1);

    const request = calls[0];
    expect(new URL(request.url).pathname).toBe(
      "/api/v1/practice/sessions/session-1/result",
    );
    expect(request.method).toBe("POST");

    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
  });

  test("maps 409 to isNotCompleted", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "not_completed" }, { status: 409 });

    let thrown: unknown;
    try {
      await generateResult(
        { userId: "user-1", sessionId: "session-1" },
        { ...OPTS, fetcher },
      );
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeInstanceOf(GenerateResultError);
    expect((thrown as GenerateResultErrorType).isNotCompleted).toBe(true);
    expect((thrown as GenerateResultErrorType).isNotFound).toBe(false);
  });

  test("maps 404 to isNotFound", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ error_code: "not_found" }, { status: 404 });

    let thrown: unknown;
    try {
      await generateResult(
        { userId: "user-1", sessionId: "gone" },
        { ...OPTS, fetcher },
      );
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeInstanceOf(GenerateResultError);
    expect((thrown as GenerateResultErrorType).isNotFound).toBe(true);
  });
});
