import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import { withoutEnv } from "../testing/without-env";
import type { FetchLike } from "./get-me";

mock.module("server-only", () => ({}));

const { getMe, GetMeError } = await import("./get-me");

const SECRET = "nextjs-pending-save-internal-secret-0123456789";
const BASE = {
  backendBaseUrl: "http://backend.test",
  internalAuthSecret: SECRET,
};

const USER = {
  id: "11111111-1111-1111-1111-111111111111",
  email: "jiyoung@gmail.com",
  display_name: "지영",
  selected_coach_id: "22222222-2222-2222-2222-222222222222",
  is_onboarded: true,
  created_at: "2026-04-01T00:00:00Z",
  scheduled_deletion_at: null,
};

describe("getMe", () => {
  test("mints a user_id internal token and returns the profile", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return Response.json(USER, { status: 200 });
    };

    const result = await getMe({ userId: "user-1" }, { ...BASE, fetcher });

    expect(result).toEqual(USER);
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe("http://backend.test/api/v1/me");
    expect(calls[0].method).toBe("GET");

    const token = calls[0].headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
  });

  test("normalizes a missing display_name and coach to null", async () => {
    const fetcher: FetchLike = async () =>
      Response.json(
        {
          id: USER.id,
          email: USER.email,
          created_at: USER.created_at,
        },
        { status: 200 },
      );

    const result = await getMe({ userId: "user-2" }, { ...BASE, fetcher });

    expect(result.display_name).toBeNull();
    expect(result.selected_coach_id).toBeNull();
    expect(result.scheduled_deletion_at).toBeNull();
    expect(result.is_onboarded).toBe(false);
  });

  test("throws GetMeError on a non-2xx backend response", async () => {
    const fetcher: FetchLike = async () => new Response(null, { status: 502 });

    let thrown: unknown;
    try {
      await getMe({ userId: "user-3" }, { ...BASE, fetcher });
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(GetMeError);
    expect((thrown as InstanceType<typeof GetMeError>).status).toBe(502);
  });

  test("requires the backend base url when it is not configured", async () => {
    // 앰비언트 .env를 읽으면 로컬에서만 통과하므로 명시적으로 지운다.
    await withoutEnv("PHRASELOG_BACKEND_BASE_URL", async () => {
      const fetcher: FetchLike = async () => Response.json(USER);

      expect(
        getMe({ userId: "user-4" }, { internalAuthSecret: SECRET, fetcher }),
      ).rejects.toThrow("PHRASELOG_BACKEND_BASE_URL is required");
    });
  });
});
