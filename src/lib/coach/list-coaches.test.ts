import { describe, expect, mock, test } from "bun:test";
import { jwtVerify } from "jose";

import type { FetchLike } from "./list-coaches";

mock.module("server-only", () => ({}));

const { listCoaches, ListCoachesError } = await import("./list-coaches");

const SECRET = "nextjs-pending-save-internal-secret-0123456789";

const COACHES = [
  {
    id: "11111111-1111-1111-1111-111111111111",
    slug: "mia" as const,
    display_name: "Mia",
    persona_summary: "친절한 코치",
    tts_voice_id: "voice-mia",
  },
];

describe("listCoaches", () => {
  test("mints a user_id internal token and returns the coaches array", async () => {
    const calls: Request[] = [];
    const fetcher: FetchLike = async (request) => {
      calls.push(request);
      return Response.json({ coaches: COACHES }, { status: 200 });
    };

    const result = await listCoaches(
      { userId: "user-1" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    expect(result).toEqual(COACHES);
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe("http://backend.test/api/v1/coaches");
    expect(calls[0].method).toBe("GET");

    const token = calls[0].headers.get("x-internal-auth") as string;
    const { payload } = await jwtVerify(token, new TextEncoder().encode(SECRET));
    expect(payload.user_id).toBe("user-1");
  });

  test("returns an empty array when coaches are not seeded", async () => {
    const fetcher: FetchLike = async () =>
      Response.json({ coaches: [] }, { status: 200 });

    const result = await listCoaches(
      { userId: "user-2" },
      { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
    );

    expect(result).toEqual([]);
  });

  test("throws ListCoachesError on a non-2xx backend response", async () => {
    const fetcher: FetchLike = async () => new Response(null, { status: 502 });

    let thrown: unknown;
    try {
      await listCoaches(
        { userId: "user-3" },
        { backendBaseUrl: "http://backend.test", internalAuthSecret: SECRET, fetcher },
      );
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(ListCoachesError);
    expect((thrown as InstanceType<typeof ListCoachesError>).status).toBe(502);
  });
});
