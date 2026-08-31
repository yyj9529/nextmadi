// S11 Loading 상태: GET /me + GET /usage/today + GET /coaches를 서버에서 조회하는 동안
// 섹션 스켈레톤을 보여준다(s11.md UI states).
export default function SettingsLoading() {
  return (
    <div className="app-screen settings-screen" aria-busy="true">
      <header className="app-topbar result-topbar">
        <h1 className="result-title">설정</h1>
      </header>

      <div className="settings-desktop-grid">
        {[0, 1].map((column) => (
          <div className="settings-column" key={column}>
            {[0, 1].map((section) => (
              <section className="settings-section" key={section}>
                <span className="skeleton skeleton-line settings-section-title" />
                <div className="settings-card">
                  <span className="skeleton skeleton-line" />
                  <span className="skeleton skeleton-line" />
                </div>
              </section>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}
