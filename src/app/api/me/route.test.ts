import { beforeEach, describe, expect, mock, test } from "bun:test";

mock.module("server-only", () => ({}));

type Session = { user?: { id?: string } } | null;
let currentSession: Session = null;

let headerMap = new Map<string, string>();

let patchImpl: (input: unknown) => Promise<unknown>;
const patchCalls: unknown[] = [];

class FakePatchMeError extends Error {
  readonly status: number;
  constructor(status: number) {
    super(`patch failed ${status}`);
    this.name = "PatchMeError";
    this.status = status;
  }
}

mock.module("next/headers", () => ({
  headers: async () => ({
    get: (name: string) => headerMap.get(name.toLowerCase()) ?? null,
  }),
}));

mock.module("@/auth", () => ({
  auth: async () => currentSession,
}));

mock.module("@/lib/user/patch-me", () => ({
  patchMe: async (input: unknown) => {
    patchCalls.push(input);
    return patchImpl(input);
  },
  PatchMeError: FakePatchMeError,
}));

const { PATCH } = await import("./route");

const COACH_UUID = "11111111-1111-1111-1111-111111111111";

function jsonRequest(body: unknown): Request {
  return new Request("http://localhost/api/me", {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
}

beforeEach(() => {
  currentSession = { user: { id: "user-1" } };
  headerMap = new Map();
  patchCalls.length = 0;
  patchImpl = async () => ({ id: "user-1", is_onboarded: true });
});

describe("PATCH /api/me", () => {
  test("persists coach selection + onboarding for an authenticated user", async () => {
    const response = await PATCH(
      jsonRequest({ selected_coach_id: COACH_UUID, is_onboarded: true }),
    );

    expect(response.status).toBe(200);
    expect(patchCalls).toHaveLength(1);
    expect(patchCalls[0]).toEqual({
      userId: "user-1",
      selectedCoachId: COACH_UUID,
      isOnboarded: true,
    });
  });

  test("rejects an unauthenticated request with 401", async () => {
    currentSession = null;

    const response = await PATCH(
      jsonRequest({ selected_coach_id: COACH_UUID, is_onboarded: true }),
    );

    expect(response.status).toBe(401);
    expect(patchCalls).toHaveLength(0);
  });

  test("rejects a cross-origin request with 403", async () => {
    headerMap.set("origin", "http://evil.test");
    headerMap.set("host", "localhost");

    const response = await PATCH(jsonRequest({ is_onboarded: true }));

    expect(response.status).toBe(403);
    expect(patchCalls).toHaveLength(0);
  });

  test("rejects a non-uuid coach id with 400", async () => {
    const response = await PATCH(
      jsonRequest({ selected_coach_id: "mock-coach-david", is_onboarded: true }),
    );

    expect(response.status).toBe(400);
    expect(patchCalls).toHaveLength(0);
  });

  test("rejects is_onboarded=false with 400 (cannot un-onboard via API)", async () => {
    const response = await PATCH(jsonRequest({ is_onboarded: false }));

    expect(response.status).toBe(400);
    expect(patchCalls).toHaveLength(0);
  });

  test("rejects an empty patch with 400", async () => {
    const response = await PATCH(jsonRequest({}));

    expect(response.status).toBe(400);
    expect(patchCalls).toHaveLength(0);
  });

  test("maps a backend failure to 502", async () => {
    patchImpl = async () => {
      throw new FakePatchMeError(502);
    };

    const response = await PATCH(
      jsonRequest({ selected_coach_id: COACH_UUID, is_onboarded: true }),
    );

    expect(response.status).toBe(502);
  });
});
