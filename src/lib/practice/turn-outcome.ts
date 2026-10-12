// 턴 제출 응답이 "이 논리 턴의 결과가 확정됐다"는 뜻인지 판정한다. (C6-04 리뷰 finding 1)
//
// Idempotency-Key는 같은 내용을 다시 보낼 때 서버가 중복 턴을 만들지 않게 하는 장치다. 키를 버려도
// 되는 때는 서버가 이 요청을 끝까지 처리했거나(성공), 내용 자체를 거절해서 아무것도 만들지 않았다는
// 것을 응답으로 알 수 있을 때뿐이다.
//
// 502/504 같은 실패는 확정이 아니다. BFF가 자체 시간 제한으로 먼저 포기해 502를 줘도 백엔드는 계속
// 처리해 턴을 커밋할 수 있다. 이때 새 키로 재시도하면 같은 답이 두 번 기록된다. 같은 키로 보내야
// 서버가 이미 끝난 결과는 재생하고, 실패로 마감된 것은 다시 처리한다(reserveRequest의 failed 재오픈).

/** 2xx와, 내용이나 세션 상태 때문에 서버가 처리를 거절한 상태. */
const DEFINITE_REJECTIONS = new Set([
  400, // 요청/녹음 규격 오류
  404, // 세션 없음
  409, // 세션이 active가 아님
  413, // 녹음이 너무 큼
  422, // 무발화
]);

export function isDefiniteTurnOutcome(status: number): boolean {
  return (status >= 200 && status < 300) || DEFINITE_REJECTIONS.has(status);
}
