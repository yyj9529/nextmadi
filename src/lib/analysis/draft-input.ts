// S05a 텍스트 입력 모달의 초안 보존. (#43, screens/s05a.md US2)
//
// 모달을 텍스트가 있는 채로 닫으면 입력을 sessionStorage에 timestamp와 함께 적어두고,
// 5분 안에 다시 열면 복원한다. 5분이 지났으면 stale 초안을 버리고 빈 모달로 연다.
// 성공 제출(201) 시에는 호출자가 clearDraftInput으로 지운다.
//
// pending-save.ts와 같은 방침: window가 없는 서버 렌더/테스트 환경이나 프라이버시 모드로
// sessionStorage 접근이 막힌 경우 조용히 no-op(read는 null)로 동작한다.

export const DRAFT_INPUT_KEY = "phraselog_draft_input";

/** 초안 유효 기간 5분(ms). 이 시간이 지난 초안은 읽는 시점에 stale로 처리된다. */
export const DRAFT_TTL_MS = 5 * 60 * 1000;

// sessionStorage 직렬화 형태. saved_at은 epoch ms.
type DraftInputWire = {
  text: string;
  saved_at: number;
};

function getSessionStorage(): Storage | null {
  if (typeof window === "undefined") {
    return null;
  }
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

/** 텍스트가 있으면 현재 시각과 함께 초안을 저장한다. 빈 문자열이면 저장 대신 삭제한다. */
export function writeDraftInput(text: string, now: number = Date.now()): void {
  const storage = getSessionStorage();
  if (!storage) {
    return;
  }
  if (text.length === 0) {
    storage.removeItem(DRAFT_INPUT_KEY);
    return;
  }
  const wire: DraftInputWire = { text, saved_at: now };
  storage.setItem(DRAFT_INPUT_KEY, JSON.stringify(wire));
}

/**
 * 유효한 초안 텍스트를 읽는다. 없거나 형식이 깨졌으면 null.
 * TTL(5분)을 넘긴 stale 초안은 삭제한 뒤 null을 돌려준다 — 다음 열기는 빈 모달이 된다.
 */
export function readDraftInput(now: number = Date.now()): string | null {
  const storage = getSessionStorage();
  if (!storage) {
    return null;
  }
  const raw = storage.getItem(DRAFT_INPUT_KEY);
  if (!raw) {
    return null;
  }
  const parsed = parseDraftInput(raw);
  if (!parsed) {
    storage.removeItem(DRAFT_INPUT_KEY);
    return null;
  }
  if (now - parsed.saved_at > DRAFT_TTL_MS) {
    storage.removeItem(DRAFT_INPUT_KEY);
    return null;
  }
  return parsed.text;
}

export function clearDraftInput(): void {
  const storage = getSessionStorage();
  if (!storage) {
    return;
  }
  storage.removeItem(DRAFT_INPUT_KEY);
}

/** 직렬화 문자열을 검증 파싱한다. 어떤 불일치든 null을 돌려 호출자가 안전하게 무시하게 한다. */
export function parseDraftInput(raw: string): DraftInputWire | null {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof value !== "object" || value === null) {
    return null;
  }
  const record = value as Record<string, unknown>;
  const text = record.text;
  const savedAt = record.saved_at;
  if (typeof text !== "string" || text.length === 0) {
    return null;
  }
  if (typeof savedAt !== "number" || !Number.isFinite(savedAt)) {
    return null;
  }
  return { text, saved_at: savedAt };
}
