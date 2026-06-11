import type { Metadata } from "next";
import Link from "next/link";

import { mockLandingExamples } from "@/lib/mock-api";

export const metadata: Metadata = {
  title: "PhraseLog — 못한 말, 다음엔 할 수 있게",
};

// PPT 충실도 패스: s01_landing.PNG 기준 정적 목 데이터.
// 예시 카드는 GET /landing/examples 응답(무작위 3개)을 목으로 대체.
// 로그인 상태면 /home 자동 이동(스펙)이지만 목 패스에서는 비로그인 가정.
export default function LandingPage() {
  return (
    <div className="app-screen landing-screen">
      <header className="app-topbar">
        <span className="app-logo">PhraseLog</span>
        <Link className="landing-login-link" href="/login">
          로그인
        </Link>
      </header>

      <div className="landing-desktop-grid">
        <section className="landing-hero" aria-label="서비스 소개">
          <h1 className="landing-headline">
            못한 말,
            <br />
            다음엔 할 수 있게
          </h1>
          <p className="landing-sub">
            한국어로 말하면, 미국 상황에 맞는 영어 표현을
            <br />
            추천하고 저장해드려요
          </p>
          <Link className="primary-button landing-cta" href="/try">
            지금 1분 체험 →
          </Link>
        </section>

        <section className="landing-examples" aria-label="상황 예시">
          <h2 className="landing-examples-title">이런 상황 어떻게 말할까?</h2>
          <ul className="landing-example-list">
            {mockLandingExamples.map((example) => (
              <li key={example.id}>
                <Link
                  className="landing-example-card"
                  href={`/try?example=${example.id}`}
                >
                  “{example.korean_text}”
                </Link>
              </li>
            ))}
          </ul>
        </section>
      </div>
    </div>
  );
}
