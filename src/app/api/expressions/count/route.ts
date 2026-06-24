import { auth } from "@/auth";
import { getBookshelfCount } from "@/lib/expression/get-bookshelf-count";

// S08 헤더 "📚 N권" 북카운트 BFF. (#46)
//
// GET /expressions 응답에 전체 개수가 없어, 대시보드 집계 API의 bookshelf_count를 재사용한다.
// 라이브러리 입장에선 "내 표현 개수"라는 단일 관심사이므로 카운트 출처(dashboard)는 감춘다.
// 실패해도 페이지 전체를 막지 않도록 클라이언트가 graceful하게 처리한다.

function jsonError(status: number, errorCode: string, userMessage: string) {
  return Response.json(
    { error_code: errorCode, user_message: userMessage },
    { status },
  );
}

export async function GET(): Promise<Response> {
  const session = await auth();
  const userId = session?.user?.id;
  if (!userId) {
    return jsonError(401, "unauthorized", "로그인이 필요해요.");
  }

  try {
    const count = await getBookshelfCount({ userId });
    return Response.json({ count }, { status: 200 });
  } catch {
    return jsonError(502, "count_failed", "개수를 불러오지 못했어요.");
  }
}
