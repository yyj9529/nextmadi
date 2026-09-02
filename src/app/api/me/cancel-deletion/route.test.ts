import { beforeEach, describe, expect, mock, test } from "bun:test";

mock.module("server-only", () => ({}));

type Session = { user?: { id?: string } } | null;
let currentSession: Session = null;

let headerMap = new Map<string, string>();

let cancelImpl: (input: unknown) => Promise<void>;
const cancelCalls: unknown[] = [];

mock.module("next/headers", () => ({
  headers: async () => ({
    get: (name: string) => headerMap.get(name.toLowerCase()) ?? null,
  }),
}));

mock.module("@/auth", () => ({
  auth: async () => currentSession,
}));

mock.module("@/lib/user/account-deletion", () => ({
  cancelAccountDeletion: async (input: unknown) => {
    cancelCalls.push(input);
    return cancelImpl(input);
  },
}));

const { POST } = await import("./route");

beforeEach(() => {
  currentSession = { user: { id: "user-1" } };
  headerMap = new Map();
  cancelCalls.length = 0;
  cancelImpl = async () => {};
});

describe("POST /api/me/cancel-deletion", () => {
  test("cancels the scheduled deletion for the session user and returns 204", async () => {
    const response = await POST();

    expect(response.status).toBe(204);
    expect(cancelCalls).toEqual([{ userId: "user-1" }]);
  });

  test("rejects an unauthenticated request with 401", async () => {
    currentSession = null;

    const response = await POST();

    expect(response.status).toBe(401);
    expect(cancelCalls).toHaveLength(0);
  });

  test("rejects a cross-origin request with 403", async () => {
    headerMap.set("origin", "http://evil.test");
    headerMap.set("host", "localhost");

    const response = await POST();

    expect(response.status).toBe(403);
    expect(cancelCalls).toHaveLength(0);
  });

  test("maps a backend failure to 502", async () => {
    cancelImpl = async () => {
      throw new Error("backend down");
    };

    const response = await POST();

    expect(response.status).toBe(502);
  });
});
