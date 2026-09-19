"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";

import { BottomNav } from "@/components/app/BottomNav";
import { GearIcon } from "@/components/app/icons";
import { HomeAnalysisCard } from "./HomeAnalysisCard";
import type { HomeDashboardResult } from "@/lib/home/get-home-dashboard";

// S04 홈 대시보드. GET /home/dashboard(BFF /api/home/dashboard) 실데이터 연결. (#55)
// - 코치 인사 카드: 브라우저 로컬 시간 기준 time-of-day 변형 + 사용자 이름
// - 코치 null이면 이름 없이 폴백 인사("안녕하세요!")
// - status banner: 복습 due 수 · 책장 권수
// - 최근 저장한 표현 2개(부족하면 있는 만큼) + 전체 보기(S08)
// 로딩=스켈레톤, 네트워크 오류=풀스크린 재시도, 빈 상태=플레이스홀더.

const GREETING_SUB = "오늘은 어떤 말을 못 하셨나요?";

// 06-12 아침 / 12-18 낮 / 18-22 저녁 / 22-06 늦은 시간 (s04.md AC US1-2).
function timeOfDayGreeting(hour: number): string {
  if (hour >= 6 && hour < 12) return "좋은 아침이에요";
  if (hour >= 12 && hour < 18) return "좋은 오후예요";
  if (hour >= 18 && hour < 22) return "좋은 저녁이에요";
  return "늦은 시간까지 수고 많으세요";
}

type LoadState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "loaded"; data: HomeDashboardResult };

export function HomeDashboard() {
  const [state, setState] = useState<LoadState>({ status: "loading" });

  // 상태는 async 콜백에서만 갱신한다(effect 본문 동기 setState 금지 규칙).
  const runFetch = useCallback(() => {
    let cancelled = false;
    fetch("/api/home/dashboard")
      .then((res) => (res.ok ? (res.json() as Promise<HomeDashboardResult>) : null))
      .then((data) => {
        if (cancelled) return;
        setState(data ? { status: "loaded", data } : { status: "error" });
      })
      .catch(() => {
        if (!cancelled) setState({ status: "error" });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // 초기 상태가 이미 loading이므로 effect는 조회만 시작한다.
  useEffect(() => runFetch(), [runFetch]);

  const retry = useCallback(() => {
    setState({ status: "loading" });
    runFetch();
  }, [runFetch]);

  return (
    <div className="app-screen home-screen has-bottom-nav">
      <header className="app-topbar">
        <span className="app-logo">NextMadi</span>
        <Link className="icon-button" href="/settings" aria-label="설정">
          <GearIcon />
        </Link>
      </header>

      {state.status === "loading" ? (
        <HomeSkeleton />
      ) : state.status === "error" ? (
        <div className="home-error" role="alert">
          <p className="home-error-title">홈 정보를 불러오지 못했어요.</p>
          <button
            className="primary-button home-retry"
            type="button"
            onClick={retry}
          >
            다시 시도
          </button>
        </div>
      ) : (
        <HomeLoaded data={state.data} />
      )}

      <BottomNav active="home" />
    </div>
  );
}

function HomeLoaded({ data }: { data: HomeDashboardResult }) {
  const { user, coach, recent_expressions, due_review_count, bookshelf_count } =
    data;

  // 코치가 null이면(온보딩 후 selected_coach_id 유실 등) 이름 없이 폴백. (s04.md edge)
  if (user.selected_coach_id && !coach) {
    console.warn(
      "[home] selected_coach_id present but coach unresolved; using fallback greeting",
    );
  }

  // 이 서브트리는 클라이언트 fetch 성공 후에만 렌더되므로(SSR은 스켈레톤),
  // 브라우저 로컬 시간을 여기서 계산해도 hydration 불일치가 없다. (s04.md: 로컬 시간 기준)
  const greetingPrefix = timeOfDayGreeting(new Date().getHours());
  const name = user.display_name?.trim();
  const greeting = name
    ? `${greetingPrefix}, ${name}님!`
    : `${greetingPrefix}!`;

  return (
    <div className="home-desktop-grid">
      <main className="home-main-column">
        <section className="coach-greeting" aria-label="코치 인사">
          {coach ? (
            <span className="coach-avatar">{coach.display_name}</span>
          ) : null}
          <div>
            <p className="greeting-title">{greeting}</p>
            <p className="greeting-sub">{GREETING_SUB}</p>
          </div>
        </section>

        <HomeAnalysisCard />
      </main>

      <aside className="home-side-column">
        <Link className="status-banner" href="/review">
          📚 복습할 카드 {due_review_count}개 · 내 책장 {bookshelf_count}권 →
        </Link>

        <section className="recent-section" aria-label="최근 저장한 표현">
          <div className="recent-header">
            <h2 className="recent-title">최근 저장한 표현</h2>
            <Link className="recent-link" href="/library">
              전체 보기 →
            </Link>
          </div>
          {recent_expressions.length === 0 ? (
            <p className="recent-empty">분석하면 여기에 모여요</p>
          ) : (
            <ul className="recent-list">
              {recent_expressions.map((expression) => (
                <li key={expression.id}>
                  <Link
                    className="recent-card"
                    href={`/expression/${expression.id}`}
                  >
                    • &ldquo;{expression.original_situation}&rdquo; →{" "}
                    {expression.english_text}
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>
      </aside>
    </div>
  );
}

function HomeSkeleton() {
  return (
    <div className="home-desktop-grid" aria-label="불러오는 중">
      <main className="home-main-column">
        <section className="coach-greeting">
          <span className="skeleton skeleton-circle" />
          <div style={{ flex: 1 }}>
            <span
              className="skeleton skeleton-line wide"
              style={{ width: "70%" }}
            />
            <span className="skeleton skeleton-line" style={{ width: "50%" }} />
          </div>
        </section>
        <div className="mic-card">
          <span
            className="skeleton skeleton-line wide"
            style={{ width: "60%" }}
          />
          <span className="skeleton skeleton-circle" style={{ margin: "16px auto" }} />
        </div>
      </main>
      <aside className="home-side-column">
        <span className="skeleton skeleton-line wide" style={{ width: "100%" }} />
        <div className="recent-section">
          <span className="skeleton skeleton-line" style={{ width: "40%" }} />
          <span className="skeleton skeleton-line wide" style={{ width: "90%" }} />
          <span className="skeleton skeleton-line wide" style={{ width: "90%" }} />
        </div>
      </aside>
    </div>
  );
}

