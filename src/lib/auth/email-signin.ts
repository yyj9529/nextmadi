/**
 * S03 이메일 매직링크의 클라이언트 측 순수 로직.
 *
 * "use client" 경계 바깥에 둔다. 서버 모듈(`src/auth.ts`)이 provider id를 여기서 가져가고,
 * 로그인 화면은 검증/결과 해석을 여기서 가져간다. 양쪽이 같은 문자열을 각자 적어두면
 * 언젠가 어긋난다.
 */

/** Auth.js Nodemailer provider의 고정 id. `signIn(...)`과 `account.provider` 분기가 같은 값을 쓴다. */
export const EMAIL_PROVIDER_ID = "nodemailer";

/** 서버가 판정하기 전, 명백히 틀린 입력만 걸러낸다. 여기서 통과해도 SMTP가 거절할 수 있다. */
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export const EMPTY_EMAIL_MESSAGE = "이메일 주소를 입력해주세요.";
export const MALFORMED_EMAIL_MESSAGE = "이메일 주소 형식이 올바르지 않아요.";
export const EMAIL_SEND_FAILED_MESSAGE =
  "이메일을 보내지 못했어요. 잠시 후 다시 시도해주세요.";

export type EmailValidationResult =
  | { valid: true; email: string }
  | { valid: false; message: string };

/**
 * 입력을 정규화하고 검증한다. 정규화는 백엔드(`OAuthIdentityService`)와 같은 규칙 —
 * 앞뒤 공백 제거 + 소문자.
 */
export function validateEmailInput(raw: string): EmailValidationResult {
  const email = raw.trim().toLowerCase();

  if (email.length === 0) {
    return { valid: false, message: EMPTY_EMAIL_MESSAGE };
  }

  if (!EMAIL_PATTERN.test(email)) {
    return { valid: false, message: MALFORMED_EMAIL_MESSAGE };
  }

  return { valid: true, email };
}

/** `signIn(..., { redirect: false })`가 돌려주는 것 중 우리가 보는 부분. */
export type EmailSignInResponse = {
  error?: string | null;
  ok?: boolean;
  url?: string | null;
} | null | undefined;

export type EmailSignInOutcome =
  | { sent: true }
  | { sent: false; message: string };

/**
 * 발송 성공/실패 판정.
 *
 * `error`가 있으면 실패다. 응답 자체가 없는 경우(네트워크 실패, provider 미등록으로
 * signIn이 조용히 undefined를 반환하는 경로)도 실패로 본다 — 확인되지 않은 성공을
 * "이메일을 확인해주세요"로 바꾸면 사용자는 오지 않을 메일을 기다린다.
 */
export function interpretEmailSignInResponse(
  response: EmailSignInResponse,
): EmailSignInOutcome {
  if (!response || response.error || response.ok === false) {
    return { sent: false, message: EMAIL_SEND_FAILED_MESSAGE };
  }

  return { sent: true };
}
