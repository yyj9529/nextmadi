import type { Metadata } from "next";
import Link from "next/link";

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
      id: "mock-1",
      koreanLabel: "줄 새치기",
      english: "Excuse me, I think there's a line.",
    },
    {
      id: "mock-2",
      koreanLabel: "병원 증상",
      english: "My daughter has had a fever...",
    },
  ],
};

function GearIcon() {
  return (
    <svg
      width="22"
      height="22"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </svg>
  );
}

function MicIcon() {
  return (
    <svg
      width="34"
      height="34"
      viewBox="0 0 24 24"
      fill="currentColor"
      aria-hidden="true"
    >
      <path d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3z" />
      <path d="M19 11a1 1 0 1 0-2 0 5 5 0 0 1-10 0 1 1 0 1 0-2 0 7 7 0 0 0 6 6.93V20H8a1 1 0 1 0 0 2h8a1 1 0 1 0 0-2h-3v-2.07A7 7 0 0 0 19 11z" />
    </svg>
  );
}

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

          <section className="mic-card" aria-label="새 분석 시작">
            <h1 className="mic-card-title">못한 말이 있었나요?</h1>
            <p className="mic-card-sub">탭하고 한국어로 말해보세요</p>
            <div>
              <button
                className="mic-button"
                type="button"
                aria-label="음성으로 말하기"
              >
                <MicIcon />
              </button>
            </div>
            <p className="mic-divider">또는</p>
            <button className="text-input-button" type="button">
              ✏️ 텍스트로 입력하기
            </button>
          </section>

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

      <nav className="bottom-nav" aria-label="하단 메뉴">
        <Link className="bottom-nav-item is-active" href="/home">
          홈
        </Link>
        <button className="bottom-nav-item" type="button">
          롤플레이
        </button>
        <button className="bottom-nav-item" type="button">
          기록
        </button>
        <Link className="bottom-nav-item" href="/review">
          복습
        </Link>
      </nav>
    </div>
  );
}
