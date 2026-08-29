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
const { buildAuthConfig, isOAuthCallbackRequest, selectAuthConfig } =
  await import("./auth");
const { createBffAdapter } = await import("./lib/auth/bff-adapter");

const providerIdsOf = (config: { providers: unknown[] }) =>
  config.providers.map((provider) =>
    typeof provider === "function"
      ? (provider as (o: object) => { id: string })({}).id
      : (provider as { id: string }).id,
  );

// #19 회귀 방어. 어댑터를 설정 최상단에 붙였더니 Google/Kakao 콜백이 전부 죽었다 —
// Auth.js는 어댑터를 provider별로 범위 잡지 않아서, 어댑터가 존재하기만 하면 OAuth 콜백도
// getUserByAccount/linkAccount를 어댑터에 묻는다. 우리 어댑터는 이메일 면만 구현했으므로 던진다.
//
// 이 회귀를 놓친 원래 테스트는 "Google/Kakao 설정이 그대로인지"를 설정 객체 모양으로 봤다.
// 모양은 그대로였고 런타임만 깨져 있었다. 그래서 여기서는 Auth.js가 실제로 받아갈 설정을
// 요청 단위로 골라 검사한다.
describe("per-request config selection (#19 OAuth regression guard)", () => {
  const callbackRequest = (path: string) =>
    new Request(`https://phraselog.app${path}`);

  test("recognizes the OAuth callback routes and nothing else", () => {
    expect(
      isOAuthCallbackRequest(callbackRequest("/api/auth/callback/google")),
    ).toBe(true);
    expect(
      isOAuthCallbackRequest(callbackRequest("/api/auth/callback/kakao")),
    ).toBe(true);

    // 이메일 콜백은 어댑터가 반드시 있어야 하고, signin/providers는 어댑터를 부르지 않는다.
    expect(
      isOAuthCallbackRequest(callbackRequest("/api/auth/callback/nodemailer")),
    ).toBe(false);
    expect(
      isOAuthCallbackRequest(callbackRequest("/api/auth/signin/google")),
    ).toBe(false);
    expect(isOAuthCallbackRequest(callbackRequest("/api/auth/providers"))).toBe(
      false,
    );
    expect(isOAuthCallbackRequest(undefined)).toBe(false);
  });

  test("hands the OAuth callback a config with no adapter at all", () => {
    // 어댑터가 있으면 @auth/core/lib/actions/callback/index.js:56이 getUserByAccount를 부르고,
    // handle-login.js:24의 `if (!adapter)` 조기 반환이 사라져 OAuth 프로비저닝 경로가 무너진다.
    const config = selectAuthConfig(
      callbackRequest("/api/auth/callback/google"),
    );

    expect(config.adapter).toBeUndefined();
    // 이메일 provider도 같이 빠져야 한다 — 남으면 Auth.js가 MissingAdapter로 설정을 거부한다.
    expect(providerIdsOf(config)).toEqual(["google", "kakao"]);
  });

  test("hands every other route the adapter the email provider needs", () => {
    for (const path of [
      "/api/auth/callback/nodemailer",
      "/api/auth/signin/nodemailer",
      "/api/auth/providers",
    ]) {
      const config = selectAuthConfig(callbackRequest(path));
      expect(config.adapter).toBeDefined();
      expect(providerIdsOf(config)).toEqual(["google", "kakao", "nodemailer"]);
    }

    // auth()/signIn()/signOut()은 요청 없이 호출된다.
    expect(selectAuthConfig(undefined).adapter).toBeDefined();
  });

  test("proves why the split is needed: the adapter throws on the OAuth methods", () => {
    // 이 세 개가 OAuth 콜백에서 호출되는 것들이다. 어댑터가 붙어 있으면 로그인이 실패한다.
    const adapter = createBffAdapter() as unknown as Record<
      string,
      (...args: unknown[]) => unknown
    >;

    for (const method of ["getUserByAccount", "linkAccount", "createSession"]) {
      expect(() => adapter[method]!({})).toThrow(method);
    }
  });
});

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

  // users.email은 매직링크가 기존 계정을 찾는 열쇠다(S03 same-email 케이스). 검증되지 않은
  // 주소가 그 열에 들어가면, 남의 주소를 주장해 만든 계정이 진짜 주인이 매직링크로 로그인할 때
  // 그 사람을 받아버린다. #19가 이 방향을 새로 열었다 — 전에는 OAuth가 거부했다.
  describe("provider email verification", () => {
    const signIn = (profile: Profile | undefined) =>
      buildAuthConfig({ provisionOAuthIdentity }).callbacks?.signIn?.({
        user: { email: "victim@example.com" } satisfies User,
        account: {
          provider: "kakao",
          providerAccountId: "kakao-9",
          type: "oauth",
        } satisfies Partial<Account> as Account,
        profile,
      } as never);

    test("refuses when Kakao says the address is not verified", async () => {
      const result = await signIn({
        email: "victim@example.com",
        kakao_account: { is_email_verified: false },
      } as Profile);

      expect(result).toBe("/login?callback_error=email_unverified");
      // 거절은 프로비저닝 이전이어야 한다 — 호출된 뒤에 막으면 users.email이 이미 쓰인다.
      expect(provisionOAuthIdentity).not.toHaveBeenCalled();
    });

    test("refuses when Google says the address is not verified", async () => {
      const result = await signIn({
        email: "victim@example.com",
        email_verified: false,
      } as Profile);

      expect(result).toBe("/login?callback_error=email_unverified");
      expect(provisionOAuthIdentity).not.toHaveBeenCalled();
    });

    test("allows a verified address and forwards the flag", async () => {
      await signIn({
        email: "victim@example.com",
        kakao_account: { is_email_verified: true },
      } as Profile);

      expect(provisionOAuthIdentity).toHaveBeenCalledWith(
        expect.objectContaining({ providerEmailVerified: true }),
      );
    });

    test("does not treat silence as a refusal", async () => {
      // 검증 필드를 아예 주지 않는 provider까지 막으면 로그인이 통째로 죽는다.
      await signIn({ email: "victim@example.com" } as Profile);

      expect(provisionOAuthIdentity).toHaveBeenCalledWith(
        expect.objectContaining({ providerEmailVerified: true }),
      );
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
      // provider가 검증 여부를 말하지 않았다. 말하지 않은 것을 미검증으로 취급하면
      // 그 필드를 안 주는 provider가 전부 막힌다.
      providerEmailVerified: true,
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
