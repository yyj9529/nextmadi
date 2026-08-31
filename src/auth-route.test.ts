import { describe, expect, mock, test } from "bun:test";

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

// 라우트가 내보내는 것이 `@/auth`가 만든 바로 그 핸들러인지를 본다.
//
// 여기서 로컬 mock 함수와 비교하면 안 된다. `@/auth`는 여러 테스트 파일이 함께 쓰는 모듈이라
// 먼저 import한 파일의 mock으로 한 번만 평가되고, 그 뒤로는 이 파일의 mock이 쓰이지 않는다.
// 실제로 auth 테스트 파일이 하나 늘었을 때 이 테스트가 "serializes to the same string"으로
// 깨졌다 — 라우트는 멀쩡했고 비교 대상이 틀렸던 것이다.
const auth = await import("./auth");
const route = await import("./app/api/auth/[...nextauth]/route");

describe("NextAuth route handler", () => {
  test("exports NextAuth GET and POST handlers", () => {
    expect(route.GET).toBe(auth.handlers.GET);
    expect(route.POST).toBe(auth.handlers.POST);
  });
});
