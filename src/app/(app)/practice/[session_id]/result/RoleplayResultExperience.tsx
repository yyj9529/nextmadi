"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { PlayButton } from "@/components/app/PlayButton";
import type { RoleplayResult } from "@/lib/practice/types";
import { useRoleplayResult } from "./useRoleplayResult";

// S12b 롤플레이 RESULT. (#63 실데이터 연결 · 백엔드 #62)
// 로드: GET /api/practice/sessions/{id} → status/result_json 분기(useRoleplayResult).
//   result_json이 null이면 POST /api/practice/sessions/{id}/result로 1회 생성한다.
// 표현 저장: POST /api/practice/sessions/{id}/save-expression { recommended_expression_index }
//   + Idempotency-Key → 라이브러리에 추가(중복 저장은 성공으로 취급, s12b.md US2).
// 재생: POST /tts/playback (PlayButton, S07과 동일 플로우 = 현재 기본 목소리).
//   s12b.md US2-3의 "현재 코치 tts_voice_id" 재생은 공유 PlayButton 확장이 필요해 #121로 분리.

type RoleplayResultExperienceProps = {
  sessionId: string;
};

export function RoleplayResultExperience({
  sessionId,
}: RoleplayResultExperienceProps) {
  const { status, result, expressionId, retry } = useRoleplayResult(sessionId);

  if (status === "loading" || status === "generating") {
    return <ResultSkeleton generating={status === "generating"} />;
  }
  if (status === "notFound") {
    return <ResultNotFound />;
  }
  if (status === "notCompleted") {
    return <ResultNotCompleted sessionId={sessionId} />;
  }
  if (status === "abandoned") {
    return <ResultAbandoned />;
  }
  if (status === "error" || !result) {
    return <ResultError onRetry={retry} />;
  }

  return (
    <LoadedResult
      sessionId={sessionId}
      result={result}
      expressionId={expressionId}
    />
  );
}

type SaveState = "idle" | "saving" | "saved" | "error";

type LoadedResultProps = {
  sessionId: string;
  result: RoleplayResult;
  expressionId: string | null;
};

function LoadedResult({ sessionId, result, expressionId }: LoadedResultProps) {
  const router = useRouter();
  const [saveStates, setSaveStates] = useState<Record<number, SaveState>>({});
  const [toast, setToast] = useState<string | null>(null);
  // 카드별 진행 중 Idempotency-Key: 재시도가 같은 키를 써 중복 저장을 막는다(US2 AC4).
  const saveKeysRef = useRef<Record<number, string>>({});
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (toastTimerRef.current) {
        clearTimeout(toastTimerRef.current);
      }
    };
  }, []);

  const showToast = useCallback((message: string) => {
    setToast(message);
    if (toastTimerRef.current) {
      clearTimeout(toastTimerRef.current);
    }
    toastTimerRef.current = setTimeout(() => setToast(null), 2600);
  }, []);

  const saveExpression = useCallback(
    async (index: number) => {
      setSaveStates((current) => {
        if (current[index] === "saving" || current[index] === "saved") {
          return current;
        }
        return { ...current, [index]: "saving" };
      });

      const idempotencyKey =
        saveKeysRef.current[index] ?? crypto.randomUUID();
      saveKeysRef.current[index] = idempotencyKey;

      try {
        const res = await fetch(
          `/api/practice/sessions/${encodeURIComponent(sessionId)}/save-expression`,
          {
            method: "POST",
            headers: {
              "content-type": "application/json",
              "idempotency-key": idempotencyKey,
            },
            body: JSON.stringify({ recommended_expression_index: index }),
          },
        );
        if (!res.ok) {
          setSaveStates((current) => ({ ...current, [index]: "error" }));
          return;
        }
        // 성공(신규/중복 모두) — 키를 비우고 저장됨으로 확정한다(US2 AC2, AC4).
        delete saveKeysRef.current[index];
        setSaveStates((current) => ({ ...current, [index]: "saved" }));
        showToast("📚 책장에 추가했어요");
      } catch {
        setSaveStates((current) => ({ ...current, [index]: "error" }));
      }
    },
    [sessionId, showToast],
  );

  return (
    <div className="app-screen roleplay-result-screen">
      <header className="app-topbar">
        <h1 className="app-logo">오늘의 연습 결과</h1>
      </header>

      <div className="result12b-desktop-grid">
        <main className="result12b-main">
          <p className="encouragement-banner">🎉 {result.coach_encouragement}</p>

          <section className="result12b-section" aria-label="추천 표현">
            <h2 className="result12b-title result12b-title-practiced">
              💡 추천 표현
            </h2>
            {result.recommended_expressions.map((expression, index) => {
              const state = saveStates[index] ?? "idle";
              const saved = state === "saved";
              const saving = state === "saving";
              return (
                <div className="practiced-card" key={`${index}-${expression.english}`}>
                  <div className="practiced-card-main">
                    <p className="practiced-english">
                      {expression.english}
                      {expression.tone_label ? (
                        <span className="practiced-tone">
                          {expression.tone_label}
                        </span>
                      ) : null}
                    </p>
                    <p className="practiced-pron">
                      {expression.korean_pronunciation}
                    </p>
                  </div>
                  <div className="practiced-card-actions">
                    <PlayButton label={expression.english} />
                    <button
                      className={`save-chip${saved ? " is-saved" : ""}`}
                      type="button"
                      disabled={saved || saving}
                      aria-busy={saving}
                      onClick={() => void saveExpression(index)}
                    >
                      {saved
                        ? "저장됨 ✓"
                        : saving
                          ? "저장 중…"
                          : state === "error"
                            ? "다시 저장"
                            : "💾 저장"}
                    </button>
                  </div>
                  {state === "error" ? (
                    <p className="save-toast" role="alert">
                      저장하지 못했어요. 다시 시도해주세요.
                    </p>
                  ) : null}
                </div>
              );
            })}
          </section>
        </main>

        <aside className="result12b-side">
          {result.awkward_pairs.length > 0 ? (
            <section className="result12b-section" aria-label="이렇게도 좋아요">
              <h2 className="result12b-title result12b-title-awkward">
                💬 이렇게도 좋아요
              </h2>
              {result.awkward_pairs.map((pair, index) => (
                <div className="awkward-card" key={`${index}-${pair.user_said}`}>
                  <p className="awkward-pair">
                    <span className="awkward-said">
                      내가 한 말: “{pair.user_said}”
                    </span>
                    <span className="awkward-natural">
                      이렇게도 좋아요: <strong>“{pair.natural_version}”</strong>
                    </span>
                  </p>
                  <p className="awkward-comment">{pair.comment}</p>
                </div>
              ))}
            </section>
          ) : null}

          <section className="result12b-section" aria-label="발음 포커스">
            <h2 className="result12b-title result12b-title-pron">🔊 발음 포커스</h2>
            {result.pronunciation_focus_words.length > 0 ? (
              <div className="pron-focus-card">
                <p className="pron-focus-words">
                  {result.pronunciation_focus_words.join(", ")} 소리
                </p>
              </div>
            ) : (
              <div className="pron-focus-card">
                <p className="pron-focus-words">🎉 발음 깔끔했어요</p>
              </div>
            )}
          </section>

          {expressionId ? (
            <Link
              className="result12b-source-link"
              href={`/expression/${expressionId}`}
            >
              원본 표현 보기 →
            </Link>
          ) : null}
        </aside>

        <div className="result12b-footer">
          <button
            className="primary-button"
            type="button"
            onClick={() => router.push("/home")}
          >
            홈으로
          </button>
        </div>
      </div>

      {toast ? (
        <p className="result12b-toast" role="status">
          {toast}
        </p>
      ) : null}
    </div>
  );
}

function ResultSkeleton({ generating }: { generating: boolean }) {
  return (
    <div className="app-screen roleplay-result-screen" aria-busy="true">
      <header className="app-topbar">
        <h1 className="app-logo">오늘의 연습 결과</h1>
      </header>
      <div className="result12b-desktop-grid">
        <main className="result12b-main">
          <p className="roleplay-loading" role="status">
            {generating ? "코치가 정리 중…" : "결과를 불러오는 중…"}
          </p>
          <div className="result12b-section" aria-hidden="true">
            <span
              className="skeleton skeleton-line"
              style={{ width: "70%", height: 20 }}
            />
            <span
              className="skeleton skeleton-line"
              style={{ width: "90%", height: 56, marginTop: 12 }}
            />
            <span
              className="skeleton skeleton-line"
              style={{ width: "90%", height: 56, marginTop: 10 }}
            />
          </div>
        </main>
      </div>
    </div>
  );
}

function ResultNotFound() {
  const router = useRouter();
  useEffect(() => {
    const timer = setTimeout(() => router.replace("/home"), 2000);
    return () => clearTimeout(timer);
  }, [router]);

  return (
    <div className="app-screen roleplay-result-screen">
      <div className="library-empty">
        <p className="library-empty-title">결과를 찾지 못했어요</p>
        <p className="library-empty-sub">잠시 후 홈으로 이동할게요.</p>
      </div>
    </div>
  );
}

// 아직 안 끝난 연습 — 활성 세션으로 돌려보낸다(s12b.md US1 AC3).
function ResultNotCompleted({ sessionId }: { sessionId: string }) {
  const router = useRouter();
  useEffect(() => {
    router.replace(`/practice/${sessionId}`);
  }, [router, sessionId]);

  return (
    <div className="app-screen roleplay-result-screen">
      <div className="library-empty">
        <p className="library-empty-title">아직 끝나지 않은 연습이에요</p>
        <p className="library-empty-sub">연습 화면으로 돌아갈게요.</p>
      </div>
    </div>
  );
}

// 중간에 종료된 세션 — 결과를 생성하지 않는다(s12b.md US1 AC4).
function ResultAbandoned() {
  const router = useRouter();
  return (
    <div className="app-screen roleplay-result-screen">
      <div className="library-empty">
        <p className="library-empty-title">이 세션은 중간에 종료됐어요</p>
        <p className="library-empty-sub">
          다음엔 끝까지 함께 연습해요.
        </p>
        <button
          className="primary-button library-retry"
          type="button"
          onClick={() => router.push("/home")}
        >
          홈으로
        </button>
      </div>
    </div>
  );
}

function ResultError({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="app-screen roleplay-result-screen">
      <div className="library-empty">
        <p className="library-empty-title">결과를 만들지 못했어요</p>
        <p className="library-empty-sub">
          네트워크를 확인하고 다시 시도해주세요.
        </p>
        <button
          className="primary-button library-retry"
          type="button"
          onClick={onRetry}
        >
          다시 시도
        </button>
      </div>
    </div>
  );
}
