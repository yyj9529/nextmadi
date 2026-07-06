import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./patch-me";

mock.module("server-only", () => ({}));

const { patchMe, PatchMeError } = await import("./patch-me");

const SECRET = "nextjs-pending-save-internal-secret-0123456789";

function okUser(): Response {
  return Response.json(
    {
      id: "user-1",
      email: "u@test.com",
      display_name: "지영",
      selected_coach_id: "11111111-1111-1111-1111-111111111111",
      is_onboarded: true,
      created_at: "2026-04-01T00:00:00Z",
      scheduled_deletion_at: null,
    },
    { status: 200 },
  );
}

describe("patchMe", () => {
  test("mints a user_id token and sends only the provided fields", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return okUser();
    };

    const result = await patchMe(
      {
        userId: "user-1",
        selectedCoachId: "11111111-1111-1111-1111-111111111111",
        isOnboarded: true,
      },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    expect(result.is_onboarded).toBe(true);
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe("http://backend.test/api/v1/me");
    expect(calls[0].method).toBe("PATCH");

    const token = calls[0].headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");

    // 부분 수정: 전달한 필드만 본문에 담긴다(display_name 없음).
    const body = await calls[0].json();
    expect(body).toEqual({
      selected_coach_id: "11111111-1111-1111-1111-111111111111",
      is_onboarded: true,
    });
  });

  test("omits absent fields from the body", async () => {
    let captured: Request | null = null;
    const fetcher: FetchLike = async (request) => {
      captured = request;
      return okUser();
    };

    await patchMe(
      { userId: "user-2", displayName: "새이름" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    const body = await (captured as unknown as Request).json();
    expect(body).toEqual({ display_name: "새이름" });
  });

  test("throws PatchMeError on a non-2xx backend response", async () => {
    const fetcher: FetchLike = async () => new Response(null, { status: 400 });

    let thrown: unknown;
    try {
      await patchMe(
        { userId: "user-3", isOnboarded: true },
        { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
      );
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(PatchMeError);
    expect((thrown as InstanceType<typeof PatchMeError>).status).toBe(400);
  });
});
