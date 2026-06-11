"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { BackIcon, ChevronDownIcon } from "@/components/app/icons";

// S03 회원가입 / 로그인.
// 실제 구현: NextAuth signIn('google' | 'kakao' | 'email').
// 신규 가입(is_onboarded=false) → /welcome/coach, 기존 사용자 → /home.
// 목 패스: 모든 로그인이 신규 가입 흐름(/welcome/coach)으로 이동한다.
export function LoginExperience() {
  const router = useRouter();
  const [emailOpen, setEmailOpen] = useState(false);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");

  const completeLogin = () => {
    router.push("/welcome/coach");
  };

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

        <div className="login-providers">
          <button
            className="provider-button provider-google"
            type="button"
            onClick={completeLogin}
          >
            <span className="provider-badge provider-badge-google">G</span>
            Google로 계속하기
          </button>
          <button
            className="provider-button provider-kakao"
            type="button"
            onClick={completeLogin}
          >
            <span className="provider-badge provider-badge-kakao">K</span>
            카카오로 계속하기
          </button>
        </div>

        <p className="login-divider">— 또는 —</p>

        <button
          className="email-toggle"
          type="button"
          aria-expanded={emailOpen}
          onClick={() => setEmailOpen((open) => !open)}
        >
          ✉️ 이메일로 가입 <ChevronDownIcon size={14} />
        </button>

        {emailOpen ? (
          <div className="email-form">
            <input
              className="email-field"
              type="email"
              placeholder="이메일 주소"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
            <input
              className="email-field"
              type="password"
              placeholder="비밀번호 (8자 이상)"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
            <button
              className="primary-button"
              type="button"
              disabled={email.length === 0 || password.length < 8}
              onClick={completeLogin}
            >
              가입 완료
            </button>
          </div>
        ) : null}

        <p className="login-legal">
          가입 시 <Link href="/terms">이용약관</Link> 및{" "}
          <Link href="/privacy">개인정보처리방침</Link>에 동의하시는 것으로
          간주됩니다
        </p>
      </main>
    </div>
  );
}
