// S03b Loading 상태: 코치 목록(GET /coaches)을 서버에서 조회하는 동안
// 카드 3개 스켈레톤을 보여준다(s03b.md UI states).
export default function CoachSelectLoading() {
  return (
    <div className="app-screen coach-select-screen">
      <main className="coach-select-main" aria-busy="true">
        <header className="coach-select-header">
          <span className="skeleton skeleton-line wide coach-skeleton-headline" />
          <span className="skeleton skeleton-line coach-skeleton-sub" />
        </header>

        <ul className="coach-card-list">
          {[0, 1, 2].map((index) => (
            <li key={index}>
              <div className="coach-card coach-card-skeleton">
                <span className="skeleton skeleton-circle coach-card-avatar" />
                <span className="coach-card-body">
                  <span className="skeleton skeleton-line coach-skeleton-name" />
                  <span className="skeleton skeleton-line" />
                </span>
              </div>
            </li>
          ))}
        </ul>
      </main>
    </div>
  );
}
