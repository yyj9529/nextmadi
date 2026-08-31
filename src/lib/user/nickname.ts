// 닉네임 입력 규칙. (#56, S11 프로필)
//
// 서버/클라이언트 중립 모듈이다 — 설정 화면(client)과 BFF 검증 양쪽에서 쓸 수 있게
// "use client"도 "server-only"도 붙이지 않는다.
//
// 빈 값은 허용하지 않는다(s11.md edge case의 TBD를 #56에서 확정). 홈·코치 화면이
// display_name을 그대로 렌더하는데 빈 문자열이면 레이아웃이 비고, 무엇보다
// display_name = null(아직 안 정함)과 "" (일부러 지움)이 둘 다 "이름 없음"이 되어
// 데이터 모델에 구분 불가능한 상태가 두 개 생긴다. 지우고 싶으면 지금은 방법이 없다.

export const MAX_NICKNAME_LENGTH = 100;

export type NicknameValidation =
  | { ok: true; value: string }
  | { ok: false; reason: "empty" | "too_long" };

/**
 * 저장 가능한 닉네임인지 판정한다. 앞뒤 공백은 잘라내고, 길이는 잘라낸 뒤 값 기준으로 센다.
 */
export function validateNickname(raw: string): NicknameValidation {
  const value = raw.trim();

  if (value.length === 0) {
    return { ok: false, reason: "empty" };
  }
  if (value.length > MAX_NICKNAME_LENGTH) {
    return { ok: false, reason: "too_long" };
  }

  return { ok: true, value };
}
