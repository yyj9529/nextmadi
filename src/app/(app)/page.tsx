import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";

import { auth } from "@/auth";
import { getLandingExamples } from "@/lib/landing/get-landing-examples";
import {
  ACCOUNT_DELETED_MESSAGE,
  shouldShowAccountDeletedNotice,
} from "@/lib/user/account-deleted-notice";

export const metadata: Metadata = {
  // absolute: 루트 레이아웃의 "%s | NextMadi" 템플릿을 건너뛴다. 랜딩 title에 이미
  // 브랜드가 들어 있어서 템플릿을 타면 "NextMadi — ... | NextMadi"로 중복된다.
  title: { absolute: "NextMadi — 못한 말, 다음엔 할 수 있게" },
};

// S01 랜딩. (#33)
// - 이미 로그인한 사용자가 / 로 오면 /home(S04)으로 자동 이동한다(s01 AC3).
// - 예시 카드는 GET /landing/examples(공개, 무작위 3개)에서 실데이터로 채운다.
//   네트워크 오류/0건이면 getLandingExamples가 []를 돌려주고, 예시 섹션은 조용히
//   생략된다(s01 UI states). 방문자는 이 데이터 없이도 CTA로 행동할 수 있다.
type LandingPageProps = {
  searchParams?:
    | Promise<Record<string, string | string[] | undefined>>
    | Record<string, string | string[] | undefined>;
};

export default async function LandingPage({ searchParams }: LandingPageProps) {
  const session = await auth();
  if (session?.user?.id) {
    redirect("/home");
  }

  const examples = await getLandingExamples();
  // 계정 삭제 예약 직후 도착한 경우의 일회성 안내 (#24, s11 User Story 3 AC2).
  const accountDeleted = shouldShowAccountDeletedNotice(await searchParams);

  return (
    <div className="app-screen landing-screen">
      <header className="app-topbar">
        <span className="app-logo">NextMadi</span>
        <Link className="landing-login-link" href="/login">
          로그인
        </Link>
      </header>

      {accountDeleted ? (
        <p className="review-toast settings-toast" role="status">
          {ACCOUNT_DELETED_MESSAGE}
        </p>
      ) : null}

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

        {examples.length > 0 && (
          <section className="landing-examples" aria-label="상황 예시">
            <h2 className="landing-examples-title">이런 상황 어떻게 말할까?</h2>
            <ul className="landing-example-list">
              {examples.map((example) => (
                <li key={example.id}>
                  <Link
                    className="landing-example-card"
                    href={`/try?text=${encodeURIComponent(example.korean_text)}`}
                  >
                    “{example.korean_text}”
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
    </div>
  );
}
