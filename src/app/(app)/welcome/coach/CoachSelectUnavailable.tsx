"use client";

// 코치 목록을 불러오지 못했을 때(조회 실패 또는 미시드). 온보딩을 진행할 수 없으므로
// 재시도만 안내한다.
export function CoachSelectUnavailable() {
  return (
    <div className="app-screen coach-select-screen">
      <main className="coach-select-main coach-select-unavailable">
        <h1 className="coach-select-headline">코치를 불러오지 못했어요</h1>
        <p className="coach-select-sub">
          잠시 후 다시 시도해주세요.
        </p>
        <div className="coach-select-footer">
          <button
            className="primary-button"
            type="button"
            onClick={() => window.location.reload()}
          >
            다시 시도
          </button>
        </div>
      </main>
    </div>
  );
}
