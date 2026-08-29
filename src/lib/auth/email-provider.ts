import "server-only";

import type { EmailConfig } from "next-auth/providers";
import Nodemailer from "next-auth/providers/nodemailer";

import { checkSendQuota } from "./email-provisioning";

/**
 * S03 이메일 매직링크 provider. Amazon SES를 SMTP로 사용한다.
 *
 * 발송 실패는 반드시 reject 되어야 한다. 삼키면 Auth.js가 성공으로 보고 "이메일을 확인해주세요"
 * 화면을 띄우는데, 사용자는 오지 않을 메일을 기다리게 된다.
 *
 * 설정 누락도 같은 이유로 발송 시점에 던진다. 모듈 평가 시점에 던지면 `next build`가
 * NODE_ENV=production으로 라우트를 수집하다가 실패해 앱을 빌드할 수 없게 된다 — CI가 SMTP
 * 자격증명을 갖고 있어야 컴파일되는 구조는 잘못됐다. 빌드는 통과하되, 운영에서 실제로 보내려는
 * 순간 크게 실패한다.
 */

/** 링크 유효기간(초). 이슈 #19는 "~24h", S03은 "provider-default (typically 24h)". 명시 고정한다. */
export const EMAIL_LINK_MAX_AGE_SECONDS = 24 * 60 * 60;

export const MISSING_SMTP_CONFIG_MESSAGE =
  "Email sign-in requires AUTH_EMAIL_SERVER_HOST/PORT/USER/PASSWORD and EMAIL_FROM in production";

export type EmailProviderEnv = {
  AUTH_EMAIL_SERVER_HOST?: string;
  AUTH_EMAIL_SERVER_PORT?: string;
  AUTH_EMAIL_SERVER_USER?: string;
  AUTH_EMAIL_SERVER_PASSWORD?: string;
  EMAIL_FROM?: string;
  NODE_ENV?: string;
};

export type BuildEmailProviderOptions = {
  env?: EmailProviderEnv;
  /** 개발용 대체 경로에서 링크를 어디에 출력할지. 테스트에서 주입한다. */
  logMagicLink?: (params: { identifier: string; url: string }) => void;
  /** 발송 전 상한 확인. 테스트에서 주입한다. 기본값은 Spring Boot를 호출하는 실제 클라이언트. */
  checkSendQuota?: (identifier: string) => Promise<unknown>;
};

type SmtpSettings = {
  host: string;
  port: number;
  auth: { user: string; pass: string };
  connectionTimeout: number;
  greetingTimeout: number;
  socketTimeout: number;
};

/**
 * 발송은 사용자가 로그인 버튼을 누르고 기다리는 동안 일어난다. Nodemailer 기본값(연결·소켓
 * 각 2분)은 그 상황에 너무 길다 — SES가 느릴 때 요청 하나가 2분을 잡고 있게 된다.
 * 실패하더라도 빨리 실패해서 "보내지 못했어요"를 보여주는 편이 낫다.
 */
const SMTP_TIMEOUTS = {
  connectionTimeout: 10_000,
  greetingTimeout: 10_000,
  socketTimeout: 15_000,
} as const;

const PLACEHOLDER_SMTP: SmtpSettings = {
  // Nodemailer()는 server 없이 즉시 throw 한다. 설정이 없을 때 이 값이 쓰이는 일은 없다 —
  // sendVerificationRequest가 전송에 도달하기 전에 던지거나 링크를 출력하기 때문이다.
  host: "localhost",
  port: 25,
  auth: { user: "", pass: "" },
  ...SMTP_TIMEOUTS,
};

export function buildEmailProvider(options: BuildEmailProviderOptions = {}) {
  const env = options.env ?? (process.env as EmailProviderEnv);
  const smtp = readSmtpSettings(env);
  const from = env.EMAIL_FROM;
  const quota =
    options.checkSendQuota ??
    ((identifier: string) => checkSendQuota(identifier));

  if (smtp && from) {
    return Nodemailer({
      server: smtp,
      from,
      maxAge: EMAIL_LINK_MAX_AGE_SECONDS,
      sendVerificationRequest: underSendQuota(
        quota,
        sendKoreanVerificationRequest,
      ),
    });
  }

  return Nodemailer({
    server: PLACEHOLDER_SMTP,
    from: "PhraseLog <dev@localhost>",
    maxAge: EMAIL_LINK_MAX_AGE_SECONDS,
    sendVerificationRequest: underSendQuota(
      quota,
      unconfiguredSender(env, options.logMagicLink),
    ),
  });
}

/**
 * 상한 확인을 발송 앞에 붙인다.
 *
 * Auth.js가 발송과 토큰 저장을 동시에 시작하기 때문에(`signin/send-token.js`), 저장 쪽에서
 * 거절하면 이미 늦는다 — 메일은 나갔고 SES 할당량은 쓰였고, 수신자는 저장된 행이 없는 링크를
 * 받아 "링크가 만료됐어요"를 보게 된다. 실제로 막을 수 있는 유일한 지점이 여기다.
 *
 * 개발용 경로에도 똑같이 건다. 상한이 개발 중에 한 번도 실행되지 않으면 운영에서 처음 돈다.
 */
function underSendQuota<T extends { identifier: string }>(
  quota: (identifier: string) => Promise<unknown>,
  send: (params: T) => Promise<void>,
) {
  return async (params: T) => {
    await quota(params.identifier);
    return send(params);
  };
}

/**
 * SMTP가 설정되지 않았을 때의 발송 경로.
 *
 * 운영에서는 던진다. 아무 일도 하지 않고 성공으로 넘어가면 사용자는 오지 않을 메일을 기다리고,
 * 로그에도 아무 흔적이 남지 않는다.
 *
 * 개발에서는 링크를 콘솔에 출력한다. SES 프로덕션 액세스가 나오기 전에도 흐름 전체를 검증할 수
 * 있어야 하기 때문이다.
 */
function unconfiguredSender(
  env: EmailProviderEnv,
  logMagicLink: BuildEmailProviderOptions["logMagicLink"],
) {
  const log =
    logMagicLink ??
    (({ identifier, url }: { identifier: string; url: string }) => {
      console.warn(
        `[auth] SMTP is not configured; not sending mail. Magic link for ${identifier}: ${url}`,
      );
    });

  return async ({ identifier, url }: { identifier: string; url: string }) => {
    if (env.NODE_ENV === "production") {
      throw new Error(MISSING_SMTP_CONFIG_MESSAGE);
    }
    log({ identifier, url });
  };
}

async function sendKoreanVerificationRequest(
  params: Parameters<NonNullable<EmailConfig["sendVerificationRequest"]>>[0],
) {
  const { identifier, url, provider } = params;
  const { createTransport } = await import("nodemailer");
  const transport = createTransport(provider.server as SmtpSettings);

  const result = await transport.sendMail({
    to: identifier,
    from: provider.from,
    subject: "PhraseLog 로그인 링크",
    text: `아래 링크를 열면 로그인됩니다.\n\n${url}\n\n24시간 후 만료돼요. 본인이 요청한 게 아니라면 이 메일은 무시하셔도 됩니다.`,
    html: koreanHtml(url),
  });

  // rejected/pending을 확인하지 않으면 SMTP가 받아주지 않은 주소도 성공으로 보인다.
  const failed = [...(result.rejected ?? []), ...(result.pending ?? [])].filter(
    Boolean,
  );
  if (failed.length > 0) {
    throw new Error(
      `Magic link could not be delivered to ${failed.length} recipient(s)`,
    );
  }
}

function koreanHtml(url: string) {
  return `
<body style="background:#f6f6f6;padding:24px 0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif">
  <table width="100%" cellpadding="0" cellspacing="0" role="presentation">
    <tr><td align="center">
      <table width="420" cellpadding="0" cellspacing="0" role="presentation"
             style="background:#ffffff;border-radius:12px;padding:32px">
        <tr><td style="font-size:20px;font-weight:700;color:#111;padding-bottom:8px">PhraseLog</td></tr>
        <tr><td style="font-size:15px;color:#444;line-height:1.6;padding-bottom:24px">
          아래 버튼을 누르면 로그인됩니다.
        </td></tr>
        <tr><td align="center" style="padding-bottom:24px">
          <a href="${url}"
             style="display:inline-block;background:#111;color:#fff;text-decoration:none;
                    padding:14px 28px;border-radius:8px;font-size:15px;font-weight:600">
            로그인하기
          </a>
        </td></tr>
        <tr><td style="font-size:13px;color:#888;line-height:1.6">
          이 링크는 24시간 후 만료돼요.<br>
          본인이 요청한 게 아니라면 이 메일은 무시하셔도 됩니다.
        </td></tr>
      </table>
    </td></tr>
  </table>
</body>`;
}

function readSmtpSettings(env: EmailProviderEnv): SmtpSettings | null {
  const host = env.AUTH_EMAIL_SERVER_HOST;
  const port = Number(env.AUTH_EMAIL_SERVER_PORT);
  const user = env.AUTH_EMAIL_SERVER_USER;
  const pass = env.AUTH_EMAIL_SERVER_PASSWORD;

  if (!host || !user || !pass || !Number.isInteger(port) || port <= 0) {
    return null;
  }

  return { host, port, auth: { user, pass }, ...SMTP_TIMEOUTS };
}
