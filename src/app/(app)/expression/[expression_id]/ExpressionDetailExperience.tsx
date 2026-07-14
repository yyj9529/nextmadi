"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { BackIcon, ChevronDownIcon, MoreIcon } from "@/components/app/icons";
import { PlayButton } from "@/components/app/PlayButton";
import { useExpressionDetail } from "./useExpressionDetail";

// S09 표현 상세. (#47 실데이터 연결)
// 데이터: GET /api/expressions/{expression_id} (useExpressionDetail 훅).
// 삭제: DELETE /api/expressions/{id} → 확인 후 /library 복귀.
// 복습 큐에서 제거: POST /api/review/{review_card_id}/remove-from-queue.
//   re-add 방향은 상세 GET이 제거된 카드의 id를 안 내려 v1 보류(s09.md).
// 연습하기: POST /api/practice/sessions { expression_id } → /practice/{session_id} (#61).
//   일일 한도 초과(429)는 안내만 하고 라우팅하지 않는다(s12.md US1 AC3).

const NOT_FOUND_REDIRECT_MS = 2000;

const toneClassByOrder: Record<number, string> = {
  1: "tone-polite",
  2: "tone-direct",
  3: "tone-firm",
};

type ExpressionDetailExperienceProps = {
  expressionId: string;
};

export function ExpressionDetailExperience({
  expressionId,
}: ExpressionDetailExperienceProps) {
  const router = useRouter();
  const { status, expression, retry, setExpression } =
    useExpressionDetail(expressionId);

  if (status === "loading") {
    return <DetailSkeleton />;
  }

  if (status === "notFound") {
    return <DetailNotFound />;
  }

  if (status === "error" || !expression) {
    return <DetailError onRetry={retry} />;
  }

  return (
    <LoadedDetail
      expression={expression}
      onLocalUpdate={setExpression}
      onDeleted={() => router.push("/library")}
    />
  );
}

type LoadedDetailProps = {
  expression: NonNullable<ReturnType<typeof useExpressionDetail>["expression"]>;
  onLocalUpdate: (next: LoadedDetailProps["expression"]) => void;
  onDeleted: () => void;
};

function LoadedDetail({
  expression,
  onLocalUpdate,
  onDeleted,
}: LoadedDetailProps) {
  const router = useRouter();
  const [expandedId, setExpandedId] = useState(expression.selected_variant_id);
  const [menuOpen, setMenuOpen] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [removing, setRemoving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [starting, setStarting] = useState(false);
  // 재시도 시 같은 Idempotency-Key를 유지해 세션(=일일 슬롯) 중복 생성을 막는다.
  const startKeyRef = useRef<string | null>(null);

  const inQueue = expression.review_card_id !== null;

  // "이 표현으로 연습하기" → 실세션 생성 후 S12로 이동. 한도 초과/실패는 안내만.
  const handlePractice = async () => {
    if (starting) {
      return;
    }
    setStarting(true);
    setNotice(null);
    const idempotencyKey = startKeyRef.current ?? crypto.randomUUID();
    startKeyRef.current = idempotencyKey;
    try {
      const res = await fetch("/api/practice/sessions", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": idempotencyKey,
        },
        body: JSON.stringify({ expression_id: expression.id }),
      });
      // 서버 응답 도달 = 확정. 다음 시도는 새 키로 시작한다.
      startKeyRef.current = null;
      if (res.status === 201) {
        const created = (await res.json()) as { id: string };
        // 성공 — 라우팅 중 재클릭 방지를 위해 starting을 유지한다.
        router.push(`/practice/${created.id}`);
        return;
      }
      if (res.status === 429) {
        setNotice("오늘은 2번 다 썼어요. 내일 다시 만나요.");
      } else {
        setNotice("연습을 시작하지 못했어요. 다시 시도해주세요.");
      }
      setStarting(false);
    } catch {
      // 결과 불명(네트워크 오류) — startKeyRef를 남겨 재시도가 중복 세션을 막게 한다.
      setNotice("연습을 시작하지 못했어요. 다시 시도해주세요.");
      setStarting(false);
    }
  };

  const handleRemoveFromQueue = async () => {
    const reviewCardId = expression.review_card_id;
    if (!reviewCardId || removing) {
      return;
    }
    setRemoving(true);
    setMenuOpen(false);
    try {
      const res = await fetch(
        `/api/review/${encodeURIComponent(reviewCardId)}/remove-from-queue`,
        { method: "POST" },
      );
      // 404(다른 탭에서 이미 제거)도 결과적으로 제거된 상태이므로 성공처럼 정리한다.
      if (res.ok || res.status === 404) {
        onLocalUpdate({
          ...expression,
          review_card_id: null,
          next_review_at: null,
        });
        setNotice("복습 큐에서 제거했어요");
      } else {
        setNotice("복습 큐에서 제거하지 못했어요. 다시 시도해주세요.");
      }
    } catch {
      setNotice("복습 큐에서 제거하지 못했어요. 다시 시도해주세요.");
    } finally {
      setRemoving(false);
    }
  };

  const handleDelete = async () => {
    if (deleting) {
      return;
    }
    setDeleting(true);
    try {
      const res = await fetch(
        `/api/expressions/${encodeURIComponent(expression.id)}`,
        { method: "DELETE" },
      );
      // 204 성공. 404(이미 삭제됨)도 결과적으로 사라진 상태이므로 /library로 복귀한다.
      if (res.ok || res.status === 404) {
        onDeleted();
        return;
      }
      setDeleting(false);
      setConfirmDelete(false);
      setNotice("삭제에 실패했어요. 다시 시도해주세요.");
    } catch {
      setDeleting(false);
      setConfirmDelete(false);
      setNotice("삭제에 실패했어요. 다시 시도해주세요.");
    }
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

          <div className="detail-actions">
            <button
              className="practice-button"
              type="button"
              onClick={handlePractice}
              disabled={starting}
              aria-busy={starting}
            >
              {starting ? "연습 준비 중…" : "🎤 이 표현으로 연습하기"}
            </button>
            {inQueue ? (
              <button
                className="detail-queue-toggle"
                type="button"
                onClick={handleRemoveFromQueue}
                disabled={removing}
                aria-busy={removing}
              >
                {removing ? "제거 중…" : "복습 큐에서 제거"}
              </button>
            ) : null}
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
                        className={`tone-badge ${toneClassByOrder[variant.variant_order] ?? ""}`}
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
                disabled={deleting}
              >
                취소
              </button>
              <button
                className="confirm-destructive"
                type="button"
                onClick={handleDelete}
                disabled={deleting}
                aria-busy={deleting}
              >
                {deleting ? "삭제 중…" : "삭제"}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function DetailSkeleton() {
  return (
    <div className="app-screen detail-screen" aria-busy="true">
      <div className="detail-desktop-grid">
        <main className="detail-main-column">
          <header className="app-topbar result-topbar detail-topbar">
            <span className="skeleton skeleton-circle" aria-hidden="true" />
            <span className="skeleton skeleton-circle" aria-hidden="true" />
          </header>
          <span
            className="skeleton skeleton-line wide"
            style={{ width: "70%" }}
            aria-hidden="true"
          />
        </main>
        <aside className="detail-side-column">
          <ul className="expression-list" aria-label="표현 변형 불러오는 중">
            {[0, 1, 2].map((i) => (
              <li className="expression-card detail-variant skeleton-card" key={i}>
                <span
                  className="skeleton skeleton-line"
                  style={{ width: "80%" }}
                />
                <span
                  className="skeleton skeleton-line"
                  style={{ width: "50%" }}
                />
              </li>
            ))}
          </ul>
        </aside>
      </div>
    </div>
  );
}

function DetailNotFound() {
  const router = useRouter();
  useEffect(() => {
    const timer = setTimeout(() => {
      router.replace("/library");
    }, NOT_FOUND_REDIRECT_MS);
    return () => clearTimeout(timer);
  }, [router]);

  return (
    <div className="app-screen detail-screen">
      <div className="library-empty">
        <p className="library-empty-title">표현을 찾지 못했어요</p>
        <p className="library-empty-sub">잠시 후 내 기록으로 이동할게요.</p>
        <Link className="primary-button library-retry" href="/library">
          내 기록으로 가기
        </Link>
      </div>
    </div>
  );
}

function DetailError({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="app-screen detail-screen">
      <div className="library-empty">
        <p className="library-empty-title">표현을 불러오지 못했어요</p>
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
