import { describe, expect, mock, test } from "bun:test";
import type { Adapter } from "next-auth/adapters";

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

const { Auth } = await import("@auth/core");
const { buildAuthConfig } = await import("./auth");

/**
 * #157 회귀 방어.
 *
 * 이 파일의 테스트는 **한 프로세스 안에서 요청을 두 번** 태운다. 콜백 하나만 태우는 테스트는
 * 버그가 살아 있어도 통과한다 — `@auth/core`의 `hasEmail`은 모듈 전역이고, 이메일 provider가
 * 든 설정이 한 번 검사된 뒤에야 콜백 설정이 죽기 때문이다. 순서가 곧 재현 조건이다.
 */

const SECRET = "test-secret-that-is-long-enough-for-authjs";

const emailProviderDouble = {
  id: "nodemailer",
  name: "Email",
  type: "email" as const,
  from: "login@phraselog.app",
  maxAge: 60 * 60,
  options: {},
  sendVerificationRequest: async () => {},
};

const bffAdapterDouble = {
  createVerificationToken: async () => {
    throw new Error("not reached in this test");
  },
  useVerificationToken: async () => null,
  getUserByEmail: async () => null,
} as unknown as Adapter;

function capture() {
  const errors: unknown[] = [];
  return {
    errors,
    logger: {
      error: (error: unknown) => {
        errors.push(error);
      },
      warn: () => {},
      debug: () => {},
    },
  };
}

function runnable(config: ReturnType<typeof buildAuthConfig>, logger: object) {
  return {
    ...config,
    secret: SECRET,
    trustHost: true,
    basePath: "/api/auth",
    logger,
  };
}

const named = (errors: unknown[], name: string) =>
  errors.some((error) => (error as { name?: string })?.name === name);

const request = (path: string) => new Request(`https://phraselog.app${path}`);

describe("OAuth callback config after an email-provider config has been asserted", () => {
  test("survives a sign-in request in the same process", async () => {
    const full = capture();
    const callback = capture();

    // 1) 이메일 provider가 든 설정이 검사되며 assert.js의 전역 hasEmail이 켜진다.
    await Auth(
      request("/api/auth/providers"),
      runnable(
        buildAuthConfig({
          adapter: bffAdapterDouble,
          emailProvider: emailProviderDouble,
        }),
        full.logger,
      ) as never,
    );
    expect(named(full.errors, "MissingAdapter")).toBe(false);

    // 2) 같은 프로세스에서 콜백 설정. 여기가 #157에서 500으로 죽던 자리다.
    await Auth(
      request("/api/auth/callback/google?code=abc&state=xyz"),
      runnable(
        buildAuthConfig({
          includeEmailProvider: false,
          adapter: bffAdapterDouble,
          emailProvider: emailProviderDouble,
        }),
        callback.logger,
      ) as never,
    );

    // 이 요청은 state 쿠키가 없어서 어차피 실패한다. 하지만 설정 검증에서 죽으면 안 된다 —
    // 그건 Google/Kakao 로그인이 통째로 불가능하다는 뜻이다.
    expect(named(callback.errors, "MissingAdapter")).toBe(false);
    expect(
      callback.errors.map((error) => String(error)).join("\n"),
    ).not.toContain("requires an adapter");
  });

  // 위 테스트가 무엇을 지키는지 고정한다. 라이브러리가 이 전역 상태를 요청 단위로 바꾸면
  // 이 테스트가 깨지고, 그때는 콜백 전용 어댑터를 지워도 된다는 신호다.
  test("the library latch this works around is still there", async () => {
    const full = capture();
    const bare = capture();

    await Auth(
      request("/api/auth/providers"),
      runnable(
        buildAuthConfig({
          adapter: bffAdapterDouble,
          emailProvider: emailProviderDouble,
        }),
        full.logger,
      ) as never,
    );

    const withoutAdapter = {
      ...buildAuthConfig({
        includeEmailProvider: false,
        adapter: bffAdapterDouble,
        emailProvider: emailProviderDouble,
      }),
      adapter: undefined,
    };

    await Auth(
      request("/api/auth/callback/google?code=abc&state=xyz"),
      runnable(withoutAdapter, bare.logger) as never,
    );

    expect(named(bare.errors, "MissingAdapter")).toBe(true);
  });
});
