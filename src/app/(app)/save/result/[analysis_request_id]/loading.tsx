// S07 로딩 상태(UI states: Loading). 분석을 서버에서 가져오는 동안 카드 3개 자리에
// shimmer 스켈레톤을 보여준다. (#40)
export default function AnalysisResultLoading() {
  return (
    <div className="app-screen result-screen">
      <div className="result-desktop-grid">
        <main className="result-main-column">
          <div className="app-topbar result-topbar">
            <span className="result-title">분석 결과</span>
          </div>
          <p className="input-summary skeleton skeleton-line" aria-hidden="true">
            &nbsp;
          </p>
        </main>

        <aside className="result-side-column">
          <ul className="expression-list" aria-label="분석 결과 불러오는 중">
            {[0, 1, 2].map((i) => (
              <li className="expression-card skeleton-card" key={i}>
                <div className="expression-main">
                  <span className="skeleton skeleton-badge" />
                  <span className="skeleton skeleton-line wide" />
                  <span className="skeleton skeleton-line" />
                </div>
                <span className="skeleton skeleton-circle" />
              </li>
            ))}
          </ul>
        </aside>
      </div>
    </div>
  );
}
