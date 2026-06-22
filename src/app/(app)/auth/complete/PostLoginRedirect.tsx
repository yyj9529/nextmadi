"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";

import { readPendingSave, resolvePostAuthDestination } from "@/lib/pending-save";

// 온보딩 완료 사용자의 로그인 직후 라우팅. (#42, S03 AC3 / S07 US3-AC4)
//
// pending_save는 sessionStorage에만 있어 서버 컴포넌트가 읽을 수 없다. 그래서 온보딩된 사용자는
// 이 클라이언트 컴포넌트가 pending_save 유무를 보고 목적지를 정한다: 있으면 S07 저장 완료 상태로
// 돌아가 자동 저장을 재개하고(/home 아님), 없으면 홈으로 보낸다. 실제 저장과 정리는 S07의
// ResultActions가 수행한다.
export function PostLoginRedirect() {
  const router = useRouter();

  useEffect(() => {
    const destination = resolvePostAuthDestination({
      isOnboarded: true,
      pendingSave: readPendingSave(),
    });
    router.replace(destination.path);
  }, [router]);

  return (
    <p className="app-screen" role="status">
      이동 중...
    </p>
  );
}
