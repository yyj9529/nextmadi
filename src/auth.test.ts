import { beforeEach, describe, expect, mock, test } from "bun:test";
import type { Account, Profile, User } from "next-auth";
import type { JWT } from "next-auth/jwt";

let provisionOAuthIdentity = mock(async () => ({
  userId: "user-1",
  email: "new@example.com",
  displayName: "New User",
  isOnboarded: false,
  createdUser: true,
  canceledScheduledDeletion: false,
}));

mock.module("server-only", () => ({}));
mock.module("next-auth", () => ({
  default: () => ({
    handlers: {
      GET: mock(async () => new Response(null)),
      POST: mock(async () => new Response(null)),
    },
    auth: mock(async () => null),
    signIn: mock(async () => undefined),
    signOut: mock(async () => undefined),
  }),
}));
const { OAuthProvisioningError } = await import("./lib/auth/oauth-provisioning");
const { buildAuthConfig } = await import("./auth");

describe("authConfig", () => {
  beforeEach(() => {
    provisionOAuthIdentity = mock(async () => ({
      userId: "user-1",
      email: "new@example.com",
      displayName: "New User",
      isOnboarded: false,
      createdUser: true,
      canceledScheduledDeletion: false,
    }));
  });

  test("configures Google and Kakao with hardened production session cookie", () => {
    const authConfig = buildAuthConfig({ secureCookies: true });
    const providerIds = authConfig.providers.map((provider) =>
      typeof provider === "function" ? provider({}).id : provider.id,
    );

    expect(providerIds).toEqual(["google", "kakao"]);
    expect(authConfig.pages).toEqual({ signIn: "/login", error: "/login" });
    expect(authConfig.session).toMatchObject({
      strategy: "jwt",
      maxAge: 7 * 24 * 60 * 60,
    });
    expect(authConfig.cookies?.sessionToken).toEqual({
      name: "__Host-authjs.session-token",
      options: {
        httpOnly: true,
        sameSite: "lax",
        path: "/",
        secure: true,
      },
    });
  });

  test("provisions an OAuth identity before allowing sign-in", async () => {
    const authConfig = buildAuthConfig({ provisionOAuthIdentity });
    const user = {
      email: "new@example.com",
      name: "New User",
    } satisfies User;
    const account = {
      provider: "google",
      providerAccountId: "google-1",
      type: "oauth",
    } satisfies Partial<Account> as Account;
    const profile = { email: "new@example.com" } satisfies Profile;

    const signInResult = await authConfig.callbacks?.signIn?.({
      user,
      account,
      profile,
    });
    const token = await authConfig.callbacks?.jwt?.({
      token: {} as JWT,
      user,
      account,
      profile,
      trigger: "signIn",
    });
    const session = await authConfig.callbacks?.session?.({
      session: { user: {}, expires: "2099-01-01T00:00:00.000Z" },
      token: token as JWT,
      newSession: null,
    } as never);

    expect(signInResult).toBe(true);
    expect(provisionOAuthIdentity).toHaveBeenCalledWith({
      provider: "google",
      providerUserId: "google-1",
      providerEmail: "new@example.com",
      displayName: "New User",
    });
    expect(token).toMatchObject({
      phraselogUserId: "user-1",
      isOnboarded: false,
      email: "new@example.com",
      name: "New User",
    });
    expect(session?.user).toMatchObject({
      id: "user-1",
      email: "new@example.com",
      name: "New User",
      isOnboarded: false,
    });
  });

  test("redirects same-email OAuth conflicts to the S03 callback error state", async () => {
    provisionOAuthIdentity = mock(async () => {
      throw new OAuthProvisioningError(409, {
        error_code: "account_link_required",
      });
    });
    const authConfig = buildAuthConfig({ provisionOAuthIdentity });

    const signInResult = await authConfig.callbacks?.signIn?.({
      user: { email: "same@example.com", name: "Same User" },
      account: {
        provider: "kakao",
        providerAccountId: "kakao-1",
        type: "oauth",
      } satisfies Partial<Account> as Account,
      profile: {},
    });

    expect(signInResult).toBe("/login?callback_error=account_link_required");
  });

  // 백엔드가 안 떠 있을 때 실제로 도는 경로다. 리다이렉트만 확인하면 조용한 실패를 그대로
  // 통과시키므로, 서버 로그에 원인이 남는지까지 본다. 로그가 없으면 화면의 "다시 시도해주세요"
  // 하나로 원인을 추적해야 한다.
  test("logs the cause when provisioning fails for a non-conflict reason", async () => {
    provisionOAuthIdentity = mock(async () => {
      throw new TypeError("fetch failed");
    });
    const errors: unknown[][] = [];
    const originalError = console.error;
    console.error = (...args: unknown[]) => {
      errors.push(args);
    };

    try {
      const authConfig = buildAuthConfig({ provisionOAuthIdentity });

      const signInResult = await authConfig.callbacks?.signIn?.({
        user: { email: "down@example.com", name: "Backend Down" },
        account: {
          provider: "google",
          providerAccountId: "google-1",
          type: "oauth",
        } satisfies Partial<Account> as Account,
        profile: {},
      });

      expect(signInResult).toBe("/login?callback_error=oauth");
      expect(errors).toHaveLength(1);
      expect(String(errors[0][0])).toContain("google");
      expect(String(errors[0][1])).toContain("fetch failed");
      // 이메일이 로그로 새지 않는지 확인한다 (SECURITY.md).
      expect(JSON.stringify(errors.map(String))).not.toContain(
        "down@example.com",
      );
    } finally {
      console.error = originalError;
    }
  });
});
