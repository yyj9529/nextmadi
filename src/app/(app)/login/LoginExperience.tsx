"use client";

import { useState } from "react";
import Link from "next/link";

import { BackIcon, ChevronDownIcon } from "@/components/app/icons";
import {
  sendEmailMagicLink,
  signInWithOAuthProvider,
} from "@/lib/auth/oauth-client";
import { validateEmailInput } from "@/lib/auth/email-signin";

// S03 회원가입 / 로그인.
// 실제 구현: NextAuth signIn('google' | 'kakao' | 'nodemailer').
// 신규 가입(is_onboarded=false) → /welcome/coach, 기존 사용자 → /home.
// 버튼 순서는 S03 AC1 고정: Google → Kakao → 이메일.
// 이메일은 매직링크 발송까지만 여기서 처리하고, 링크 클릭 후 라우팅은 /auth/complete가 맡는다.
type LoginExperienceProps = {
  callbackErrorMessage?: string | null;
  /** 만료/발송 실패로 되돌아온 경우 이메일 폼을 펼친 채로 시작한다 (S03 "새 링크 요청"). */
  promptEmailRetry?: boolean;
};

/** 발송 상태. "sent"에 도달하는 유일한 경로는 서버가 실패를 반환하지 않은 경우뿐이다. */
type SendState = "idle" | "sending" | "sent";

export function LoginExperience({
  callbackErrorMessage = null,
  promptEmailRetry = false,
}: LoginExperienceProps) {
  const [emailOpen, setEmailOpen] = useState(promptEmailRetry);
  const [email, setEmail] = useState("");
  const [sendState, setSendState] = useState<SendState>("idle");
  const [emailError, setEmailError] = useState<string | null>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);

  async function handleEmailSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();

    const validation = validateEmailInput(email);
    if (!validation.valid) {
      setEmailError(validation.message);
      return;
    }

    setEmailError(null);
    setSendState("sending");

    const outcome = await sendEmailMagicLink(validation.email);

    if (!outcome.sent) {
      // 실패는 실패로 보여준다. 여기서 "sent"로 넘어가면 오지 않을 메일을 기다리게 된다.
      setSendState("idle");
      setEmailError(outcome.message);
      return;
    }

    setSentTo(validation.email);
    setSendState("sent");
  }

  function resetToForm() {
    setSendState("idle");
    setSentTo(null);
    setEmailError(null);
  }

  const sending = sendState === "sending";

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

        {sendState === "sent" ? (
          <section className="email-sent" aria-live="polite">
            <p className="email-sent-title">이메일을 확인해주세요</p>
            <p className="email-sent-body">
              {sentTo}로 로그인 링크를 보냈어요. 링크는 24시간 후 만료돼요.
            </p>
            <button
              className="email-toggle"
              type="button"
              onClick={resetToForm}
            >
              다른 주소로 다시 보내기
            </button>
          </section>
        ) : (
          <>
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

            <p className="login-divider">— 또는 —</p>

            <button
              className="email-toggle"
              type="button"
              aria-expanded={emailOpen}
              onClick={() => setEmailOpen((open) => !open)}
            >
              ✉️ 이메일로 계속하기 <ChevronDownIcon size={14} />
            </button>

            {emailOpen ? (
              <form className="email-form" noValidate onSubmit={handleEmailSubmit}>
                <label className="sr-only" htmlFor="login-email">
                  이메일 주소
                </label>
                <input
                  className="email-field"
                  id="login-email"
                  name="email"
                  type="email"
                  autoComplete="email"
                  placeholder="이메일 주소"
                  value={email}
                  aria-invalid={emailError !== null}
                  aria-describedby={emailError ? "login-email-error" : undefined}
                  disabled={sending}
                  onChange={(event) => {
                    setEmail(event.target.value);
                    setEmailError(null);
                  }}
                />
                {emailError ? (
                  <p className="email-error" id="login-email-error" role="alert">
                    {emailError}
                  </p>
                ) : null}
                <button
                  className="primary-button"
                  type="submit"
                  disabled={sending}
                >
                  {sending ? "보내는 중…" : "로그인 링크 받기"}
                </button>
                <p className="email-hint">
                  비밀번호 없이, 메일로 받은 링크를 열면 로그인돼요.
                </p>
              </form>
            ) : null}
          </>
        )}

        <p className="login-legal">
          가입 시 <Link href="/terms">이용약관</Link> 및{" "}
          <Link href="/privacy">개인정보처리방침</Link>에 동의하시는 것으로
          간주됩니다
        </p>
      </main>
    </div>
  );
}
