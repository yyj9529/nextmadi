import { describe, expect, mock, test } from "bun:test";

const GET = mock(async () => new Response(null));
const POST = mock(async () => new Response(null));

mock.module("server-only", () => ({}));
mock.module("next-auth", () => ({
  default: () => ({
    handlers: { GET, POST },
    auth: mock(async () => null),
    signIn: mock(async () => undefined),
    signOut: mock(async () => undefined),
  }),
}));

const route = await import("./app/api/auth/[...nextauth]/route");

describe("NextAuth route handler", () => {
  test("exports NextAuth GET and POST handlers", () => {
    expect(route.GET).toBe(GET);
    expect(route.POST).toBe(POST);
  });
});
