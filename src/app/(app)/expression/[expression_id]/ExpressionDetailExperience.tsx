"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { BackIcon, ChevronDownIcon, MoreIcon } from "@/components/app/icons";
import { PlayButton } from "@/components/app/PlayButton";
import {
  MOCK_SESSION_ID,
  mockExpressionDetail,
  mockExpressionReviewMeta,
} from "@/lib/mock-api";

// S09 표현 상세.
// 데이터: GET /expressions/{expression_id} (목: mockExpressionDetail).
// 연습하기: POST /practice/sessions { expression_id } → /practice/{session_id}.
// 복습 큐 토글: POST /review/{review_card_id}/remove-from-queue ·
// re-add-to-queue — 목 패스에서는 로컬 상태만 바꾼다.
// 삭제: DELETE /expressions/{id} → 확인 후 /library 복귀.

const toneClassByOrder: Record<number, string> = {
  1: "tone-polite",
  2: "tone-direct",
  3: "tone-firm",
};

export function ExpressionDetailExperience() {
  const router = useRouter();
  const expression = mockExpressionDetail;

  const [expandedId, setExpandedId] = useState(expression.selected_variant_id);
  const [menuOpen, setMenuOpen] = useState(false);
  const [inQueue, setInQueue] = useState(true);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const toggleQueue = () => {
    setInQueue((current) => {
      setNotice(
        current ? "복습 큐에서 제거했어요" : "복습 큐에 다시 추가했어요",
      );
      return !current;
    });
    setMenuOpen(false);
  };

  return (
    <div className="app-screen detail-screen">
      <div className="detail-desktop-grid">
        <main className="detail-main-column">
          <header className="app-topbar result-topbar detail-topbar">
            <Link className="back-link" href="/library" aria-label="뒤로 가기">
              <BackIcon />
            </Link>
            <div className="detail-menu-wrap">
              <button
                className="icon-button"
                type="button"
                aria-label="관리 메뉴"
                aria-expanded={menuOpen}
                onClick={() => setMenuOpen((open) => !open)}
              >
                <MoreIcon />
              </button>
              {menuOpen ? (
                <div className="detail-menu" role="menu">
                  <button
                    className="detail-menu-item"
                    type="button"
                    role="menuitem"
                    onClick={toggleQueue}
                  >
                    {inQueue ? "복습 큐에서 제거" : "복습 큐에 다시 추가"}
                  </button>
                  <button
                    className="detail-menu-item is-destructive"
                    type="button"
                    role="menuitem"
                    onClick={() => {
                      setMenuOpen(false);
                      setConfirmDelete(true);
                    }}
                  >
                    삭제
                  </button>
                </div>
              ) : null}
            </div>
          </header>

          <p className="input-summary detail-situation">
            “{expression.original_situation}”
          </p>

          <p className="status-banner detail-review-banner">
            📅 다음 복습: {mockExpressionReviewMeta.nextReviewLabel} · 최근:{" "}
            {mockExpressionReviewMeta.lastResultLabel}
          </p>

          <div className="detail-actions">
            <button
              className="practice-button"
              type="button"
              onClick={() => router.push(`/practice/${MOCK_SESSION_ID}`)}
            >
              🎤 이 표현으로 연습하기
            </button>
          </div>

          {notice ? (
            <p className="detail-notice" role="status">
              {notice}
            </p>
          ) : null}
        </main>

        <aside className="detail-side-column">
          <ul className="expression-list" aria-label="표현 변형">
            {expression.variants.map((variant) => {
              const expanded = variant.id === expandedId;
              return (
                <li
                  className={`expression-card detail-variant${
                    expanded ? " is-expanded" : ""
                  }`}
                  key={variant.id}
                >
                  <button
                    className="detail-variant-toggle"
                    type="button"
                    aria-expanded={expanded}
                    onClick={() => setExpandedId(variant.id)}
                  >
                    <span className="expression-main">
                      <span
                        className={`tone-badge ${toneClassByOrder[variant.variant_order]}`}
                      >
                        {variant.tone_label}
                      </span>
                      <span className="expression-english">
                        {variant.english_text}
                      </span>
                    </span>
                    {expanded ? null : (
                      <span className="detail-variant-chevron" aria-hidden="true">
                        <ChevronDownIcon />
                      </span>
                    )}
                  </button>
                  {expanded ? (
                    <div className="detail-variant-body">
                      <div className="detail-variant-pron-row">
                        <p className="expression-pron">
                          {variant.ipa} · {variant.korean_pronunciation}
                        </p>
                        <PlayButton label={variant.english_text} />
                      </div>
                      {variant.pronunciation_tip ? (
                        <p className="detail-tip detail-tip-pron">
                          {variant.pronunciation_tip}
                        </p>
                      ) : null}
                      {variant.cultural_tip ? (
                        <p className="detail-tip detail-tip-culture">
                          {variant.cultural_tip}
                        </p>
                      ) : null}
                    </div>
                  ) : null}
                </li>
              );
            })}
          </ul>
        </aside>
      </div>

      {confirmDelete ? (
        <div className="modal-backdrop" role="presentation">
          <div
            className="confirm-dialog"
            role="alertdialog"
            aria-modal="true"
            aria-label="삭제 확인"
          >
            <p className="confirm-title">정말 삭제할까요?</p>
            <p className="confirm-copy">
              삭제하면 라이브러리와 복습 큐에서 모두 사라져요
            </p>
            <div className="confirm-actions">
              <button
                className="retry-button"
                type="button"
                onClick={() => setConfirmDelete(false)}
              >
                취소
              </button>
              <button
                className="confirm-destructive"
                type="button"
                onClick={() => router.push("/library")}
              >
                삭제
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
