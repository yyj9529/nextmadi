"use client";

import { CheckIcon } from "@/components/app/icons";
import { mockCoachSampleLines } from "@/lib/mock-api";
import type { Coach } from "@/lib/mock-api";

// 코치 비교 카드 — S03b 코치 선택과 S11 코치 변경 모달이 공유.
// 데이터 출처: GET /coaches. 두 화면 모두 실제 목록을 coaches로 넘긴다(#56).
// 목 폴백 기본값은 두지 않는다 — 조회가 실패해도 화면이 그럴듯하게 그려져
// 실패가 성공처럼 보이는 경로가 생긴다(docs/solutions/mock-to-real-drift.md).

type CoachCardListProps = {
  selectedId: string | null;
  onSelect: (coach: Coach) => void;
  /** 렌더할 코치 목록(GET /coaches). */
  coaches: Coach[];
  /** 저장 중 등 카드 상호작용을 잠글 때. */
  disabled?: boolean;
  /** 저장 중인 카드에 스피너를 표시한다(S03b Selecting 상태). */
  savingId?: string | null;
};

export function CoachCardList({
  selectedId,
  onSelect,
  coaches,
  disabled = false,
  savingId = null,
}: CoachCardListProps) {
  return (
    <ul className={`coach-card-list${disabled ? " is-busy" : ""}`}>
      {coaches.map((coach) => {
        const isSelected = coach.id === selectedId;
        const isSaving = coach.id === savingId;
        return (
          <li key={coach.id}>
            <button
              className={`coach-card coach-card-${coach.slug}${
                isSelected ? " is-selected" : ""
              }${isSaving ? " is-saving" : ""}`}
              type="button"
              aria-pressed={isSelected}
              disabled={disabled}
              onClick={() => onSelect(coach)}
            >
              <span className="coach-card-avatar" aria-hidden="true">
                {coach.display_name[0]}
              </span>
              <span className="coach-card-body">
                <span className="coach-card-name">{coach.display_name}</span>
                <span className="coach-card-summary">
                  {coach.persona_summary}
                </span>
                <span className="coach-card-quote">
                  {mockCoachSampleLines[coach.slug]}
                </span>
              </span>
              {isSaving ? (
                <span
                  className="coach-card-spinner"
                  role="status"
                  aria-label="저장 중"
                />
              ) : isSelected ? (
                <span className="coach-card-check" aria-hidden="true">
                  <CheckIcon />
                </span>
              ) : null}
            </button>
          </li>
        );
      })}
    </ul>
  );
}
