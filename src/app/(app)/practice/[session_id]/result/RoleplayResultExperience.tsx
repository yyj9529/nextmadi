"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { PlayButton } from "@/components/app/PlayButton";
import {
  MOCK_EXPRESSION_ID,
  mockPracticeResult,
  mockUsageToday,
} from "@/lib/mock-api";

// S12b 롤플레이 RESULT.
// 표현 저장: POST /practice/sessions/{session_id}/save-expression
// { recommended_expression_index } → 목 패스에서는 "저장됨 ✓" 토글.
// 재생: POST /tts/playback (PlayButton이 시뮬레이션).
export function RoleplayResultExperience() {
  const router = useRouter();
  const result = mockPracticeResult;
  const [savedIndexes, setSavedIndexes] = useState<Set<number>>(new Set());

  return (
    <div className="app-screen roleplay-result-screen">
      <header className="app-topbar">
        <h1 className="app-logo">오늘의 연습 결과</h1>
      </header>

      <div className="result12b-desktop-grid">
        <main className="result12b-main">
          <p className="encouragement-banner">
            🎉 {result.coach_encouragement}
          </p>

          <section className="result12b-section" aria-label="오늘 연습한 표현">
            <h2 className="result12b-title result12b-title-practiced">
              ✅ 오늘 연습한 표현
            </h2>
            {result.recommended_expressions.map((expression, index) => {
              const saved = savedIndexes.has(index);
              return (
                <div className="practiced-card" key={expression.english}>
                  <div className="practiced-card-main">
                    <p className="practiced-english">{expression.english}</p>
                    <p className="practiced-pron">
                      {expression.korean_pronunciation}
                    </p>
                  </div>
                  <div className="practiced-card-actions">
                    <PlayButton label={expression.english} />
                    <button
                      className={`save-chip${saved ? " is-saved" : ""}`}
                      type="button"
                      disabled={saved}
                      onClick={() =>
                        setSavedIndexes((current) => {
                          const next = new Set(current);
                          next.add(index);
                          return next;
                        })
                      }
                    >
                      {saved ? "저장됨 ✓" : "💾 저장"}
                    </button>
                  </div>
                </div>
              );
            })}
          </section>
        </main>

        <aside className="result12b-side">
          {result.awkward_pairs.length > 0 ? (
            <section className="result12b-section" aria-label="어색했던 표현">
              <h2 className="result12b-title result12b-title-awkward">
                ⚠️ 어색했던 표현
              </h2>
              {result.awkward_pairs.map((pair) => (
                <div className="awkward-card" key={pair.user_said}>
                  <p className="awkward-pair">
                    “{pair.user_said}” → <strong>“{pair.natural_version}”</strong>
                  </p>
                  <p className="awkward-comment">{pair.comment}</p>
                </div>
              ))}
            </section>
          ) : null}

          <section className="result12b-section" aria-label="발음 주의">
            <h2 className="result12b-title result12b-title-pron">
              🔊 발음 주의
            </h2>
            {result.pronunciation_focus_words.length > 0 ? (
              <div className="pron-focus-card">
                <p className="pron-focus-words">
                  {result.pronunciation_focus_words.join(", ")} 소리
                </p>
                <p className="pron-focus-comment">
                  {result.pronunciation_focus_comment}
                </p>
              </div>
            ) : (
              <div className="pron-focus-card">
                <p className="pron-focus-words">🎉 발음 깔끔했어요</p>
              </div>
            )}
          </section>

          <Link
            className="result12b-source-link"
            href={`/expression/${MOCK_EXPRESSION_ID}`}
          >
            원본 표현 보기 →
          </Link>
        </aside>

        <div className="result12b-footer">
          <button
            className="primary-button"
            type="button"
            onClick={() => router.push("/home")}
          >
            홈으로 · 오늘 {mockUsageToday.roleplay_session_count}/
            {mockUsageToday.daily_roleplay_limit}회 사용
          </button>
        </div>
      </div>
    </div>
  );
}
