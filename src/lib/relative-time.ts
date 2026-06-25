// created_at(ISO) → 한국어 상대 시간 라벨. S08 카드 표시용.
// 백엔드는 created_at만 주므로 표시 라벨은 클라이언트에서 계산한다(s08.md US1-AC1).

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const WEEK = 7 * DAY;

export function formatRelativeTime(
  iso: string,
  now: number = Date.now(),
): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) {
    return "";
  }
  const diff = now - then;

  if (diff < MINUTE) {
    return "방금 전";
  }
  if (diff < HOUR) {
    return `${Math.floor(diff / MINUTE)}분 전`;
  }
  if (diff < DAY) {
    return `${Math.floor(diff / HOUR)}시간 전`;
  }
  if (diff < 2 * DAY) {
    return "어제";
  }
  if (diff < WEEK) {
    return `${Math.floor(diff / DAY)}일 전`;
  }
  if (diff < 5 * WEEK) {
    return `${Math.floor(diff / WEEK)}주 전`;
  }
  // 한 달 이상은 날짜로 떨어뜨린다(예: 2026.04.01).
  const date = new Date(then);
  const yyyy = date.getFullYear();
  const mm = String(date.getMonth() + 1).padStart(2, "0");
  const dd = String(date.getDate()).padStart(2, "0");
  return `${yyyy}.${mm}.${dd}`;
}
