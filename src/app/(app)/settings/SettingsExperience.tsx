"use client";

import { useState, useSyncExternalStore } from "react";
import Link from "next/link";

import { CoachCardList } from "@/components/app/CoachCards";
import { BackIcon, CloseIcon, PencilIcon } from "@/components/app/icons";
import { signOutToLanding } from "@/lib/auth/oauth-client";
import { ACCOUNT_DELETED_PARAM } from "@/lib/user/account-deleted-notice";
import type { Coach } from "@/lib/mock-api";
import type { UsageTodayResult } from "@/lib/usage/get-usage-today";
import type { GetMeResult } from "@/lib/user/get-me";
import { formatJoinedLabel } from "@/lib/user/joined-label";
import { MAX_NICKNAME_LENGTH, validateNickname } from "@/lib/user/nickname";

// S11 설정 (#56).
// 첫 페인트 데이터는 서버(page.tsx)가 GET /me + GET /usage/today + GET /coaches로 받아 넘긴다.
// 닉네임 저장: PATCH /api/me { display_name } — 빈 값은 저장할 수 없다(nickname.ts 참고).
// 코치 변경: PATCH /api/me { selected_coach_id } (모달은 S03b 카드 재사용).
// 로그아웃: NextAuth signOut → /.
// 계정 삭제 (#24): DELETE /api/me 가 200/204로 답한 뒤에만 로그아웃한다. 실패하면 세션을
// 유지한 채 에러 토스트만 띄운다 — 로그아웃부터 시키면 서버에 아무 일도 안 일어난 채
// 삭제된 것처럼 보인다 (docs/solutions/silent-failure-looks-like-success.md).

type SettingsExperienceProps = {
  me: GetMeResult;
  usage: UsageTodayResult;
  coaches: Coach[];
};

type Toast = { tone: "info" | "error"; message: string };

const NO_OP_SUBSCRIBE = () => () => {};

/**
 * hydration이 끝났는지. 서버에서는 false, 클라이언트에서는 true를 돌려준다.
 *
 * 로컬 시간대에 의존하는 값은 서버에서 계산하면 뷰어와 달라 hydration 불일치가 난다.
 * effect + setState로 마운트를 감지하는 대신 useSyncExternalStore를 쓴다 —
 * effect 내 동기 setState는 이 저장소에서 세 번 재발한 lint 실패다
 * (docs/solutions/set-state-in-effect.md).
 */
function useHydrated(): boolean {
  return useSyncExternalStore(
    NO_OP_SUBSCRIBE,
    () => true,
    () => false,
  );
}

export function SettingsExperience({
  me,
  usage,
  coaches,
}: SettingsExperienceProps) {
  const hydrated = useHydrated();
  const [nickname, setNickname] = useState(me.display_name ?? "");
  const [editingNickname, setEditingNickname] = useState(false);
  const [draftNickname, setDraftNickname] = useState(me.display_name ?? "");
  const [savingNickname, setSavingNickname] = useState(false);

  const [coachId, setCoachId] = useState(me.selected_coach_id);
  const [coachModalOpen, setCoachModalOpen] = useState(false);
  const [savingCoachId, setSavingCoachId] = useState<string | null>(null);

  const [confirmDeletion, setConfirmDeletion] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [toast, setToast] = useState<Toast | null>(null);

  const coach = coaches.find((item) => item.id === coachId) ?? null;
  const draftValidation = validateNickname(draftNickname);

  /** PATCH /api/me. 성공하면 true. 실패는 섹션 토스트로만 알린다. */
  async function patchMe(
    body: Record<string, unknown>,
    failureMessage: string,
  ): Promise<boolean> {
    let response: Response;
    try {
      response = await fetch("/api/me", {
        method: "PATCH",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
      });
    } catch {
      setToast({ tone: "error", message: failureMessage });
      return false;
    }

    if (!response.ok) {
      setToast({ tone: "error", message: failureMessage });
      return false;
    }

    return true;
  }

  async function handleSaveNickname() {
    if (savingNickname || !draftValidation.ok) {
      return;
    }
    const value = draftValidation.value;
    setSavingNickname(true);

    const saved = await patchMe(
      { display_name: value },
      "닉네임을 저장하지 못했어요. 다시 시도해주세요.",
    );

    setSavingNickname(false);
    if (!saved) {
      return;
    }

    setNickname(value);
    setDraftNickname(value);
    setEditingNickname(false);
    setToast({ tone: "info", message: "닉네임이 변경됐어요" });
  }

  async function handleSelectCoach(next: Coach) {
    if (savingCoachId !== null) {
      return;
    }
    // 같은 코치를 다시 고르면 저장할 게 없다 — 모달만 닫는다.
    if (next.id === coachId) {
      setCoachModalOpen(false);
      return;
    }

    setSavingCoachId(next.id);
    const saved = await patchMe(
      { selected_coach_id: next.id },
      "코치를 변경하지 못했어요. 다시 시도해주세요.",
    );
    setSavingCoachId(null);

    if (!saved) {
      return;
    }

    setCoachId(next.id);
    setCoachModalOpen(false);
    setToast({ tone: "info", message: "코치가 변경됐어요" });
  }

  /**
   * 계정 삭제 예약 (S11 User Story 3).
   *
   * 서버가 예약을 확인한 뒤에만 로그아웃한다. 안내 문구는 URL로 넘긴다 — 로그아웃은 전체
   * 네비게이션이라 이 컴포넌트의 토스트 state가 도착지까지 살아남지 못한다.
   */
  async function handleDeleteAccount() {
    if (deleting) {
      return;
    }
    setDeleting(true);

    let response: Response;
    try {
      response = await fetch("/api/me", { method: "DELETE" });
    } catch {
      setDeleting(false);
      setConfirmDeletion(false);
      setToast({
        tone: "error",
        message: "계정 삭제를 처리하지 못했어요. 다시 시도해주세요.",
      });
      return;
    }

    if (!response.ok) {
      setDeleting(false);
      setConfirmDeletion(false);
      setToast({
        tone: "error",
        message: "계정 삭제를 처리하지 못했어요. 다시 시도해주세요.",
      });
      return;
    }

    await signOutToLanding(`/?${ACCOUNT_DELETED_PARAM}=1`);
  }

  return (
    <div className="app-screen settings-screen">
      <header className="app-topbar result-topbar">
        <Link className="back-link" href="/home" aria-label="뒤로 가기">
          <BackIcon />
        </Link>
        <h1 className="result-title">설정</h1>
      </header>

      <div className="settings-desktop-grid">
        <div className="settings-column">
          <section className="settings-section" aria-label="프로필">
            <h2 className="settings-section-title">프로필</h2>
            <div className="settings-card">
              <div className="settings-row">
                <span className="settings-row-label">닉네임</span>
                {editingNickname ? (
                  <span className="nickname-edit">
                    <input
                      className="email-field nickname-field"
                      type="text"
                      aria-label="닉네임"
                      maxLength={MAX_NICKNAME_LENGTH}
                      value={draftNickname}
                      disabled={savingNickname}
                      onChange={(event) => setDraftNickname(event.target.value)}
                    />
                    <button
                      className="nickname-action"
                      type="button"
                      disabled={!draftValidation.ok || savingNickname}
                      onClick={() => void handleSaveNickname()}
                    >
                      {savingNickname ? "저장 중…" : "저장"}
                    </button>
                    <button
                      className="nickname-action is-muted"
                      type="button"
                      disabled={savingNickname}
                      onClick={() => {
                        // 취소는 API를 부르지 않는다(s11.md User Story 1).
                        setDraftNickname(nickname);
                        setEditingNickname(false);
                      }}
                    >
                      취소
                    </button>
                  </span>
                ) : (
                  <span className="settings-row-value">
                    {nickname}{" "}
                    <button
                      className="nickname-pencil"
                      type="button"
                      aria-label="닉네임 편집"
                      onClick={() => {
                        setDraftNickname(nickname);
                        setEditingNickname(true);
                      }}
                    >
                      <PencilIcon size={14} />
                    </button>
                  </span>
                )}
              </div>
              {editingNickname && !draftValidation.ok ? (
                <p className="settings-field-hint" role="status">
                  {draftValidation.reason === "empty"
                    ? "닉네임을 입력해주세요."
                    : `${MAX_NICKNAME_LENGTH}자까지 쓸 수 있어요.`}
                </p>
              ) : null}
              <div className="settings-row">
                <span className="settings-row-label">이메일</span>
                <span className="settings-row-value is-muted">{me.email}</span>
              </div>
              <div className="settings-row">
                <span className="settings-row-label">가입일</span>
                <span className="settings-row-value is-muted">
                  {/* 로컬 시간대 기준이라 hydration 이후에만 채운다. */}
                  {hydrated ? formatJoinedLabel(me.created_at) : ""}
                </span>
              </div>
            </div>
          </section>

          <section className="settings-section" aria-label="내 코치">
            <h2 className="settings-section-title">내 코치</h2>
            <div className="settings-coach-card">
              {coach ? (
                <>
                  <span className="coach-avatar">{coach.display_name}</span>
                  <span className="settings-coach-body">
                    <span className="settings-coach-name">
                      {coach.display_name}
                    </span>
                    <span className="settings-coach-summary">
                      {coach.persona_summary}
                    </span>
                  </span>
                </>
              ) : (
                <span className="settings-coach-body">
                  <span className="settings-coach-name">코치 미선택</span>
                  <span className="settings-coach-summary">
                    함께할 코치를 골라주세요.
                  </span>
                </span>
              )}
              <button
                className="settings-coach-change"
                type="button"
                disabled={coaches.length === 0}
                onClick={() => setCoachModalOpen(true)}
              >
                변경
              </button>
            </div>
          </section>
        </div>

        <div className="settings-column">
          <section className="settings-section" aria-label="오늘의 사용량">
            <h2 className="settings-section-title">오늘의 사용량</h2>
            <div className="settings-card">
              <p className="usage-row">
                🎤 롤플레이{" "}
                <strong>
                  {usage.roleplay_session_count} / {usage.daily_roleplay_limit}회
                </strong>
              </p>
              <p className="usage-row">
                ✏️ 표현 분석 <strong>{usage.analysis_count}회</strong>{" "}
                {usage.analysis_limit === null
                  ? "(무제한)"
                  : `/ ${usage.analysis_limit}회`}
              </p>
            </div>
          </section>

          <section className="settings-section" aria-label="계정">
            <h2 className="settings-section-title">계정</h2>
            <div className="settings-card">
              <button
                className="settings-text-button"
                type="button"
                onClick={() => void signOutToLanding()}
              >
                로그아웃
              </button>
              <button
                className="settings-text-button is-destructive"
                type="button"
                onClick={() => setConfirmDeletion(true)}
              >
                계정 삭제
              </button>
            </div>
          </section>

          <p className="login-legal settings-legal">
            <Link href="/terms">이용약관</Link> ·{" "}
            <Link href="/privacy">개인정보처리방침</Link>
          </p>
        </div>
      </div>

      {toast ? (
        <p
          className="review-toast settings-toast"
          role={toast.tone === "error" ? "alert" : "status"}
        >
          {toast.message}
        </p>
      ) : null}

      {coachModalOpen ? (
        <div
          className="modal-backdrop"
          role="presentation"
          onClick={() => {
            if (savingCoachId === null) {
              setCoachModalOpen(false);
            }
          }}
        >
          <div
            className="coach-modal"
            role="dialog"
            aria-modal="true"
            aria-label="코치 변경"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="sheet-header">
              <h2 className="sheet-title">코치 변경</h2>
              <button
                className="icon-button"
                type="button"
                aria-label="닫기"
                disabled={savingCoachId !== null}
                onClick={() => setCoachModalOpen(false)}
              >
                <CloseIcon />
              </button>
            </div>
            <CoachCardList
              coaches={coaches}
              selectedId={coachId}
              disabled={savingCoachId !== null}
              savingId={savingCoachId}
              onSelect={(next) => void handleSelectCoach(next)}
            />
          </div>
        </div>
      ) : null}

      {confirmDeletion ? (
        <div className="modal-backdrop" role="presentation">
          <div
            className="confirm-dialog"
            role="alertdialog"
            aria-modal="true"
            aria-label="계정 삭제 확인"
          >
            <p className="confirm-title">계정을 삭제할까요?</p>
            <p className="confirm-copy">
              14일 동안 로그인하지 않으면 모든 데이터가 영구 삭제됩니다. 그 안에
              로그인하면 복원돼요.
            </p>
            <div className="confirm-actions">
              <button
                className="retry-button"
                type="button"
                disabled={deleting}
                onClick={() => setConfirmDeletion(false)}
              >
                취소
              </button>
              <button
                className="confirm-destructive"
                type="button"
                disabled={deleting}
                onClick={() => void handleDeleteAccount()}
              >
                {deleting ? "처리 중…" : "계속 진행"}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
