"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { CoachCardList } from "@/components/app/CoachCards";
import { BackIcon, CloseIcon, PencilIcon } from "@/components/app/icons";
import { mockCoaches, mockMe, mockUsageToday } from "@/lib/mock-api";

// S11 설정.
// 닉네임 저장: PATCH /me { display_name }.
// 코치 변경: PATCH /me { selected_coach_id } (모달은 S03b 카드 재사용).
// 로그아웃: NextAuth signOut → /. 계정 삭제: DELETE /me (14일 유예) → /.
export function SettingsExperience() {
  const router = useRouter();

  const [nickname, setNickname] = useState(mockMe.display_name);
  const [editingNickname, setEditingNickname] = useState(false);
  const [draftNickname, setDraftNickname] = useState(nickname);
  const [coachId, setCoachId] = useState(mockMe.selected_coach_id);
  const [coachModalOpen, setCoachModalOpen] = useState(false);
  const [confirmDeletion, setConfirmDeletion] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const coach =
    mockCoaches.find((item) => item.id === coachId) ?? mockCoaches[0];

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
                      maxLength={100}
                      value={draftNickname}
                      onChange={(event) => setDraftNickname(event.target.value)}
                    />
                    <button
                      className="nickname-action"
                      type="button"
                      onClick={() => {
                        setNickname(draftNickname.trim() || nickname);
                        setEditingNickname(false);
                        setNotice("닉네임이 변경됐어요");
                      }}
                    >
                      저장
                    </button>
                    <button
                      className="nickname-action is-muted"
                      type="button"
                      onClick={() => {
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
                      onClick={() => setEditingNickname(true)}
                    >
                      <PencilIcon size={14} />
                    </button>
                  </span>
                )}
              </div>
              <div className="settings-row">
                <span className="settings-row-label">이메일</span>
                <span className="settings-row-value is-muted">
                  {mockMe.email}
                </span>
              </div>
              <div className="settings-row">
                <span className="settings-row-label">가입일</span>
                <span className="settings-row-value is-muted">
                  {mockMe.joined_label}
                </span>
              </div>
            </div>
          </section>

          <section className="settings-section" aria-label="내 코치">
            <h2 className="settings-section-title">내 코치</h2>
            <div className="settings-coach-card">
              <span className="coach-avatar">{coach.display_name}</span>
              <span className="settings-coach-body">
                <span className="settings-coach-name">
                  {coach.display_name}
                </span>
                <span className="settings-coach-summary">
                  {coach.persona_summary}
                </span>
              </span>
              <button
                className="settings-coach-change"
                type="button"
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
                  {mockUsageToday.roleplay_session_count} /{" "}
                  {mockUsageToday.daily_roleplay_limit}회
                </strong>
              </p>
              <p className="usage-row">
                ✏️ 표현 분석 <strong>{mockUsageToday.analysis_count}회</strong>{" "}
                (무제한)
              </p>
            </div>
          </section>

          <section className="settings-section" aria-label="계정">
            <h2 className="settings-section-title">계정</h2>
            <div className="settings-card">
              <button
                className="settings-text-button"
                type="button"
                onClick={() => router.push("/")}
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

      {notice ? (
        <p className="review-toast settings-toast" role="status">
          {notice}
        </p>
      ) : null}

      {coachModalOpen ? (
        <div
          className="modal-backdrop"
          role="presentation"
          onClick={() => setCoachModalOpen(false)}
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
                onClick={() => setCoachModalOpen(false)}
              >
                <CloseIcon />
              </button>
            </div>
            <CoachCardList
              selectedId={coachId}
              onSelect={(next) => {
                setCoachId(next.id);
                setCoachModalOpen(false);
                setNotice("코치가 변경됐어요");
              }}
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
              14일 동안 로그인하지 않으면 모든 데이터가 영구 삭제됩니다. 그
              안에 로그인하면 복원돼요.
            </p>
            <div className="confirm-actions">
              <button
                className="retry-button"
                type="button"
                onClick={() => setConfirmDeletion(false)}
              >
                취소
              </button>
              <button
                className="confirm-destructive"
                type="button"
                onClick={() => router.push("/")}
              >
                계속 진행
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
