import { beforeEach, describe, expect, mock, test } from "bun:test";

mock.module("server-only", () => ({}));

type SendMailResult = { rejected?: unknown[]; pending?: unknown[] };

// 실제 발송 경로는 호출 시점에 `await import("nodemailer")` 한다. 모듈을 여기서 갈아끼워
// 전송 결과를 테스트가 정한다 — SES에 붙지 않고도 실패 처리가 실제로 도는지 볼 수 있다.
type SentMail = {
  to: string;
  from: string;
  subject: string;
  text: string;
  html: string;
};

let sendMail = mock(async (): Promise<SendMailResult> => ({}));
let lastTransportConfig: unknown = null;
let lastMail: SentMail | null = null;
let sendCount = 0;

mock.module("nodemailer", () => ({
  createTransport: (config: unknown) => {
    lastTransportConfig = config;
    return {
      sendMail: async (options: SentMail) => {
        lastMail = options;
        sendCount += 1;
        return sendMail();
      },
    };
  },
}));

const { EMAIL_LINK_MAX_AGE_SECONDS, buildEmailProvider } = await import(
  "./email-provider"
);

// Nodemailer() returns Auth.js's defaults with our configuration parked under `options`;
// parseProviders merges `options` over the defaults per request
// (@auth/core/lib/utils/providers.js). `options` is therefore what we actually control, and
// asserting on the un-merged top level would test Auth.js's defaults instead of our config.
type ConfiguredProvider = {
  id: string;
  options: {
    from?: string;
    maxAge?: number;
    server?: unknown;
    sendVerificationRequest?: (params: {
      identifier: string;
      url: string;
      provider?: unknown;
    }) => Promise<void>;
  };
};

const configured = (provider: unknown) => provider as ConfiguredProvider;

/** 기본값은 실제 백엔드를 호출하므로, 테스트는 항상 상한 확인을 주입한다. */
let quotaCalls: string[] = [];
let quotaCheck = async (identifier: string) => {
  quotaCalls.push(identifier);
};
const withQuota = <T extends object>(options: T) => ({
  ...options,
  checkSendQuota: (identifier: string) => quotaCheck(identifier),
});

const SMTP_ENV = {
  AUTH_EMAIL_SERVER_HOST: "email-smtp.us-east-1.amazonaws.com",
  AUTH_EMAIL_SERVER_PORT: "587",
  AUTH_EMAIL_SERVER_USER: "ses-user",
  AUTH_EMAIL_SERVER_PASSWORD: "ses-password",
  EMAIL_FROM: "PhraseLog <noreply@phraselog.test>",
};

describe("with SMTP configured", () => {
  test("builds a nodemailer provider pinned to a 24h link", () => {
    const provider = buildEmailProvider(
      withQuota({ env: { ...SMTP_ENV, NODE_ENV: "production" } }),
    );

    expect(configured(provider).id).toBe("nodemailer");
    expect(configured(provider).options.maxAge).toBe(EMAIL_LINK_MAX_AGE_SECONDS);
    expect(EMAIL_LINK_MAX_AGE_SECONDS).toBe(86400);
  });

  test("uses the configured sender and SMTP endpoint", () => {
    const provider = buildEmailProvider(withQuota({ env: SMTP_ENV }));

    expect(configured(provider).options.from).toBe(
      "PhraseLog <noreply@phraselog.test>",
    );
    expect(configured(provider).options.server).toMatchObject({
      host: "email-smtp.us-east-1.amazonaws.com",
      port: 587,
      auth: { user: "ses-user", pass: "ses-password" },
    });
  });

  test("bounds every SMTP wait well under a user's patience", () => {
    // Nodemailer 기본값은 연결·소켓 각 2분이다. 로그인 요청이 그만큼 매달려 있으면
    // 사용자는 실패했는지 진행 중인지 알 수 없다.
    const server = configured(buildEmailProvider(withQuota({ env: SMTP_ENV }))).options
      .server as Record<string, number>;

    for (const key of [
      "connectionTimeout",
      "greetingTimeout",
      "socketTimeout",
    ]) {
      expect(server[key]).toBeGreaterThan(0);
      expect(server[key]).toBeLessThanOrEqual(15_000);
    }
  });

  test("treats a non-numeric port as no configuration at all", () => {
    // A typo'd port must not silently become NaN and fail at send time.
    const provider = buildEmailProvider(
      withQuota({
        env: { ...SMTP_ENV, AUTH_EMAIL_SERVER_PORT: "not-a-port" },
        logMagicLink: () => {},
      }),
    );

    expect(configured(provider).options.from).toBe("PhraseLog <dev@localhost>");
  });
});

// 이 티켓이 스스로 지목한 최대 위험 지점이다 — 발송이 실패했는데 성공 화면이 뜨는 것.
// 그 판정은 전부 sendKoreanVerificationRequest 안에 있으므로 여기서 직접 돌린다.
describe("the SES send path", () => {
  const provider = () => buildEmailProvider(withQuota({ env: SMTP_ENV }));

  const send = (p: unknown) => {
    const options = configured(p).options;
    return options.sendVerificationRequest!({
      identifier: "woojoo@phraselog.test",
      url: "https://phraselog.app/api/auth/callback/nodemailer?token=raw",
      provider: { server: options.server, from: options.from },
    });
  };

  beforeEach(() => {
    quotaCalls = [];
    quotaCheck = async (identifier: string) => {
      quotaCalls.push(identifier);
    };
    sendMail = mock(async (): Promise<SendMailResult> => ({}));
    lastTransportConfig = null;
    lastMail = null;
    sendCount = 0;
  });

  test("sends Korean mail through the configured SES endpoint", async () => {
    const p = provider();
    await send(p);

    expect(lastTransportConfig).toEqual(configured(p).options.server);
    expect(sendCount).toBe(1);

    const mail = lastMail!;
    expect(mail.to).toBe("woojoo@phraselog.test");
    expect(mail.from).toBe("PhraseLog <noreply@phraselog.test>");
    expect(mail.subject).toBe("PhraseLog 로그인 링크");
    // 본문이 영어 기본값으로 돌아가면 여기서 걸린다.
    expect(mail.text).toContain("아래 링크를 열면 로그인됩니다.");
    expect(mail.text).toContain(
      "https://phraselog.app/api/auth/callback/nodemailer?token=raw",
    );
    expect(mail.html).toContain(
      "https://phraselog.app/api/auth/callback/nodemailer?token=raw",
    );
  });

  test("asks the backend for room before handing anything to SES", async () => {
    // Auth.js는 발송과 토큰 저장을 동시에 시작한다. 저장 쪽에서 상한을 걸면 메일은 이미
    // 나간 뒤라, 할당량은 그대로 쓰이고 수신자는 행이 없는 죽은 링크를 받는다.
    await send(provider());

    expect(quotaCalls).toEqual(["woojoo@phraselog.test"]);
    expect(sendCount).toBe(1);
  });

  test("does not send at all when the address is over its cap", async () => {
    quotaCheck = async () => {
      throw new Error("rate_limit_exceeded");
    };

    await expect(send(provider())).rejects.toThrow("rate_limit_exceeded");
    expect(sendCount).toBe(0);
  });

  test("rejects when SMTP refuses the recipient", async () => {
    // SES가 주소를 거절해도 sendMail 자체는 성공으로 resolve 한다. rejected를 보지 않으면
    // 배달되지 않은 메일이 "이메일을 확인해주세요"가 된다.
    sendMail = mock(async () => ({ rejected: ["woojoo@phraselog.test"] }));

    await expect(send(provider())).rejects.toThrow(/could not be delivered/);
  });

  test("rejects when SMTP leaves the recipient pending", async () => {
    sendMail = mock(async () => ({ pending: ["woojoo@phraselog.test"] }));

    await expect(send(provider())).rejects.toThrow(/could not be delivered/);
  });

  test("propagates a transport failure instead of swallowing it", async () => {
    sendMail = mock(async () => {
      throw new Error("SES connection reset");
    });

    await expect(send(provider())).rejects.toThrow("SES connection reset");
  });
});

describe("in production without SMTP configuration", () => {
  test.each([
    ["AUTH_EMAIL_SERVER_HOST"],
    ["AUTH_EMAIL_SERVER_USER"],
    ["AUTH_EMAIL_SERVER_PASSWORD"],
    ["EMAIL_FROM"],
  ])("throws on send when %s is missing", async (missing) => {
    // Degrading to the dev logger in production would leave the user waiting for mail that was
    // never sent, with nothing in the logs to say so.
    const env = { ...SMTP_ENV, NODE_ENV: "production" } as Record<string, string>;
    delete env[missing];

    const provider = buildEmailProvider(withQuota({ env }));

    await expect(
      configured(provider).options.sendVerificationRequest!({
        identifier: "mia@example.com",
        url: "https://phraselog.test/api/auth/callback/nodemailer?token=abc",
      }),
    ).rejects.toThrow(/Email sign-in requires/);
  });

  test("still builds, so the app compiles without SMTP secrets", () => {
    // next build runs with NODE_ENV=production and evaluates route modules, which construct the
    // auth config. Throwing at construction made the app unbuildable in CI.
    expect(() =>
      buildEmailProvider(withQuota({ env: { NODE_ENV: "production" } })),
    ).not.toThrow();
  });
});

describe("in development without SMTP configuration", () => {
  test("logs the magic link instead of sending, and does not throw", async () => {
    const logged: { identifier: string; url: string }[] = [];
    const provider = buildEmailProvider(
      withQuota({
        env: { NODE_ENV: "development" },
        logMagicLink: (params: { identifier: string; url: string }) =>
          logged.push(params),
      }),
    );

    await configured(provider).options.sendVerificationRequest!({
      identifier: "mia@example.com",
      url: "http://localhost:3000/api/auth/callback/nodemailer?token=abc",
    });

    expect(logged).toEqual([
      {
        identifier: "mia@example.com",
        url: "http://localhost:3000/api/auth/callback/nodemailer?token=abc",
      },
    ]);
  });

  test("keeps the same 24h expiry as the real path", () => {
    const provider = buildEmailProvider(
      withQuota({ env: { NODE_ENV: "development" }, logMagicLink: () => {} }),
    );

    expect(configured(provider).options.maxAge).toBe(EMAIL_LINK_MAX_AGE_SECONDS);
  });
});
