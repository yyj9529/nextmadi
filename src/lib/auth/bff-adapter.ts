import "server-only";

import type { Adapter, AdapterUser } from "next-auth/adapters";

import {
  consumeVerificationToken,
  createVerificationToken,
  type EmailIdentity,
  type EmailProvisioningOptions,
  linkEmailIdentityByUserId,
  lookupEmailIdentity,
  resolveEmailIdentity,
} from "./email-provisioning";

// Auth.js는 이메일 provider에 Adapter를 요구하지만, ADR-010에서 DB는 Spring Boot만 소유한다.
// 그래서 공식 DB 어댑터(@auth/pg-adapter) 대신 규격만 구현하고 저장은 X-Internal-Auth로 넘긴다.
//
// 실제로 호출되는 메서드는 설치된 next-auth 5.0.0-beta.31 소스에서 확인했다:
//   - @auth/core/lib/utils/assert.js  : 설정 시점 필수 3개 (createVerificationToken,
//     useVerificationToken, getUserByEmail). 나머지 session 계열은 strategy:"database"에만 요구된다.
//   - @auth/core/lib/actions/callback/handle-login.js : email 분기에서 updateUser / createUser
//   - linkAccount는 oauth/webauthn 분기 전용이라 이 경로에서 호출되지 않는다.
//
// 구현하지 않은 메서드는 조용히 null을 돌려주지 않고 던진다. 그럴듯한 값을 돌려주면 "없는 기능"이
// "성공한 로그인"처럼 보인다 (docs/solutions/silent-failure-looks-like-success.md).

const UNSUPPORTED = [
  "getUserByAccount",
  "linkAccount",
  "unlinkAccount",
  "createSession",
  "getSessionAndUser",
  "updateSession",
  "deleteSession",
  "deleteUser",
] as const;

export function createBffAdapter(
  options: EmailProvisioningOptions = {},
): Adapter {
  const unsupported = Object.fromEntries(
    UNSUPPORTED.map((name) => [
      name,
      () => {
        throw new Error(
          `BFF adapter does not implement ${name}: it is not reached by the email provider under session.strategy "jwt".`,
        );
      },
    ]),
  );

  return {
    ...unsupported,

    async createVerificationToken(token) {
      const created = await createVerificationToken(
        {
          identifier: token.identifier,
          token: token.token,
          expires: token.expires.toISOString(),
        },
        options,
      );
      return toVerificationToken(created);
    },

    async useVerificationToken(input) {
      const consumed = await consumeVerificationToken(input, options);
      if (!consumed) {
        return null;
      }
      const token = toVerificationToken(consumed);
      return isStillValid(token.expires) ? token : null;
    },

    async getUserByEmail(email) {
      const identity = await lookupEmailIdentity(email, options);
      return identity ? toAdapterUser(identity) : null;
    },

    async createUser(user) {
      return toAdapterUser(await resolveEmailIdentity(user.email, options));
    },

    async updateUser(user) {
      // Auth.js는 {id, emailVerified}만 넘긴다. emailVerified는 users에 대응 컬럼이 없어 버린다 —
      // 마이그레이션 없이 저장할 곳이 없고, 링크를 소비했다는 사실 자체가 검증의 증거다.
      return toAdapterUser(await linkEmailIdentityByUserId(user.id, options));
    },

    async getUser() {
      // Spring Boot는 주소로만 신원을 조회한다. Auth.js가 이 메서드를 쓰는 곳은 "이미 로그인한
      // 상태에서 매직링크를 누른" 경우뿐이고, 거기서 null은 "이전 세션 없음"으로 처리된다 —
      // 새 로그인으로 이어지므로 결과가 옳다. 조회 실패를 감춘 게 아니라 조회 수단이 없는 것이다.
      return null;
    },
  } as Adapter;
}

/**
 * 링크 수명을 우리 코드에서도 판정한다.
 *
 * 이 판정은 원래 한 곳에만 있었다 — `@auth/core/lib/actions/callback/index.js:147`의
 * `invite.expires.valueOf() < Date.now()`. 그 한 줄이 두 가지 이유로 유일한 방어선이기에는
 * 약하다.
 *
 * 첫째, `package.json`이 캐럿 범위의 프리릴리스를 가리키므로 보안상 의미 있는 수명의 유일한
 * 집행자가 우리가 고르지 않은 버전에 있다. 둘째, 그 비교는 `expires`가 유효한 Date일 때만
 * 동작한다. 백엔드의 wire format이 ISO 문자열에서 벗어나면 `new Date(...)`는 `Invalid Date`가
 * 되고 `NaN < Date.now()`는 **false** — 만료 판정이 조용히 뒤집혀 만료된 링크가 영구히 유효해진다.
 * 그래서 `> Date.now()`가 아니라 유한성부터 확인한다. 이 방향의 실패는 열리는 쪽이 아니라 닫히는
 * 쪽이어야 한다.
 *
 * 백엔드 `consume`은 만료 여부와 무관하게 행을 먼저 삭제하므로 여기서 거절해도 단일 사용은
 * 그대로다. Auth.js 관점에서 null과 만료된 토큰은 둘 다 `Verification`으로 끝나 사용자가 보는
 * 화면도 같다 (`callback/index.js:153`).
 *
 * 양쪽이 같은 규칙을 적용하는 구조는 이 경로에 이미 있다 — OAuth의 email 검증도 BFF와 Spring
 * Boot 양쪽에서 판정한다 (`docs/auth.md` "OAuth email verification").
 */
function isStillValid(expires: Date) {
  const at = expires.valueOf();
  return Number.isFinite(at) && at > Date.now();
}

function toAdapterUser(identity: EmailIdentity): AdapterUser {
  return {
    id: identity.userId,
    email: identity.email,
    name: identity.displayName,
    // 링크를 소비해야만 여기 도달하므로 메일함 소유는 이 시점에 증명돼 있다.
    emailVerified: new Date(),
    // S03 AC3의 라우팅(온보딩 미완료 → /welcome/coach)이 세션에서 이 값을 읽는다.
    // AdapterUser에 없는 필드지만 Auth.js는 user 객체를 그대로 jwt 콜백에 넘긴다.
    isOnboarded: identity.isOnboarded,
  } as AdapterUser;
}

function toVerificationToken(record: {
  identifier: string;
  token: string;
  expires: string;
}) {
  return {
    identifier: record.identifier,
    token: record.token,
    expires: new Date(record.expires),
  };
}
