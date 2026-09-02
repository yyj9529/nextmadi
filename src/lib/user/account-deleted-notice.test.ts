import { describe, expect, test } from "bun:test";

import {
  ACCOUNT_DELETED_PARAM,
  shouldShowAccountDeletedNotice,
} from "./account-deleted-notice";

describe("shouldShowAccountDeletedNotice", () => {
  test("shows the notice when the param is present", () => {
    expect(
      shouldShowAccountDeletedNotice({ [ACCOUNT_DELETED_PARAM]: "1" }),
    ).toBe(true);
  });

  test("shows the notice for a bare param with an empty value", () => {
    expect(shouldShowAccountDeletedNotice({ [ACCOUNT_DELETED_PARAM]: "" })).toBe(
      true,
    );
  });

  test("stays hidden on a plain landing visit", () => {
    expect(shouldShowAccountDeletedNotice({})).toBe(false);
    expect(shouldShowAccountDeletedNotice(undefined)).toBe(false);
    expect(shouldShowAccountDeletedNotice({ q: "hello" })).toBe(false);
  });
});
