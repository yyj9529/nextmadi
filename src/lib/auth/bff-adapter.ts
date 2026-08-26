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
// "성공한 로그인"처럼 보인다 (docs/solutions/green-build-proves-nothing.md).

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
      return consumed ? toVerificationToken(consumed) : null;
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

function toAdapterUser(identity: EmailIdentity): AdapterUser {
  return {
    id: identity.userId,
    email: identity.email,
    name: identity.displayName,
    // 링크를 소비해야만 여기 도달하므로 메일함 소유는 이 시점에 증명돼 있다.
    emailVerified: new Date(),
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
