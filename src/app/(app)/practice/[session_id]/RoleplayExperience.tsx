"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";

import {
  ChevronDownIcon,
  CloseIcon,
  KeyboardIcon,
  MicIcon,
} from "@/components/app/icons";
import {
  MOCK_SESSION_ID,
  mockCoaches,
  mockSession,
  mockTurnScript,
} from "@/lib/mock-api";
import type { PracticeTurn } from "@/lib/mock-api";

// S12 롤플레이 가이드형 연습.
// 턴 제출: POST /practice/sessions/{session_id}/turns (음성 multipart 또는
// text_content JSON) → user_turn + coach_turn + feedback 응답.
// 목 패스: 마이크/전송 탭마다 mockTurnScript를 순서대로 소비하고,
// 스크립트가 끝나면(session_status=completed) 결과 화면으로 라우팅한다.
export function RoleplayExperience() {
  const router = useRouter();

  const [coachName, setCoachName] = useState("David");
  const [coachMenuOpen, setCoachMenuOpen] = useState(false);
  const [pendingCoach, setPendingCoach] = useState<string | null>(null);
  const [turns, setTurns] = useState<PracticeTurn[]>([mockSession.opening_turn]);
  const [scriptIndex, setScriptIndex] = useState(0);
  const [coachTyping, setCoachTyping] = useState(false);
  const [textMode, setTextMode] = useState(false);
  const [draft, setDraft] = useState("");
  const [confirmExit, setConfirmExit] = useState(false);
  const logRef = useRef<HTMLDivElement>(null);

  const userTurnsSent = scriptIndex;
  const sessionDone = scriptIndex >= mockTurnScript.length;

  useEffect(() => {
    logRef.current?.scrollTo({ top: logRef.current.scrollHeight });
  }, [turns, coachTyping]);

  const sendTurn = (typedText?: string) => {
    if (coachTyping || sessionDone) {
      return;
    }
    const script = mockTurnScript[scriptIndex];
    const userTurn: PracticeTurn =
      typedText && typedText.trim().length > 0
        ? { ...script.user, text_content: typedText.trim() }
        : script.user;

    setTurns((current) => [...current, userTurn]);
    setDraft("");
    setCoachTyping(true);

    window.setTimeout(() => {
      setTurns((current) => [...current, script.coach]);
      setCoachTyping(false);
      const nextIndex = scriptIndex + 1;
      setScriptIndex(nextIndex);
      if (nextIndex >= mockTurnScript.length) {
        // 마지막 턴 → session_status=completed → 결과 화면 자동 이동.
        window.setTimeout(() => {
          router.push(`/practice/${MOCK_SESSION_ID}/result`);
        }, 1400);
      }
    }, 900);
  };

  const resetWithCoach = (name: string) => {
    setCoachName(name);
    setTurns([mockSession.opening_turn]);
    setScriptIndex(0);
    setCoachTyping(false);
    setPendingCoach(null);
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
            onClick={() => setCoachMenuOpen((open) => !open)}
          >
            {coachName} <ChevronDownIcon size={14} />
          </button>
          {coachMenuOpen ? (
            <div className="detail-menu roleplay-coach-menu" role="menu">
              {mockCoaches.map((coach) => (
                <button
                  key={coach.id}
                  className="detail-menu-item"
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setCoachMenuOpen(false);
                    if (coach.display_name !== coachName) {
                      setPendingCoach(coach.display_name);
                    }
                  }}
                >
                  {coach.display_name}
                </button>
              ))}
            </div>
          ) : null}
        </div>
        <span className="roleplay-progress">
          {Math.min(userTurnsSent + 1, mockSession.planned_turns)} /{" "}
          {mockSession.planned_turns}
        </span>
      </header>

      <div className="roleplay-body" ref={logRef}>
        <div className="roleplay-stage" aria-hidden="true">
          <span className="roleplay-stage-avatar">{coachName[0]}</span>
          <span className="roleplay-stage-name">{coachName}</span>
          <span className="roleplay-stage-scenario">
            {mockSession.scenario_label}
          </span>
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
          {coachTyping ? (
            <p className="chat-bubble chat-coach chat-typing">…</p>
          ) : null}
          {sessionDone && !coachTyping ? (
            <p className="roleplay-complete" role="status">
              🎉 연습 완료! 결과를 정리하고 있어요…
            </p>
          ) : null}
        </div>
      </div>

      <footer className="roleplay-inputbar">
        <button
          className="icon-button"
          type="button"
          aria-label="텍스트 입력 전환"
          aria-pressed={textMode}
          onClick={() => setTextMode((mode) => !mode)}
        >
          <KeyboardIcon />
        </button>
        {textMode ? (
          <>
            <input
              className="roleplay-text-input"
              type="text"
              placeholder="영어로 답해보세요"
              value={draft}
              disabled={sessionDone}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  sendTurn(draft);
                }
              }}
            />
            <button
              className="roleplay-mic-button"
              type="button"
              aria-label="보내기"
              disabled={sessionDone}
              onClick={() => sendTurn(draft)}
            >
              →
            </button>
          </>
        ) : (
          <button
            className="roleplay-mic-button"
            type="button"
            aria-label="음성으로 말하기"
            disabled={sessionDone}
            onClick={() => sendTurn()}
          >
            <MicIcon size={22} />
          </button>
        )}
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
                onClick={() => setPendingCoach(null)}
              >
                취소
              </button>
              <button
                className="save-button confirm-primary"
                type="button"
                onClick={() => resetWithCoach(pendingCoach)}
              >
                다시 시작
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
