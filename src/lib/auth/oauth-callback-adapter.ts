import "server-only";

import type { Adapter, AdapterUser } from "next-auth/adapters";

/**
 * OAuth 콜백 요청 전용 어댑터. 저장은 한 줄도 하지 않는다.
 *
 * 왜 "아무것도 안 하는 어댑터"가 필요한가 (#157):
 *
 * `@auth/core/lib/utils/assert.js`의 `hasEmail`은 **모듈 전역**이고 아무도 리셋하지 않는다
 * (assert.js:15-17, 90-94). 이메일 provider가 든 설정이 한 번 검사되는 순간 그 플래그는
 * 프로세스가 죽을 때까지 true로 남는다. 그래서 #19가 만든 "콜백 요청에서만 어댑터를 뗀다"는
 * 분기가 이 순서에서 무너졌다:
 *
 *   1. POST /api/auth/signin/google — 전체 설정이 검사되며 `hasEmail = true`로 걸린다.
 *   2. GET  /api/auth/callback/google — 어댑터 없는 설정. provider 목록에 이메일이 없어도
 *      플래그는 아직 true라서 `hasEmail && !adapter` → MissingAdapter, 500 (assert.js:130-135).
 *
 * 콜백이 살아남는 건 그것이 프로세스의 첫 auth 요청일 때뿐이었다. dev에서 HMR이 모듈을 다시
 * 평가하며 플래그를 지워준 탓에 간헐적으로만 보였고, 오래 사는 프로덕션 프로세스에서는 항상 죽는다.
 *
 * 그래서 콜백 설정에도 어댑터를 **붙이되**, BFF 어댑터(`createBffAdapter`)는 붙이지 않는다.
 * BFF 어댑터를 붙이면 #19가 없앤 문제가 그대로 돌아온다 — OAuth 분기가 `getUserByAccount`,
 * `createUser`, `linkAccount`를 어댑터에 묻는데, 그 어댑터의 `createUser`는 이메일 신원을
 * 만든다. 신원 생성 출처가 둘이 되고, 그건 `signIn` 콜백의 프로비저닝(#18)과 충돌한다.
 *
 * 이 어댑터는 `assertConfig`를 만족시키는 것 외에 하는 일이 없다. OAuth 콜백에서 실제로
 * 호출되는 메서드만, "저장소가 없을 때와 똑같은 답"으로 구현한다:
 *
 *   - `getUserByAccount` → null  (callback/index.js:56, handle-login.js:175)
 *   - `getUser`          → null  (기존 세션 쿠키가 있을 때만, handle-login.js:40)
 *   - `getUserByEmail`   → null  (handle-login.js:231)
 *   - `createUser`       → 받은 user를 그대로 반환 (handle-login.js:260)
 *   - `linkAccount`      → no-op (handle-login.js:264)
 *
 * `createUser`가 "받은 것을 그대로 돌려주는" 것이 핵심이다. 그 객체는 `signIn` 콜백이 이미
 * PhraseLog 신원(`phraselogUserId`, `isOnboarded`)을 붙여둔 바로 그 user다. 여기서 새 객체를
 * 지어내면 jwt 콜백이 신원을 잃는다. 즉 이 어댑터는 신원을 만들지 않는다 — 프로비저닝은
 * 여전히 `signIn` 콜백 한 곳에서만 일어난다.
 *
 * 나머지는 조용히 null을 돌려주지 않고 던진다. 그럴듯한 값을 돌려주면 "없는 기능"이
 * "성공한 로그인"처럼 보인다 (docs/solutions/silent-failure-looks-like-success.md).
 * 특히 이메일 계열(`createVerificationToken`/`useVerificationToken`)이 여기서 불린다면
 * 그것은 이메일 요청이 콜백 설정으로 잘못 라우팅됐다는 뜻이므로, 매직링크를 조용히
 * 발급하는 것보다 500이 낫다.
 */

const UNSUPPORTED = [
  "createVerificationToken",
  "useVerificationToken",
  "updateUser",
  "unlinkAccount",
  "createSession",
  "getSessionAndUser",
  "updateSession",
  "deleteSession",
  "deleteUser",
] as const;

export function createOAuthCallbackAdapter(): Adapter {
  const unsupported = Object.fromEntries(
    UNSUPPORTED.map((name) => [
      name,
      () => {
        throw new Error(
          `OAuth callback adapter does not implement ${name}: this config only serves /api/auth/callback/{google,kakao}, where Auth.js never reaches it.`,
        );
      },
    ]),
  );

  return {
    ...unsupported,

    async getUserByAccount() {
      // null이어야 signIn 콜백이 provider가 준 user 객체를 받는다. 여기서 무언가를 돌려주면
      // callback/index.js:56-62가 그 객체를 대신 넘겨서, 프로비저닝 결과가 붙을 대상이 바뀐다.
      return null;
    },

    async getUser() {
      return null;
    },

    async getUserByEmail() {
      // null이 아니면 handle-login.js:234가 OAuthAccountNotLinked를 던진다. same-email 판정은
      // 백엔드(`/api/v1/auth/oauth/identity`)가 account_link_required로 이미 내리고 있다.
      return null;
    },

    async createUser(user) {
      return user as AdapterUser;
    },

    async linkAccount() {
      return undefined;
    },
  } as Adapter;
}
