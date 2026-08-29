import { describe, expect, mock, test } from "bun:test";

import type { FetchLike } from "./email-provisioning";

mock.module("server-only", () => ({}));

const { createBffAdapter } = await import("./bff-adapter");

const SECRET = "nextjs-email-provisioning-secret-0123456789";
const EXPIRES_ISO = "2026-08-28T00:00:00.000Z";
// 만료 판정을 보는 테스트는 지금 기준으로 잡는다. 고정 날짜를 쓰면 그 날이 지나는 순간
// "유효한 토큰" 케이스가 조용히 만료 케이스로 바뀌어 검사하려던 것을 더 이상 검사하지 않는다.
const FUTURE_ISO = new Date(Date.now() + 60 * 60 * 1000).toISOString();
const PAST_ISO = new Date(Date.now() - 60 * 1000).toISOString();

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
  const consuming = (expires: unknown) =>
    adapterWith(() =>
      json({ identifier: "mia@example.com", token: "hashed", expires }),
    ).adapter.useVerificationToken!({
      identifier: "mia@example.com",
      token: "hashed",
    });

  test("returns the record with a Date expiry", async () => {
    const result = await consuming(FUTURE_ISO);

    expect(result!.expires).toBeInstanceOf(Date);
    expect(result!.expires.toISOString()).toBe(FUTURE_ISO);
  });

  // 만료 판정이 원래는 @auth/core 안의 한 줄에만 있었다. 캐럿 범위의 프리릴리스가
  // 보안상 의미 있는 수명의 유일한 집행자였다.
  test("refuses a token the backend consumed after it expired", async () => {
    expect(await consuming(PAST_ISO)).toBeNull();
  });

  test.each([
    ["not-a-date", "wire format drift"],
    [null, "a null the contract does not allow"],
  ])("refuses an unusable expiry (%s)", async (expires) => {
    // 계약을 벗어난 값이 Date로 어떻게 떨어지는지는 값마다 다르다 — "not-a-date"는 Invalid Date라
    // NaN 비교가 false가 되어 상류에서 "만료되지 않음"으로 통과하고, null은 epoch가 된다.
    // 어느 쪽이든 결론은 거절이어야 한다.
    expect(await consuming(expires)).toBeNull();
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
    // login (docs/solutions/silent-failure-looks-like-success.md).
    const { adapter } = adapterWith(() => json(IDENTITY));
    const method = (adapter as unknown as Record<string, () => unknown>)[name];

    expect(method).toBeDefined();
    expect(() => method.call(adapter)).toThrow(name);
  });
});
