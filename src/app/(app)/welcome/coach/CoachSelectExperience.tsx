"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

import { CoachCardList } from "@/components/app/CoachCards";
import { mockMe } from "@/lib/mock-api";
import {
  readPendingSave,
  resolvePostOnboardingDestination,
} from "@/lib/pending-save";
import type { Coach } from "@/lib/mock-api";

// S03b 코치 선택 (신규 1회).
export function CoachSelectExperience() {
  const router = useRouter();
  const [selected, setSelected] = useState<Coach | null>(null);

  function handleContinue() {
    const destination = resolvePostOnboardingDestination(readPendingSave());
    router.push(destination.path);
  }

  return (
    <div className="app-screen coach-select-screen">
      <main className="coach-select-main">
        <header className="coach-select-header">
          <h1 className="coach-select-headline">
            환영해요, {mockMe.display_name}님!
          </h1>
          <p className="coach-select-sub">함께할 코치를 골라보세요</p>
        </header>

        <CoachCardList
          selectedId={selected?.id ?? null}
          onSelect={setSelected}
        />

        <div className="coach-select-footer">
          <button
            className="primary-button"
            type="button"
            disabled={selected === null}
            onClick={handleContinue}
          >
            {selected
              ? `${selected.display_name}와 함께 시작하기`
              : "코치를 골라주세요"}
          </button>
          <p className="coach-select-note">나중에 설정에서 변경할 수 있어요</p>
        </div>
      </main>
    </div>
  );
}
