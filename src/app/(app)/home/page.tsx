import type { Metadata } from "next";
import Link from "next/link";

import { BottomNav } from "@/components/app/BottomNav";
import { GearIcon } from "@/components/app/icons";
import { HomeAnalysisCard } from "./HomeAnalysisCard";

export const metadata: Metadata = {
  title: "홈",
};

// PPT 충실도 패스: s04_home.PNG 목업과 동일한 정적 목 데이터.
// API 연동(GET /home/dashboard)은 후속 패스에서 교체.
const mockDashboard = {
  coachName: "Mia",
  greeting: "좋은 아침이에요, 지영님!",
  greetingSub: "오늘은 어떤 말을 못 하셨나요?",
  dueReviewCount: 5,
  bookshelfCount: 47,
  recentExpressions: [
    {
      id: "mock-expression-1",
      koreanLabel: "줄 새치기",
      english: "Excuse me, I think there's a line.",
    },
    {
      id: "mock-expression-2",
      koreanLabel: "병원 증상",
      english: "My daughter has had a fever...",
    },
  ],
};

export default function HomePage() {
  return (
    <div className="app-screen home-screen has-bottom-nav">
      <header className="app-topbar">
        <span className="app-logo">PhraseLog</span>
        <Link className="icon-button" href="/settings" aria-label="설정">
          <GearIcon />
        </Link>
      </header>

      <div className="home-desktop-grid">
        <main className="home-main-column">
          <section className="coach-greeting" aria-label="코치 인사">
            <span className="coach-avatar">{mockDashboard.coachName}</span>
            <div>
              <p className="greeting-title">{mockDashboard.greeting}</p>
              <p className="greeting-sub">{mockDashboard.greetingSub}</p>
            </div>
          </section>

          <HomeAnalysisCard />
        </main>

        <aside className="home-side-column">
          <Link className="status-banner" href="/review">
            📚 복습할 카드 {mockDashboard.dueReviewCount}개 · 내 책장{" "}
            {mockDashboard.bookshelfCount}권 →
          </Link>

          <section className="recent-section" aria-label="최근 저장한 표현">
            <div className="recent-header">
              <h2 className="recent-title">최근 저장한 표현</h2>
              <Link className="recent-link" href="/library">
                전체 보기 →
              </Link>
            </div>
            <ul className="recent-list">
              {mockDashboard.recentExpressions.map((expression) => (
                <li key={expression.id}>
                  <Link
                    className="recent-card"
                    href={`/expression/${expression.id}`}
                  >
                    • &ldquo;{expression.koreanLabel}&rdquo; →{" "}
                    {expression.english}
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        </aside>
      </div>

      <BottomNav active="home" />
    </div>
  );
}
