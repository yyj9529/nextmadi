import { describe, expect, test } from "bun:test";

import { getVirtualWindow } from "./virtual-window";

describe("getVirtualWindow", () => {
  test("returns all items when the list is smaller than the viewport", () => {
    expect(
      getVirtualWindow({
        itemCount: 6,
        columnCount: 2,
        viewportTop: 0,
        viewportHeight: 600,
        containerTop: 0,
        rowHeight: 96,
        rowGap: 12,
        overscanRows: 1,
      }),
    ).toEqual({
      startIndex: 0,
      endIndex: 6,
      offsetY: 0,
      totalHeight: 312,
    });
  });

  test("windows a large scrolled list by row and column", () => {
    expect(
      getVirtualWindow({
        itemCount: 500,
        columnCount: 2,
        viewportTop: 1_200,
        viewportHeight: 400,
        containerTop: 100,
        rowHeight: 96,
        rowGap: 12,
        overscanRows: 1,
      }),
    ).toEqual({
      startIndex: 18,
      endIndex: 32,
      offsetY: 972,
      totalHeight: 26_988,
    });
  });
});
