"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

import { CoachCardList } from "@/components/app/CoachCards";
import {
  readPendingSave,
  resolvePostOnboardingDestination,
} from "@/lib/pending-save";
import type { Coach } from "@/lib/mock-api";

// S03b 코치 선택 (신규 1회).
// 선택 완료: PATCH /me { selected_coach_id, is_onboarded: true } → 성공 시 /home
// (또는 대기 중 저장 흐름). 저장 중에는 카드를 잠그고 선택 카드에 스피너를 표시한다.

type CoachSelectExperienceProps = {
  coaches: Coach[];
  displayName: string;
};

type Status = "idle" | "saving" | "error";

export function CoachSelectExperience({
  coaches,
  displayName,
}: CoachSelectExperienceProps) {
  const router = useRouter();
  const [selected, setSelected] = useState<Coach | null>(null);
  const [status, setStatus] = useState<Status>("idle");

  const greetingName = displayName.trim();

  async function handleContinue() {
    if (selected === null || status === "saving") {
      return;
    }
    setStatus("saving");

    let response: Response;
    try {
      response = await fetch("/api/me", {
        method: "PATCH",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          selected_coach_id: selected.id,
          is_onboarded: true,
        }),
      });
    } catch {
      setStatus("error");
      return;
    }

    if (!response.ok) {
      setStatus("error");
      return;
    }

    const destination = resolvePostOnboardingDestination(readPendingSave());
    router.push(destination.path);
  }

  const saving = status === "saving";

  return (
    <div className="app-screen coach-select-screen">
      <main className="coach-select-main">
        <header className="coach-select-header">
          <h1 className="coach-select-headline">
            {greetingName ? `환영해요, ${greetingName}님!` : "환영해요!"}
          </h1>
          <p className="coach-select-sub">함께할 코치를 골라보세요</p>
        </header>

        <CoachCardList
          coaches={coaches}
          selectedId={selected?.id ?? null}
          onSelect={setSelected}
          disabled={saving}
          savingId={saving ? (selected?.id ?? null) : null}
        />

        <div className="coach-select-footer">
          <button
            className="primary-button"
            type="button"
            disabled={selected === null || saving}
            onClick={() => void handleContinue()}
          >
            {saving
              ? "저장 중…"
              : selected
                ? `${selected.display_name}와 함께 시작하기`
                : "코치를 골라주세요"}
          </button>
          <p className="coach-select-note">나중에 설정에서 변경할 수 있어요</p>
        </div>
      </main>

      {status === "error" ? (
        <p className="review-toast coach-select-toast" role="alert">
          선택을 저장하지 못했어요. 다시 시도해주세요.
        </p>
      ) : null}
    </div>
  );
}
