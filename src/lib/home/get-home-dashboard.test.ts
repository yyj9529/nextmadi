import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./get-home-dashboard";

mock.module("server-only", () => ({}));

const { getHomeDashboard, GetHomeDashboardError } = await import(
  "./get-home-dashboard"
);

const SECRET = "nextjs-pending-save-internal-secret-0123456789";

const FULL_BODY = {
  user: {
    id: "user-1",
    email: "jiyoung@example.com",
    display_name: "지영",
    selected_coach_id: "coach-mia",
    is_onboarded: true,
    created_at: "2026-04-01T00:00:00Z",
  },
  coach: {
    id: "coach-mia",
    slug: "mia",
    display_name: "Mia",
    persona_summary: "친절한 코치",
    tts_voice_id: "voice-mia",
  },
  bookshelf_count: 47,
  recent_expressions: [
    {
      id: "expr-1",
      original_situation: "줄 새치기 상황",
      english_text: "Excuse me, I think there's a line.",
      tone_label: "정중한",
      created_at: "2026-06-12T07:00:00Z",
    },
  ],
  due_review_count: 5,
  today_roleplay_session_count: 1,
  daily_roleplay_limit: 2,
};

describe("getHomeDashboard", () => {
  test("reads the aggregate from the dashboard endpoint with a user_id token", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return Response.json(FULL_BODY, { status: 200 });
    };

    const result = await getHomeDashboard(
      { userId: "user-1" },
      {
        backendBaseUrl: "http://backend.test",
        internalAuthSecret: SECRET,
        fetcher,
      },
    );

    expect(result.bookshelf_count).toBe(47);
    expect(result.coach?.display_name).toBe("Mia");
    expect(result.recent_expressions).toHaveLength(1);
    expect(result.due_review_count).toBe(5);

    const request = calls[0];
    expect(new URL(request.url).pathname).toBe("/api/v1/home/dashboard");
    const token = request.headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
  });

  test("defaults coach to null and counts to safe values when fields are missing", async () => {
    const fetcher: FetchLike = async () =>
      Response.json(
        {
          user: {
            id: "user-2",
            email: "u2@example.com",
            display_name: null,
            selected_coach_id: null,
            is_onboarded: true,
            created_at: "2026-04-01T00:00:00Z",
          },
        },
        { status: 200 },
      );

    const result = await getHomeDashboard(
      { userId: "user-2" },
      {
        backendBaseUrl: "http://backend.test",
        internalAuthSecret: SECRET,
        fetcher,
      },
    );

    expect(result.coach).toBeNull();
    expect(result.bookshelf_count).toBe(0);
    expect(result.recent_expressions).toEqual([]);
    expect(result.due_review_count).toBe(0);
    expect(result.today_roleplay_session_count).toBe(0);
    expect(result.daily_roleplay_limit).toBe(2);
  });

  test("throws GetHomeDashboardError on non-2xx", async () => {
    const fetcher: FetchLike = async () => new Response("", { status: 500 });

    let thrown: unknown;
    try {
      await getHomeDashboard(
        { userId: "user-3" },
        {
          backendBaseUrl: "http://backend.test",
          internalAuthSecret: SECRET,
          fetcher,
        },
      );
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(GetHomeDashboardError);
  });
});
