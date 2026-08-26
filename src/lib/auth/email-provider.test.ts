import { describe, expect, mock, test } from "bun:test";

mock.module("server-only", () => ({}));

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
    }) => Promise<void>;
  };
};

const configured = (provider: unknown) => provider as ConfiguredProvider;

const SMTP_ENV = {
  AUTH_EMAIL_SERVER_HOST: "email-smtp.us-east-1.amazonaws.com",
  AUTH_EMAIL_SERVER_PORT: "587",
  AUTH_EMAIL_SERVER_USER: "ses-user",
  AUTH_EMAIL_SERVER_PASSWORD: "ses-password",
  EMAIL_FROM: "PhraseLog <noreply@phraselog.test>",
};

describe("with SMTP configured", () => {
  test("builds a nodemailer provider pinned to a 24h link", () => {
    const provider = buildEmailProvider({
      env: { ...SMTP_ENV, NODE_ENV: "production" },
    });

    expect(configured(provider).id).toBe("nodemailer");
    expect(configured(provider).options.maxAge).toBe(EMAIL_LINK_MAX_AGE_SECONDS);
    expect(EMAIL_LINK_MAX_AGE_SECONDS).toBe(86400);
  });

  test("uses the configured sender and SMTP endpoint", () => {
    const provider = buildEmailProvider({ env: SMTP_ENV });

    expect(configured(provider).options.from).toBe(
      "PhraseLog <noreply@phraselog.test>",
    );
    expect(configured(provider).options.server).toEqual({
      host: "email-smtp.us-east-1.amazonaws.com",
      port: 587,
      auth: { user: "ses-user", pass: "ses-password" },
    });
  });

  test("treats a non-numeric port as no configuration at all", () => {
    // A typo'd port must not silently become NaN and fail at send time.
    const provider = buildEmailProvider({
      env: { ...SMTP_ENV, AUTH_EMAIL_SERVER_PORT: "not-a-port" },
      logMagicLink: () => {},
    });

    expect(configured(provider).options.from).toBe("PhraseLog <dev@localhost>");
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

    const provider = buildEmailProvider({ env });

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
      buildEmailProvider({ env: { NODE_ENV: "production" } }),
    ).not.toThrow();
  });
});

describe("in development without SMTP configuration", () => {
  test("logs the magic link instead of sending, and does not throw", async () => {
    const logged: { identifier: string; url: string }[] = [];
    const provider = buildEmailProvider({
      env: { NODE_ENV: "development" },
      logMagicLink: (params) => logged.push(params),
    });

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
    const provider = buildEmailProvider({
      env: { NODE_ENV: "development" },
      logMagicLink: () => {},
    });

    expect(configured(provider).options.maxAge).toBe(EMAIL_LINK_MAX_AGE_SECONDS);
  });
});
