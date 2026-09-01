import { describe, expect, mock, test } from "bun:test";

mock.module("server-only", () => ({}));

const { createOAuthCallbackAdapter } = await import("./oauth-callback-adapter");

const adapter = () =>
  createOAuthCallbackAdapter() as unknown as Record<
    string,
    (...args: unknown[]) => unknown
  >;

describe("OAuth callback adapter", () => {
  // 아래 네 개는 @auth/core가 OAuth 콜백에서 실제로 부르는 것들이다. 셋이 null을 주고
  // linkAccount가 아무것도 하지 않아야, 어댑터가 있어도 흐름이 어댑터 없을 때와 같아진다.
  test("answers the OAuth-path lookups exactly as no storage would", async () => {
    const a = adapter();

    expect(await a.getUserByAccount({})).toBeNull();
    expect(await a.getUser("user-1")).toBeNull();
    expect(await a.getUserByEmail("someone@example.com")).toBeNull();
    expect(await a.linkAccount({})).toBeUndefined();
  });

  // 이게 이 어댑터의 핵심이다. handle-login.js:260의 createUser가 받은 객체를 그대로 돌려줘야
  // signIn 콜백이 붙여둔 PhraseLog 신원이 jwt 콜백까지 살아서 간다. 새 객체를 지어내면
  // 로그인은 "성공"하는데 세션에 user_id가 없다.
  test("hands back the very user it was given, provisioned fields included", async () => {
    const user = {
      id: "user-1",
      email: "new@example.com",
      name: "New User",
      emailVerified: null,
      phraselogUserId: "user-1",
      isOnboarded: false,
    };

    expect(await adapter().createUser(user)).toEqual(user);
  });

  // 저장하는 척하지 않는다. 이 어댑터가 이메일 경로에 잘못 붙었다면 매직링크를 조용히
  // 발급하는 것보다 500이 낫다 (docs/solutions/silent-failure-looks-like-success.md).
  test("throws on everything the OAuth callback never reaches", () => {
    const a = adapter();

    for (const method of [
      "createVerificationToken",
      "useVerificationToken",
      "updateUser",
      "createSession",
      "getSessionAndUser",
      "deleteSession",
      "deleteUser",
      "unlinkAccount",
    ]) {
      expect(() => a[method]!({})).toThrow(method);
    }
  });
});
