// 테스트 전용 헬퍼. 프로덕션 코드에서 import 하지 않는다.
//
// bun test는 저장소의 `.env`를 자동으로 로드한다. 그래서 "환경변수가 없을 때"를 검증하는
// 테스트가 앰비언트 env를 그대로 읽으면, CI(.env 없음)에서는 통과하고 로컬(.env 있음)에서는
// 실패한다 — 혹은 더 나쁘게, 실제 로컬 백엔드에 붙어 진짜 응답을 받아 초록으로 보인다.
// 미설정 경로를 검증하려면 해당 변수를 명시적으로 지워야 한다.

export async function withoutEnv<T>(
  names: string | string[],
  run: () => Promise<T>,
): Promise<T> {
  const keys = typeof names === "string" ? [names] : names;
  const previous = new Map<string, string | undefined>();

  for (const key of keys) {
    previous.set(key, process.env[key]);
    delete process.env[key];
  }

  try {
    return await run();
  } finally {
    for (const [key, value] of previous) {
      if (value !== undefined) {
        process.env[key] = value;
      } else {
        delete process.env[key];
      }
    }
  }
}
