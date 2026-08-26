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

    // S03 AC1 order: Google, Kakao, then email.
    expect(providerIds).toEqual(["google", "kakao", "nodemailer"]);
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

  test("attaches an adapter, which the email provider requires", () => {
    // ADR-010 keeps the database with Spring Boot; the adapter exists to satisfy Auth.js and
    // forwards storage over X-Internal-Auth rather than opening a second writer.
    const authConfig = buildAuthConfig();

    expect(authConfig.adapter).toBeDefined();
    expect(authConfig.adapter?.createVerificationToken).toBeInstanceOf(Function);
    expect(authConfig.adapter?.useVerificationToken).toBeInstanceOf(Function);
    expect(authConfig.adapter?.getUserByEmail).toBeInstanceOf(Function);
  });

  test("carries the adapter user onto the session for an email sign-in", async () => {
    // The adapter has already settled the user against Spring Boot, so user.id is the PhraseLog
    // user_id and there is nothing for signIn to provision.
    const authConfig = buildAuthConfig({ provisionOAuthIdentity });
    const user = {
      id: "user-9",
      email: "mia@example.com",
      name: "Mia",
      isOnboarded: true,
    } as User;
    const account = {
      provider: "nodemailer",
      providerAccountId: "mia@example.com",
      type: "email",
    } satisfies Partial<Account> as Account;

    const signInResult = await authConfig.callbacks?.signIn?.({
      user,
      account,
      profile: undefined,
    } as never);
    const token = await authConfig.callbacks?.jwt?.({
      token: {} as JWT,
      user,
      account,
      trigger: "signIn",
    } as never);
    const session = await authConfig.callbacks?.session?.({
      session: { user: {}, expires: "2099-01-01T00:00:00.000Z" },
      token: token as JWT,
      newSession: null,
    } as never);

    expect(signInResult).toBe(true);
    expect(provisionOAuthIdentity).not.toHaveBeenCalled();
    expect(token).toMatchObject({
      phraselogUserId: "user-9",
      isOnboarded: true,
      email: "mia@example.com",
      name: "Mia",
    });
    // S03 AC3 routes on isOnboarded, so losing it here would send onboarded users to /welcome/coach.
    expect(session?.user).toMatchObject({
      id: "user-9",
      email: "mia@example.com",
      isOnboarded: true,
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
});
