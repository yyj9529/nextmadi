"use client";

import Link from "next/link";

import { BackIcon } from "@/components/app/icons";

// S11 설정 데이터(GET /me, GET /usage/today, GET /coaches) 조회 실패.
// 프로필 없이 그릴 수 있는 섹션이 없으므로 재시도만 안내한다(s11.md UI states).
export function SettingsUnavailable() {
  return (
    <div className="app-screen settings-screen">
      <header className="app-topbar result-topbar">
        <Link className="back-link" href="/home" aria-label="뒤로 가기">
          <BackIcon />
        </Link>
        <h1 className="result-title">설정</h1>
      </header>

      <section className="settings-section" aria-label="설정 불러오기 실패">
        <div className="settings-card">
          <p className="confirm-title">설정을 불러오지 못했어요</p>
          <p className="confirm-copy">잠시 후 다시 시도해주세요.</p>
          <button
            className="retry-button"
            type="button"
            onClick={() => window.location.reload()}
          >
            다시 시도
          </button>
        </div>
      </section>
    </div>
  );
}
