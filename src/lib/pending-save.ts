// 가입 전(pre-signup) 저장 보존 플로우의 클라이언트 상태. (#42, PRD 5.2)
//
// S07에서 비로그인 사용자가 "저장하기"를 누르면 어떤 분석을 어떤 변형으로 저장하려 했는지를
// sessionStorage에 적어두고 /login으로 보낸다. 로그인(+필요 시 S03b 코치 선택) 후 이 값을
// 감지해 추가 탭 없이 자동 저장한 뒤, S07 저장 완료 상태로 돌아온다.
//
// 원래 익명 session_token은 여기 담지 않는다 — 그 토큰은 httpOnly 쿠키로만 존재하고 BFF가
// 서버에서 읽어 claim에 사용한다(브라우저 번들에 노출 금지). 그래서 이 블롭은 이슈 #42가 정한
// { analysis_request_id, selected_variant_order } 형태 그대로다.

export const PENDING_SAVE_KEY = "pending_save";

export type PendingSave = {
  analysisRequestId: string;
  selectedVariantOrder: number;
};

// sessionStorage에는 API 계약과 동일한 snake_case로 직렬화한다.
type PendingSaveWire = {
  analysis_request_id: string;
  selected_variant_order: number;
};

function getSessionStorage(): Storage | null {
  // 서버 렌더/테스트 환경에서 window가 없을 수 있다 — 그때는 no-op로 동작한다.
  if (typeof window === "undefined") {
    return null;
  }
  try {
    return window.sessionStorage;
  } catch {
    // 프라이버시 모드 등에서 sessionStorage 접근이 막힐 수 있다.
    return null;
  }
}

export function writePendingSave(pending: PendingSave): void {
  const storage = getSessionStorage();
  if (!storage) {
    return;
  }
  const wire: PendingSaveWire = {
    analysis_request_id: pending.analysisRequestId,
    selected_variant_order: pending.selectedVariantOrder,
  };
  storage.setItem(PENDING_SAVE_KEY, JSON.stringify(wire));
}

/** 저장된 pending_save를 타입 안전하게 읽는다. 없거나 형식이 깨졌으면 null. */
export function readPendingSave(): PendingSave | null {
  const storage = getSessionStorage();
  if (!storage) {
    return null;
  }
  const raw = storage.getItem(PENDING_SAVE_KEY);
  if (!raw) {
    return null;
  }
  return parsePendingSave(raw);
}

export function clearPendingSave(): void {
  const storage = getSessionStorage();
  if (!storage) {
    return;
  }
  storage.removeItem(PENDING_SAVE_KEY);
}

/** 직렬화 문자열을 검증 파싱한다. 어떤 불일치든 null을 돌려 호출자가 안전하게 무시하게 한다. */
export function parsePendingSave(raw: string): PendingSave | null {
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
  const analysisRequestId = record.analysis_request_id;
  const selectedVariantOrder = record.selected_variant_order;
  if (typeof analysisRequestId !== "string" || analysisRequestId.length === 0) {
    return null;
  }
  if (
    typeof selectedVariantOrder !== "number" ||
    !Number.isInteger(selectedVariantOrder) ||
    selectedVariantOrder < 1 ||
    selectedVariantOrder > 3
  ) {
    return null;
  }
  return { analysisRequestId, selectedVariantOrder };
}

export type PostAuthDestination = {
  /** 인증/온보딩 직후 이동할 경로. */
  path: string;
  /** S07 도착 후 pending_save 자동 저장을 실행해야 하는지. */
  resume: boolean;
};

/**
 * 로그인/온보딩 직후 목적지를 결정한다. (S03 AC3 / S03b / S07 US3-AC4, 이슈 #42)
 *
 * <ul>
 *   <li>온보딩 전이면 코치 선택(S03b)이 우선한다.
 *   <li>pending_save가 있으면 S07 저장 완료 상태로 돌아가 자동 저장을 재개한다(/home 아님).
 *   <li>그 외에는 홈(S04).
 * </ul>
 */
export function resolvePostAuthDestination(input: {
  isOnboarded: boolean | null | undefined;
  pendingSave: PendingSave | null;
}): PostAuthDestination {
  if (!input.isOnboarded) {
    return { path: "/welcome/coach", resume: false };
  }
  if (input.pendingSave) {
    return {
      path: `/save/result/${input.pendingSave.analysisRequestId}`,
      resume: true,
    };
  }
  return { path: "/home", resume: false };
}

export function resolvePostOnboardingDestination(
  pendingSave: PendingSave | null,
): PostAuthDestination {
  return resolvePostAuthDestination({ isOnboarded: true, pendingSave });
}
