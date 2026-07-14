"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";

import {
  ChevronDownIcon,
  CloseIcon,
  MicIcon,
} from "@/components/app/icons";
import type { Coach } from "@/lib/mock-api";
import type {
  RoleplaySession,
  RoleplayTurn,
  SubmitTurnResult,
} from "@/lib/practice/types";
import { useRoleplaySession } from "./useRoleplaySession";

// 화면 렌더용 턴: 서버 RoleplayTurn에 표시용 피드백 카드를 붙인다(GET 복원 시엔 없음).
type DisplayFeedback = { natural_alternative: string; korean_comment: string };
type DisplayTurn = RoleplayTurn & { feedback: DisplayFeedback | null };

// S12 롤플레이 가이드형 연습. (#61 실데이터 연결)
// 로드: GET /api/practice/sessions/{id} (useRoleplaySession) → 대화 상태 복원.
// 턴 제출: POST /api/practice/sessions/{id}/turns { text_content } → user_turn + coach_turn +
//   feedback. turn_consumed=false면 retry_prompt만 안내(턴 미소비), session_status=completed면
//   결과 화면으로 자동 이동.
// 코치 전환: POST /api/practice/sessions { expression_id, coach_id } → 새 세션으로 라우팅.
// 음성 캡처(MediaRecorder)는 이번 티켓 범위 밖 — 텍스트 입력이 실경로다(mic 버튼은 비활성 안내).
// 참고: 서버측 abandon 엔드포인트가 없어 종료(X)는 클라이언트 홈 복귀만 한다(openapi 갭).

const COMPLETION_ROUTE_MS = 1600;

type RoleplayExperienceProps = {
  sessionId: string;
  coaches: Coach[];
};

export function RoleplayExperience({
  sessionId,
  coaches,
}: RoleplayExperienceProps) {
  const { status, session, retry } = useRoleplaySession(sessionId);

  if (status === "loading") {
    return <RoleplaySkeleton />;
  }
  if (status === "notFound") {
    return <RoleplayNotFound />;
  }
  if (status === "error" || !session) {
    return <RoleplayError onRetry={retry} />;
  }

  return <LoadedRoleplay key={session.id} session={session} coaches={coaches} />;
}

type LoadedRoleplayProps = {
  session: RoleplaySession;
  coaches: Coach[];
};

function LoadedRoleplay({ session, coaches }: LoadedRoleplayProps) {
  const router = useRouter();

  const coach = coaches.find((c) => c.id === session.coach_id) ?? null;
  const coachName = coach?.display_name ?? "코치";

  const [turns, setTurns] = useState<DisplayTurn[]>(
    session.turns.map((turn) => ({ ...turn, feedback: null })),
  );
  const [processing, setProcessing] = useState(false);
  const [draft, setDraft] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const [completed, setCompleted] = useState(session.status === "completed");
  const [coachMenuOpen, setCoachMenuOpen] = useState(false);
  const [pendingCoach, setPendingCoach] = useState<Coach | null>(null);
  const [switching, setSwitching] = useState(false);
  const [confirmExit, setConfirmExit] = useState(false);
  const logRef = useRef<HTMLDivElement>(null);
  // 전송 중복 방지 + 재시도 시 같은 Idempotency-Key를 유지하기 위한 refs.
  const sendingRef = useRef(false);
  const turnKeyRef = useRef<string | null>(null);
  const switchKeyRef = useRef<string | null>(null);

  const userTurnCount = turns.filter((turn) => turn.speaker === "user").length;
  const progress = Math.min(userTurnCount + 1, session.planned_turns);
  const inputDisabled = processing || completed;

  useEffect(() => {
    logRef.current?.scrollTo({ top: logRef.current.scrollHeight });
  }, [turns, processing]);

  // 이미 종료된 세션으로 복귀하면(새로고침/뒤로) 결과 화면으로 보낸다(s12.md edge case).
  useEffect(() => {
    if (session.status === "completed") {
      const timer = setTimeout(() => {
        router.replace(`/practice/${session.id}/result`);
      }, COMPLETION_ROUTE_MS);
      return () => clearTimeout(timer);
    }
    if (session.status === "abandoned") {
      router.replace("/home");
    }
    return undefined;
  }, [session.status, session.id, router]);

  const playCoachAudio = (url: string | null) => {
    if (!url) {
      return; // TTS 미제공(#30) — 텍스트 전용으로 계속.
    }
    try {
      void new Audio(url).play().catch(() => {});
    } catch {
      // 오디오 생성 실패는 무시하고 텍스트로 계속.
    }
  };

  // 진입/복귀 시 마지막 코치 발화 TTS 자동재생(US1 AC2). 현재 tts_audio_url은 null(#30)이라
  // 실질 무동작이지만, TTS가 붙으면 오프닝/복원된 코치 발화가 자동재생된다.
  useEffect(() => {
    const lastCoach = [...session.turns]
      .reverse()
      .find((turn) => turn.speaker === "coach");
    if (lastCoach?.tts_audio_url) {
      try {
        void new Audio(lastCoach.tts_audio_url).play().catch(() => {});
      } catch {
        // 무시하고 텍스트로 계속.
      }
    }
  }, [session.turns]);

  const sendTurn = async (text: string) => {
    const trimmed = text.trim();
    // ref 가드로 같은 tick의 이중 트리거(Enter+클릭)를 확실히 막는다(stale closure 회피).
    if (sendingRef.current || completed || trimmed.length === 0) {
      return;
    }
    sendingRef.current = true;
    // 재시도가 같은 키를 쓰도록 진행 중 키를 유지한다. 서버 응답을 받으면(성공/거절 무관)
    // 논리 턴이 확정되므로 초기화하고, 결과 불명(네트워크 오류)일 때만 키를 남겨 다음
    // 재시도가 중복 턴 없이 이어지게 한다(s12.md edge case).
    const idempotencyKey = turnKeyRef.current ?? crypto.randomUUID();
    turnKeyRef.current = idempotencyKey;
    setProcessing(true);
    setNotice(null);

    try {
      const res = await fetch(
        `/api/practice/sessions/${encodeURIComponent(session.id)}/turns`,
        {
          method: "POST",
          headers: {
            "content-type": "application/json",
            "idempotency-key": idempotencyKey,
          },
          body: JSON.stringify({ text_content: trimmed }),
        },
      );

      // 서버 응답 도달 = 이 논리 턴 확정. 다음 전송은 새 키로 시작한다.
      turnKeyRef.current = null;

      if (res.status === 404) {
        setNotice("세션을 찾지 못했어요. 잠시 후 홈으로 이동할게요.");
        setTimeout(() => router.replace("/home"), COMPLETION_ROUTE_MS);
        return;
      }
      if (res.status === 409) {
        // 세션이 더 이상 active가 아님(완료/포기) — 재시도가 아니라 결과로 보낸다.
        setNotice("이미 끝난 세션이에요. 결과로 이동할게요.");
        setCompleted(true);
        setTimeout(() => {
          router.replace(`/practice/${session.id}/result`);
        }, COMPLETION_ROUTE_MS);
        return;
      }
      if (!res.ok) {
        setNotice("메시지를 보내지 못했어요. 다시 시도해주세요.");
        return;
      }

      const result = (await res.json()) as SubmitTurnResult;

      // 빈/저신뢰 입력 → 턴 미소비, 재시도 안내만(s12.md US2 AC5).
      if (!result.turn_consumed) {
        setNotice(result.retry_prompt ?? "잘 안 들렸어요. 다시 말해주세요.");
        return;
      }

      setDraft("");
      const appended: DisplayTurn[] = [];
      if (result.user_turn) {
        // 피드백 카드는 user_turn에 붙여 렌더한다(show_feedback일 때만).
        const fb =
          result.feedback && result.feedback.show_feedback
            ? {
                natural_alternative: result.feedback.natural_alternative ?? "",
                korean_comment: result.feedback.korean_comment ?? "",
              }
            : null;
        appended.push({ ...result.user_turn, feedback: fb });
      }
      if (result.coach_turn) {
        appended.push({ ...result.coach_turn, feedback: null });
      }
      setTurns((current) => [...current, ...appended]);
      playCoachAudio(result.coach_turn?.tts_audio_url ?? null);

      if (result.session_status === "completed") {
        setCompleted(true);
        setTimeout(() => {
          router.replace(`/practice/${session.id}/result`);
        }, COMPLETION_ROUTE_MS);
      }
      // 결과 불명(네트워크 오류) — turnKeyRef를 남겨 재시도가 중복 없이 이어지게 한다.
    } catch {
      setNotice("메시지를 보내지 못했어요. 다시 시도해주세요.");
    } finally {
      sendingRef.current = false;
      setProcessing(false);
    }
  };

  const switchCoach = async (next: Coach) => {
    if (switching || !session.expression_id) {
      setPendingCoach(null);
      return;
    }
    setSwitching(true);
    // 코치 전환도 재시도 시 같은 키를 유지해 세션(=일일 슬롯) 중복 생성을 막는다.
    const idempotencyKey = switchKeyRef.current ?? crypto.randomUUID();
    switchKeyRef.current = idempotencyKey;
    try {
      const res = await fetch("/api/practice/sessions", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": idempotencyKey,
        },
        body: JSON.stringify({
          expression_id: session.expression_id,
          coach_id: next.id,
        }),
      });
      // 서버 응답 도달 = 확정. 다음 전환은 새 키로 시작한다.
      switchKeyRef.current = null;
      if (res.status === 429) {
        setNotice("오늘은 2번 다 썼어요. 내일 다시 만나요.");
        setPendingCoach(null);
        return;
      }
      if (res.status !== 201) {
        setNotice("코치를 바꾸지 못했어요. 다시 시도해주세요.");
        setPendingCoach(null);
        return;
      }
      const created = (await res.json()) as { id: string };
      router.replace(`/practice/${created.id}`);
    } catch {
      // 결과 불명 — switchKeyRef를 남겨 재시도가 중복 세션을 만들지 않게 한다.
      setNotice("코치를 바꾸지 못했어요. 다시 시도해주세요.");
      setPendingCoach(null);
    } finally {
      setSwitching(false);
    }
  };

  return (
    <div className="app-screen roleplay-screen">
      <header className="roleplay-topbar">
        <button
          className="icon-button"
          type="button"
          aria-label="세션 종료"
          onClick={() => setConfirmExit(true)}
        >
          <CloseIcon />
        </button>
        <div className="roleplay-coach-picker">
          <button
            className="roleplay-coach-name"
            type="button"
            aria-expanded={coachMenuOpen}
            disabled={!session.expression_id}
            onClick={() => setCoachMenuOpen((open) => !open)}
          >
            {coachName} <ChevronDownIcon size={14} />
          </button>
          {coachMenuOpen ? (
            <div className="detail-menu roleplay-coach-menu" role="menu">
              {coaches.map((c) => (
                <button
                  key={c.id}
                  className="detail-menu-item"
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setCoachMenuOpen(false);
                    if (c.id !== session.coach_id) {
                      setPendingCoach(c);
                    }
                  }}
                >
                  {c.display_name}
                </button>
              ))}
            </div>
          ) : null}
        </div>
        <span className="roleplay-progress">
          {progress} / {session.planned_turns}
        </span>
      </header>

      <div className="roleplay-body" ref={logRef}>
        <div className="roleplay-stage" aria-hidden="true">
          <span className="roleplay-stage-avatar">{coachName[0]}</span>
          <span className="roleplay-stage-name">{coachName}</span>
        </div>

        <div className="roleplay-log" aria-label="대화">
          {turns.map((turn) => (
            <div key={turn.id} className="roleplay-turn">
              <p
                className={`chat-bubble ${
                  turn.speaker === "coach" ? "chat-coach" : "chat-user"
                }`}
              >
                {turn.text_content}
              </p>
              {turn.feedback ? (
                <p className="chat-feedback">
                  🗣️ 더 자연스럽게: {turn.feedback.natural_alternative}
                  <span className="chat-feedback-comment">
                    {turn.feedback.korean_comment}
                  </span>
                </p>
              ) : null}
            </div>
          ))}
          {processing ? (
            <p className="chat-bubble chat-coach chat-typing">…</p>
          ) : null}
          {completed && !processing ? (
            <p className="roleplay-complete" role="status">
              🎉 연습 완료! 결과를 정리하고 있어요…
            </p>
          ) : null}
        </div>

        {notice ? (
          <p className="roleplay-notice" role="status">
            {notice}
          </p>
        ) : null}
      </div>

      <footer className="roleplay-inputbar">
        <button
          className="icon-button"
          type="button"
          aria-label="음성 입력 준비 중"
          title="음성 입력은 곧 제공됩니다"
          disabled
        >
          <MicIcon size={22} />
        </button>
        <input
          className="roleplay-text-input"
          type="text"
          placeholder="영어로 답해보세요"
          value={draft}
          disabled={inputDisabled}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              void sendTurn(draft);
            }
          }}
        />
        <button
          className="roleplay-mic-button"
          type="button"
          aria-label="보내기"
          disabled={inputDisabled || draft.trim().length === 0}
          onClick={() => void sendTurn(draft)}
        >
          →
        </button>
      </footer>

      {pendingCoach ? (
        <div className="modal-backdrop" role="presentation">
          <div
            className="confirm-dialog"
            role="alertdialog"
            aria-modal="true"
            aria-label="코치 변경 확인"
          >
            <p className="confirm-title">
              현재 세션을 끝내고 새 코치와 다시 시작할까요?
            </p>
            <div className="confirm-actions">
              <button
                className="retry-button"
                type="button"
                disabled={switching}
                onClick={() => setPendingCoach(null)}
              >
                취소
              </button>
              <button
                className="save-button confirm-primary"
                type="button"
                disabled={switching}
                aria-busy={switching}
                onClick={() => void switchCoach(pendingCoach)}
              >
                {switching ? "시작 중…" : "다시 시작"}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {confirmExit ? (
        <div className="modal-backdrop" role="presentation">
          <div
            className="confirm-dialog"
            role="alertdialog"
            aria-modal="true"
            aria-label="세션 종료 확인"
          >
            <p className="confirm-title">연습을 끝낼까요?</p>
            <p className="confirm-copy">지금 나가면 이번 세션은 종료돼요</p>
            <div className="confirm-actions">
              <button
                className="retry-button"
                type="button"
                onClick={() => setConfirmExit(false)}
              >
                계속하기
              </button>
              <button
                className="confirm-destructive"
                type="button"
                onClick={() => router.push("/home")}
              >
                종료
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function RoleplaySkeleton() {
  return (
    <div className="app-screen roleplay-screen" aria-busy="true">
      <header className="roleplay-topbar">
        <span className="skeleton skeleton-circle" aria-hidden="true" />
        <span
          className="skeleton skeleton-line"
          style={{ width: "30%" }}
          aria-hidden="true"
        />
        <span
          className="skeleton skeleton-line"
          style={{ width: "12%" }}
          aria-hidden="true"
        />
      </header>
      <div className="roleplay-body">
        <p className="roleplay-loading" role="status">
          코치를 깨우는 중…
        </p>
        <div className="roleplay-log">
          <p className="chat-bubble chat-coach chat-typing" aria-hidden="true">
            …
          </p>
        </div>
      </div>
    </div>
  );
}

function RoleplayNotFound() {
  const router = useRouter();
  useEffect(() => {
    const timer = setTimeout(() => router.replace("/home"), 2000);
    return () => clearTimeout(timer);
  }, [router]);

  return (
    <div className="app-screen roleplay-screen">
      <div className="library-empty">
        <p className="library-empty-title">연습 세션을 찾지 못했어요</p>
        <p className="library-empty-sub">잠시 후 홈으로 이동할게요.</p>
      </div>
    </div>
  );
}

function RoleplayError({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="app-screen roleplay-screen">
      <div className="library-empty">
        <p className="library-empty-title">세션을 불러오지 못했어요</p>
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
