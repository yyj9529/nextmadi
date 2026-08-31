import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import { withoutEnv } from "../testing/without-env";
import type { FetchLike } from "./get-usage-today";

mock.module("server-only", () => ({}));

const { getUsageToday, GetUsageTodayError } = await import(
  "./get-usage-today"
);

const SECRET = "nextjs-pending-save-internal-secret-0123456789";
const BASE = {
  backendBaseUrl: "http://backend.test",
  internalAuthSecret: SECRET,
};

describe("getUsageToday", () => {
  test("mints a user_id internal token and returns today's counters", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return Response.json(
        {
          roleplay_session_count: 1,
          daily_roleplay_limit: 2,
          analysis_count: 3,
          analysis_limit: null,
        },
        { status: 200 },
      );
    };

    const result = await getUsageToday(
      { userId: "user-1" },
      { ...BASE, fetcher },
    );

    expect(result).toEqual({
      roleplay_session_count: 1,
      daily_roleplay_limit: 2,
      analysis_count: 3,
      analysis_limit: null,
    });
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe("http://backend.test/api/v1/usage/today");
    expect(calls[0].method).toBe("GET");

    const token = calls[0].headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
  });

  test("keeps a zero count as zero rather than falling back", async () => {
    const fetcher: FetchLike = async () =>
      Response.json(
        {
          roleplay_session_count: 0,
          daily_roleplay_limit: 2,
          analysis_count: 0,
          analysis_limit: 0,
        },
        { status: 200 },
      );

    const result = await getUsageToday(
      { userId: "user-2" },
      { ...BASE, fetcher },
    );

    expect(result.roleplay_session_count).toBe(0);
    expect(result.analysis_count).toBe(0);
    // 0은 "무제한"(null)이 아니다.
    expect(result.analysis_limit).toBe(0);
  });

  test("throws GetUsageTodayError on a non-2xx backend response", async () => {
    const fetcher: FetchLike = async () => new Response(null, { status: 500 });

    let thrown: unknown;
    try {
      await getUsageToday({ userId: "user-3" }, { ...BASE, fetcher });
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(GetUsageTodayError);
    expect((thrown as InstanceType<typeof GetUsageTodayError>).status).toBe(500);
  });

  test("requires the internal auth secret when it is not configured", async () => {
    await withoutEnv("INTERNAL_AUTH_SECRET", async () => {
      const fetcher: FetchLike = async () => Response.json({});

      expect(
        getUsageToday(
          { userId: "user-4" },
          { backendBaseUrl: "http://backend.test", fetcher },
        ),
      ).rejects.toThrow("INTERNAL_AUTH_SECRET is required");
    });
  });
});
