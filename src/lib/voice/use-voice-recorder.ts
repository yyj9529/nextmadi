"use client";

import {
  useCallback,
  useEffect,
  useState,
  useSyncExternalStore,
} from "react";

import {
  VoiceRecorderController,
  browserDeps,
  type VoiceRecorderDeps,
  type VoiceSnapshot,
} from "./recorder-controller";
import {
  INITIAL_VOICE_MACHINE,
  type VoiceMachine,
  type VoiceStatus,
} from "./recorder-machine";

// 음성 입력 훅. (#36)
//
// 판단은 recorder-machine.ts(순수 상태머신)에, 부수효과는 recorder-controller.ts(주입 가능한
// 오케스트레이터)에 있다. 여기 남은 일은 컨트롤러를 React 수명주기에 붙이는 것뿐이다 —
// 마이크 트랙·AudioContext·타이머·AbortController 해제처럼 위험한 부분이 훅 안에 있으면
// React 렌더러 없이는 한 줄도 검증할 수 없어서 밖으로 뺐다.

export type VoiceRecorder = {
  status: VoiceStatus;
  machine: VoiceMachine;
  /** 녹음 경과 시간(ms). 녹음 중에만 갱신된다. */
  elapsedMs: number;
  /** 마이크 버튼 탭 — 상태에 따라 시작 또는 정지. */
  tap: () => void;
  /** 실패 상태에서 처음부터 다시 녹음. */
  retry: () => void;
  /** 일시적 실패에서 같은 녹음을 재전송. */
  retryTranscribe: () => void;
  /** 진행 중인 녹음/전사를 버리고 idle로. */
  cancel: () => void;
  /** 안내 상태를 접고 idle로. 사용자가 텍스트 입력으로 넘어갈 때 쓴다. */
  dismiss: () => void;
};

export type UseVoiceRecorderOptions = {
  /** 전사문이 확정됐을 때 호출된다. S02는 입력창, S04는 S05a 모달에 채운다. */
  onTranscript: (transcript: string) => void;
  /** 테스트에서 브라우저 API를 갈아끼우기 위한 통로. 실제 화면에서는 넘기지 않는다. */
  deps?: VoiceRecorderDeps;
};

const SERVER_SNAPSHOT: VoiceSnapshot = {
  machine: INITIAL_VOICE_MACHINE,
  elapsedMs: 0,
};

export function useVoiceRecorder({
  onTranscript,
  deps,
}: UseVoiceRecorderOptions): VoiceRecorder {
  // useState 지연 초기화로 컨트롤러를 한 번만 만든다. ref 로 하면 렌더 중에 ref 를 읽게 되고,
  // 그건 동시성 렌더에서 안전하지 않다(react-hooks/refs).
  const [controller] = useState(
    () => new VoiceRecorderController(deps ?? browserDeps(), onTranscript),
  );

  // prop이 매 렌더 새로 만들어져도 컨트롤러를 다시 만들지 않는다 — 콜백만 갈아끼운다.
  useEffect(() => {
    controller.setOnTranscript(onTranscript);
  }, [controller, onTranscript]);

  // 언마운트: 화면을 떠나도 마이크와 진행 중 요청을 반드시 정리한다.
  // 이 정리는 진짜 언마운트가 아닐 때도 돈다(StrictMode 이중 호출, Fast Refresh). 컨트롤러는
  // useState가 들고 있어 그 경우 같은 인스턴스가 그대로 살아 렌더되므로, dispose()는 되돌릴
  // 수 있어야 한다 — 아래 useSyncExternalStore가 다시 구독하면서 되살린다(#143).
  useEffect(() => () => controller.dispose(), [controller]);

  const snapshot = useSyncExternalStore(
    controller.subscribe,
    controller.getSnapshot,
    () => SERVER_SNAPSHOT,
  );

  const dispatch = controller.dispatch;
  return {
    status: snapshot.machine.status,
    machine: snapshot.machine,
    elapsedMs: snapshot.elapsedMs,
    tap: useCallback(() => dispatch({ type: "tap" }), [dispatch]),
    retry: useCallback(() => dispatch({ type: "retry" }), [dispatch]),
    retryTranscribe: useCallback(
      () => dispatch({ type: "retry_transcribe" }),
      [dispatch],
    ),
    cancel: useCallback(() => dispatch({ type: "cancel" }), [dispatch]),
    dismiss: useCallback(() => dispatch({ type: "reset" }), [dispatch]),
  };
}
