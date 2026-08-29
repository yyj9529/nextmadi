import { randomUUID } from "node:crypto";

import { cookies, headers } from "next/headers";

import { auth } from "@/auth";
import { ANON_SESSION_COOKIE } from "@/lib/anon-session";
import { TranscribeError, transcribeAudio } from "@/lib/voice/transcribe";

// 음성 입력 STT BFF 라우트. (#36, 백엔드 #29)
//
// 브라우저가 녹음한 WebM/Opus를 multipart로 받아 Spring의 POST /api/v1/transcriptions로
// 프록시하고 전사문만 돌려준다. 분석(LLM)은 여기서 일어나지 않는다 — 사용자가 전사문을
// 확인·편집한 뒤 기존 POST /api/analysis로 따로 보낸다(s02.md US1-4).
//
// 인증 사용자는 user_id, 가입 전 S02 호출은 익명 session_token으로 내부 인증 토큰을 발급한다.
// 익명 쿠키 취급은 analysis 라우트와 동일하게 맞춘다 — 여기서 새 방식을 만들지 않는다.
//
// 주의: 오디오와 전사문은 사용자 데이터다 — 로그에 남기지 않는다(SECURITY.md).

// 60초 WebM/Opus는 1MB를 넘지 않는다(실측: 3초 ≈ 48KB). 여유를 두되 상한은 둔다 —
// 백엔드도 60초를 강제하지만, 큰 본문을 백엔드까지 흘려보낼 이유가 없다.
const MAX_AUDIO_BYTES = 4 * 1024 * 1024;

/** 백엔드 STT 타임아웃 30초 + 업로드/응답 여유. */
const BACKEND_TIMEOUT_MS = 40_000;

function jsonError(status: number, errorCode: string, userMessage: string) {
  return Response.json(
    { error_code: errorCode, user_message: userMessage },
    { status },
  );
}

/** X-Forwarded-For의 첫 IP만 취한다 — Spring은 forwarding chain이 아닌 단일 IP를 기대한다. */
function clientIpFrom(forwardedFor: string | null): string | null {
  if (!forwardedFor) {
    return null;
  }
  const first = forwardedFor.split(",")[0]?.trim();
  return first && first.length > 0 ? first : null;
}

/** 상태 변경 요청의 CSRF 방어: Origin이 있으면 호스트와 일치해야 한다(SameSite=Lax 보완). */
async function isSameOrigin(): Promise<boolean> {
  const headerStore = await headers();
  const origin = headerStore.get("origin");
  if (!origin) {
    return true;
  }
  const host = headerStore.get("host");
  try {
    return new URL(origin).host === host;
  } catch {
    return false;
  }
}

export async function POST(request: Request): Promise<Response> {
  if (!(await isSameOrigin())) {
    return jsonError(403, "forbidden", "요청을 처리할 수 없어요.");
  }

  let audio: unknown;
  try {
    audio = (await request.formData()).get("audio");
  } catch {
    return jsonError(400, "validation_failed", "녹음을 확인해주세요.");
  }

  if (!(audio instanceof Blob) || audio.size === 0) {
    return jsonError(400, "validation_failed", "녹음을 확인해주세요.");
  }
  if (audio.size > MAX_AUDIO_BYTES) {
    return jsonError(413, "audio_too_large", "녹음이 너무 길어요. 다시 녹음해주세요.");
  }

  // 인증 사용자면 user_id로, 아니면 가입 전 익명 session_token으로 호출한다(XOR 불변식).
  const session = await auth();
  const userId = session?.user?.id;

  const cookieStore = await cookies();
  let sessionToken: string | undefined;
  let mintedSessionToken: string | undefined;
  if (!userId) {
    sessionToken = cookieStore.get(ANON_SESSION_COOKIE)?.value;
    if (!sessionToken) {
      // 음성으로 먼저 들어온 첫 방문자. 뒤이을 분석 요청이 같은 토큰을 쓰도록 여기서 심는다.
      sessionToken = randomUUID();
      mintedSessionToken = sessionToken;
    }
  }

  // 익명 호출만 IP/day 한도 집계 대상이다. 이 헤더가 없으면 백엔드가 한도를 걸 키를 잃는다.
  const clientIp = clientIpFrom((await headers()).get("x-forwarded-for"));

  try {
    const result = await transcribeAudio(
      {
        audio,
        ...(userId ? { userId } : { sessionToken, clientIp }),
      },
      // 백엔드 STT 예산 30초(AI_PIPELINE.md)에 업로드/응답 여유를 더한다. 이 신호가 없으면
      // 백엔드가 매달릴 때 라우트도 함께 매달린다.
      { signal: AbortSignal.timeout(BACKEND_TIMEOUT_MS) },
    );

    const response = Response.json(
      { transcript: result.transcript, stt_confidence: result.sttConfidence },
      { status: 200 },
    );
    if (mintedSessionToken) {
      cookieStore.set(ANON_SESSION_COOKIE, mintedSessionToken, {
        httpOnly: true,
        sameSite: "lax",
        secure: process.env.NODE_ENV === "production",
        path: "/",
      });
    }
    return response;
  } catch (error) {
    if (error instanceof TranscribeError) {
      if (error.isEmptyTranscript) {
        // 소리는 들어왔지만 말이 잡히지 않았다 — 다시 말해달라고 안내한다.
        return jsonError(422, "empty_transcript", "말소리를 알아듣지 못했어요.");
      }
      if (error.isInvalidAudio) {
        return jsonError(400, "validation_failed", "녹음을 확인해주세요.");
      }
      if (error.isRateLimited) {
        // 오늘 치를 다 썼다. 재시도를 권하면 안 된다 — 텍스트 입력으로 안내한다.
        return jsonError(
          429,
          "rate_limit_exceeded",
          "오늘 사용할 수 있는 음성 입력 횟수를 모두 썼어요. 텍스트로 입력해보세요.",
        );
      }
      // 타임아웃·공급자 오류: 같은 녹음을 재전송할 가치가 있는 실패다.
      return jsonError(
        503,
        "transcription_failed",
        "변환에 실패했어요. 다시 시도해주세요.",
      );
    }
    return jsonError(
      503,
      "transcription_failed",
      "변환에 실패했어요. 다시 시도해주세요.",
    );
  }
}
