import { describe, expect, mock, test } from "bun:test";

import type { FetchLike } from "./email-provisioning";

mock.module("server-only", () => ({}));

const { createBffAdapter } = await import("./bff-adapter");

const SECRET = "nextjs-email-provisioning-secret-0123456789";
const EXPIRES_ISO = "2026-08-28T00:00:00.000Z";

const IDENTITY = {
  userId: "user-1",
  email: "mia@example.com",
  displayName: "Mia",
  isOnboarded: false,
  createdUser: true,
  canceledScheduledDeletion: false,
  linkedToExistingUser: false,
};

function adapterWith(responder: (request: Request) => Response) {
  const calls: Request[] = [];
  const fetcher: FetchLike = async (request) => {
    calls.push(request);
    return responder(request);
  };
  const adapter = createBffAdapter({
    backendBaseUrl: "http://backend.test",
    internalAuthSecret: SECRET,
    fetcher,
  });
  return { adapter, calls };
}

const json = (body: unknown, status = 200) => Response.json(body, { status });

describe("createVerificationToken", () => {
  test("sends the expiry as ISO and returns it as a Date", async () => {
    const { adapter, calls } = adapterWith(() =>
      json({
        identifier: "mia@example.com",
        token: "hashed",
        expires: EXPIRES_ISO,
      }),
    );

    const result = await adapter.createVerificationToken!({
      identifier: "mia@example.com",
      token: "hashed",
      expires: new Date(EXPIRES_ISO),
    });

    expect(await calls[0].json()).toMatchObject({ expires: EXPIRES_ISO });
    expect(result!.expires).toBeInstanceOf(Date);
    expect(result!.expires.toISOString()).toBe(EXPIRES_ISO);
  });
});

describe("useVerificationToken", () => {
  test("returns the record with a Date expiry", async () => {
    const { adapter } = adapterWith(() =>
      json({
        identifier: "mia@example.com",
        token: "hashed",
        expires: EXPIRES_ISO,
      }),
    );

    const result = await adapter.useVerificationToken!({
      identifier: "mia@example.com",
      token: "hashed",
    });

    expect(result!.expires).toBeInstanceOf(Date);
  });

  test("returns null when the backend has no such token", async () => {
    const { adapter } = adapterWith(() =>
      json({ error_code: "verification_token_not_found" }, 404),
    );

    const result = await adapter.useVerificationToken!({
      identifier: "mia@example.com",
      token: "hashed",
    });

    expect(result).toBeNull();
  });

  test("propagates a backend outage instead of returning null", async () => {
    // Returning null here would render as "this link expired" — our outage, shown as user error.
    const { adapter } = adapterWith(() => json({ error_code: "internal_error" }, 500));

    await expect(
      adapter.useVerificationToken!({ identifier: "mia@example.com", token: "hashed" }),
    ).rejects.toThrow();
  });
});

describe("getUserByEmail", () => {
  test("maps a known identity onto an adapter user", async () => {
    const { adapter, calls } = adapterWith(() => json(IDENTITY));

    const user = await adapter.getUserByEmail!("mia@example.com");

    expect(user).toMatchObject({ id: "user-1", email: "mia@example.com", name: "Mia" });
    expect(calls[0].url).toContain("/auth/email/identity/lookup");
  });

  test("returns null for an unknown address and creates nothing", async () => {
    // Auth.js calls this while merely sending the link. Anything that writes here would open an
    // account for every address typed into the login form.
    const { adapter, calls } = adapterWith(() =>
      json({ error_code: "email_identity_not_found" }, 404),
    );

    expect(await adapter.getUserByEmail!("nobody@example.com")).toBeNull();
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toContain("/lookup");
  });
});

describe("createUser", () => {
  test("resolves the address through the write endpoint", async () => {
    const { adapter, calls } = adapterWith(() => json(IDENTITY));

    const user = await adapter.createUser!({
      id: "ignored-by-us",
      email: "mia@example.com",
      emailVerified: new Date(),
    });

    expect(user).toMatchObject({ id: "user-1", email: "mia@example.com" });
    expect(calls[0].url).toBe("http://backend.test/api/v1/auth/email/identity");
    expect(await calls[0].json()).toEqual({ email: "mia@example.com" });
  });
});

describe("updateUser", () => {
  test("links by user id, because Auth.js sends no address here", async () => {
    const { adapter, calls } = adapterWith(() =>
      json({ ...IDENTITY, createdUser: false, linkedToExistingUser: true }),
    );

    const user = await adapter.updateUser!({
      id: "user-1",
      emailVerified: new Date(),
    });

    expect(user).toMatchObject({ id: "user-1", email: "mia@example.com" });
    expect(calls[0].url).toBe("http://backend.test/api/v1/auth/email/identity/link");
    expect(await calls[0].json()).toEqual({ user_id: "user-1" });
  });

  test("does not fail over the emailVerified field it cannot store", async () => {
    const { adapter } = adapterWith(() => json(IDENTITY));

    await expect(
      adapter.updateUser!({ id: "user-1", emailVerified: new Date() }),
    ).resolves.toBeDefined();
  });
});

describe("getUser", () => {
  test("returns null without calling the backend", async () => {
    // Spring Boot resolves identity by address only. Auth.js reaches this method solely on the
    // already-signed-in path, where null means "no prior session" and a fresh sign-in follows.
    const { adapter, calls } = adapterWith(() => json(IDENTITY));

    expect(await adapter.getUser!("user-1")).toBeNull();
    expect(calls).toHaveLength(0);
  });
});

describe("unimplemented methods", () => {
  test.each([
    "getUserByAccount",
    "linkAccount",
    "unlinkAccount",
    "createSession",
    "getSessionAndUser",
    "updateSession",
    "deleteSession",
    "deleteUser",
  ])("%s throws instead of returning a plausible value", (name) => {
    // A stub that returns null or undefined turns a missing capability into a successful-looking
    // login (docs/solutions/green-build-proves-nothing.md).
    const { adapter } = adapterWith(() => json(IDENTITY));
    const method = (adapter as unknown as Record<string, () => unknown>)[name];

    expect(method).toBeDefined();
    expect(() => method.call(adapter)).toThrow(name);
  });
});
