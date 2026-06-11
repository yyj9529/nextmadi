"use client";

import { CheckIcon } from "@/components/app/icons";
import { mockCoaches, mockCoachSampleLines } from "@/lib/mock-api";
import type { Coach } from "@/lib/mock-api";

// 코치 비교 카드 — S03b 코치 선택과 S11 코치 변경 모달이 공유.
// 데이터 출처: GET /coaches (목: mockCoaches).

type CoachCardListProps = {
  selectedId: string | null;
  onSelect: (coach: Coach) => void;
};

export function CoachCardList({ selectedId, onSelect }: CoachCardListProps) {
  return (
    <ul className="coach-card-list">
      {mockCoaches.map((coach) => {
        const isSelected = coach.id === selectedId;
        return (
          <li key={coach.id}>
            <button
              className={`coach-card coach-card-${coach.slug}${
                isSelected ? " is-selected" : ""
              }`}
              type="button"
              aria-pressed={isSelected}
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
              {isSelected ? (
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
