// jose↔Nimbus 상호운용 픽스처 생성기. (#20)
//
// 고정된 비밀·시각으로 토큰을 발급해 출력한다. 출력 토큰을 백엔드의
// InternalAuthInteropTests에 픽스처로 박아, jose(TS)가 만든 토큰을 Nimbus(Java)가 검증할 수
// 있음을 자동 테스트로 보장한다. 재생성: `bun run scripts/mint-interop-fixture.ts`
//
// 비밀은 테스트 전용이며 실제 비밀이 아니다(SECURITY.md상 커밋 가능한 더미).

import { mintInternalAuthToken } from "../src/lib/internal-auth";

const SECRET = "interop-shared-secret-jose-and-nimbus-0123456789";
const FIXED_NOW = new Date("2026-06-18T12:00:00Z");

const token = await mintInternalAuthToken(
  { userId: "interop-user" },
  SECRET,
  { now: FIXED_NOW, ttlSeconds: 120 },
);

console.log("secret    :", SECRET);
console.log("fixedNow  :", FIXED_NOW.toISOString());
console.log("token     :", token);
