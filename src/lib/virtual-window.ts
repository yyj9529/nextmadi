export type VirtualWindowInput = {
  itemCount: number;
  columnCount: number;
  viewportTop: number;
  viewportHeight: number;
  containerTop: number;
  rowHeight: number;
  rowGap: number;
  overscanRows: number;
};

export type VirtualWindow = {
  startIndex: number;
  endIndex: number;
  offsetY: number;
  totalHeight: number;
};

export function getVirtualWindow(input: VirtualWindowInput): VirtualWindow {
  const itemCount = Math.max(0, Math.floor(input.itemCount));
  const columnCount = Math.max(1, Math.floor(input.columnCount));
  const rowCount = Math.ceil(itemCount / columnCount);

  if (rowCount === 0) {
    return {
      startIndex: 0,
      endIndex: 0,
      offsetY: 0,
      totalHeight: 0,
    };
  }

  const rowStride = input.rowHeight + input.rowGap;
  const totalHeight = rowCount * input.rowHeight + (rowCount - 1) * input.rowGap;
  const viewportStart = Math.max(0, input.viewportTop - input.containerTop);
  const viewportEnd = viewportStart + Math.max(0, input.viewportHeight);

  const firstVisibleRow = Math.floor(viewportStart / rowStride);
  const lastVisibleRow = Math.ceil(viewportEnd / rowStride);
  const startRow = clamp(firstVisibleRow - input.overscanRows, 0, rowCount - 1);
  const endRow = clamp(lastVisibleRow + input.overscanRows, startRow, rowCount - 1);

  return {
    startIndex: startRow * columnCount,
    endIndex: Math.min(itemCount, (endRow + 1) * columnCount),
    offsetY: startRow * rowStride,
    totalHeight,
  };
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}
