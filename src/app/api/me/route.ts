import { headers } from "next/headers";

import { auth } from "@/auth";
import { scheduleAccountDeletion } from "@/lib/user/account-deletion";
import { PatchMeError, patchMe } from "@/lib/user/patch-me";

// 현재 사용자 부분 수정 BFF. (#53, S03b 코치 선택 완료 / S11 프로필)
//
// 상태 변경 요청이므로 Origin이 있으면 호스트와 일치해야 한다(SameSite=Lax 보완). NextAuth 세션을
// 서버에서 확인하고 user_id로 내부 인증 토큰을 발급해 PATCH /api/v1/me 를 프록시한다. 본문의
// selected_coach_id / is_onboarded / display_name 만 검증해 전달하며, 사용자는 세션으로 식별한다.

const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_DISPLAY_NAME = 100;

type PatchRequestBody = {
  selected_coach_id?: unknown;
  is_onboarded?: unknown;
  display_name?: unknown;
};

function jsonError(status: number, errorCode: string, userMessage: string) {
  return Response.json(
    { error_code: errorCode, user_message: userMessage },
    { status },
  );
}

/** 상태 변경 요청의 CSRF 방어: Origin이 있으면 호스트와 일치해야 한다. */
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

export async function PATCH(request: Request): Promise<Response> {
  if (!(await isSameOrigin())) {
    return jsonError(403, "forbidden", "요청을 처리할 수 없어요.");
  }

  const session = await auth();
  const userId = session?.user?.id;
  if (!userId) {
    return jsonError(401, "unauthorized", "로그인이 필요해요.");
  }

  let body: PatchRequestBody;
  try {
    body = (await request.json()) as PatchRequestBody;
  } catch {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  const patch: {
    userId: string;
    selectedCoachId?: string;
    isOnboarded?: boolean;
    displayName?: string;
  } = { userId };

  if (body.selected_coach_id !== undefined) {
    if (
      typeof body.selected_coach_id !== "string" ||
      !UUID_RE.test(body.selected_coach_id)
    ) {
      return jsonError(400, "validation_failed", "요청을 확인해주세요.");
    }
    patch.selectedCoachId = body.selected_coach_id;
  }

  if (body.is_onboarded !== undefined) {
    // is_onboarded는 완료(true)만 API로 허용된다 — 되돌리기(false)는 불가.
    if (body.is_onboarded !== true) {
      return jsonError(400, "validation_failed", "요청을 확인해주세요.");
    }
    patch.isOnboarded = true;
  }

  if (body.display_name !== undefined) {
    if (
      typeof body.display_name !== "string" ||
      body.display_name.length > MAX_DISPLAY_NAME
    ) {
      return jsonError(400, "validation_failed", "요청을 확인해주세요.");
    }
    patch.displayName = body.display_name;
  }

  // 바꿀 필드가 하나도 없으면 무의미한 호출 — 400.
  if (
    patch.selectedCoachId === undefined &&
    patch.isOnboarded === undefined &&
    patch.displayName === undefined
  ) {
    return jsonError(400, "validation_failed", "요청을 확인해주세요.");
  }

  try {
    const result = await patchMe(patch);
    return Response.json(result, { status: 200 });
  } catch (error) {
    if (error instanceof PatchMeError && error.status === 400) {
      return jsonError(400, "validation_failed", "요청을 확인해주세요.");
    }
    return jsonError(
      502,
      "update_failed",
      "변경 사항을 저장하지 못했어요. 다시 시도해주세요.",
    );
  }
}

// 계정 삭제 예약 BFF. (#24, S11 User Story 3)
//
// 여기서 지워지는 데이터는 없다 — 백엔드가 users.scheduled_deletion_at 을 14일 뒤로 세우고,
// 실제 삭제는 유예가 지난 뒤 E02.3 잡이 한다. 그래서 유예 안에 다시 로그인하면 복원된다.
// 성공은 204라서 본문이 없다. 세션 종료(signOut)는 호출한 클라이언트가 한다 — 쿠키를 지우는
// 것은 브라우저 쪽 동작이고, 여기서 대신 하면 실패했을 때도 로그아웃돼 삭제된 것처럼 보인다.
export async function DELETE(): Promise<Response> {
  if (!(await isSameOrigin())) {
    return jsonError(403, "forbidden", "요청을 처리할 수 없어요.");
  }

  const session = await auth();
  const userId = session?.user?.id;
  if (!userId) {
    return jsonError(401, "unauthorized", "로그인이 필요해요.");
  }

  try {
    await scheduleAccountDeletion({ userId });
    return new Response(null, { status: 204 });
  } catch {
    return jsonError(
      502,
      "deletion_failed",
      "계정 삭제를 처리하지 못했어요. 다시 시도해주세요.",
    );
  }
}
