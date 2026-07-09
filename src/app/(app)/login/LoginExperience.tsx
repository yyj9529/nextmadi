"use client";

import Link from "next/link";

import { BackIcon } from "@/components/app/icons";
import { signInWithOAuthProvider } from "@/lib/auth/oauth-client";

// S03 회원가입 / 로그인.
// 실제 구현: NextAuth signIn('google' | 'kakao').
// 신규 가입(is_onboarded=false) → /welcome/coach, 기존 사용자 → /home.
// 이메일 가입(#19)은 미구현이라 UI를 노출하지 않는다 — 되는 척하는 폼은 사용자를 혼란시킨다.
// #19 구현 시 이메일 토글/폼과 관련 state를 되살린다. Kakao는 콘솔 검증 완료 후 활성화(#18).
type LoginExperienceProps = {
  callbackErrorMessage?: string | null;
};

export function LoginExperience({
  callbackErrorMessage = null,
}: LoginExperienceProps) {
  return (
    <div className="app-screen login-screen">
      <header className="app-topbar result-topbar">
        <Link className="back-link" href="/" aria-label="뒤로 가기">
          <BackIcon />
        </Link>
        <h1 className="result-title">로그인 / 가입</h1>
      </header>

      <main className="login-main">
        <p className="pending-save-banner">
          💾 가입하시면 분석한 표현이 자동 저장돼요
        </p>

        <h2 className="login-headline">5초만에 시작하기</h2>

        {callbackErrorMessage ? (
          <p className="login-error-banner" role="alert">
            {callbackErrorMessage}
          </p>
        ) : null}

        <div className="login-providers">
          <button
            className="provider-button provider-google"
            type="button"
            onClick={() => void signInWithOAuthProvider("google")}
          >
            <span className="provider-badge provider-badge-google">G</span>
            Google로 계속하기
          </button>
          <button
            className="provider-button provider-kakao"
            type="button"
            onClick={() => void signInWithOAuthProvider("kakao")}
          >
            <span className="provider-badge provider-badge-kakao">K</span>
            카카오로 계속하기
          </button>
        </div>

        <p className="login-legal">
          가입 시 <Link href="/terms">이용약관</Link> 및{" "}
          <Link href="/privacy">개인정보처리방침</Link>에 동의하시는 것으로
          간주됩니다
        </p>
      </main>
    </div>
  );
}
