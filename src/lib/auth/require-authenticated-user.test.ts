import { beforeEach, describe, expect, mock, test } from "bun:test";

const authMock = mock(
  async (): Promise<{ user: { id: string } } | null> => ({
    user: { id: "user-1" },
  }),
);
const redirectMock = mock((path: string) => {
  throw new Error(`redirect:${path}`);
});

mock.module("@/auth", () => ({
  auth: authMock,
}));
mock.module("next/navigation", () => ({
  redirect: redirectMock,
}));

const { requireAuthenticatedUserId } = await import(
  "./require-authenticated-user"
);

describe("requireAuthenticatedUserId", () => {
  beforeEach(() => {
    authMock.mockImplementation(async () => ({ user: { id: "user-1" } }));
    redirectMock.mockClear();
  });

  test("returns the authenticated user id", async () => {
    await expect(requireAuthenticatedUserId()).resolves.toBe("user-1");
    expect(redirectMock).not.toHaveBeenCalled();
  });

  test("redirects anonymous users to login", async () => {
    authMock.mockImplementation(async () => null);

    await expect(requireAuthenticatedUserId()).rejects.toThrow(
      "redirect:/login",
    );
    expect(redirectMock).toHaveBeenCalledWith("/login");
  });
});
