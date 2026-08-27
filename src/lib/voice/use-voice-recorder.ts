"use client";

import { useCallback, useEffect, useReducer, useRef, useState } from "react";

import { checkBrowserRecordingSupport } from "./capability";
import {
  INITIAL_VOICE_MACHINE,
  holdsMicrophone,
  reduceVoiceMachine,
  type VoiceMachine,
  type VoiceStatus,
} from "./recorder-machine";
import { SilenceMonitor, rmsFromTimeDomain } from "./silence-monitor";

// 상태머신(recorder-machine.ts)에 브라우저 API를 붙이는 층. (#36)
//
// 갈림길 판단은 전부 순수 함수 쪽에 있고, 여기서는 부수효과만 다룬다:
// getUserMedia / MediaRecorder / AudioContext / 타이머 / fetch, 그리고 자원 해제.
//
// **MediaRecorder.start()에 timeslice를 주지 않는다.** 조각으로 뱉으면 WebM 헤더의
// Segment 크기와 Duration이 패치되지 않아 백엔드 WebmOpusInspector가 400으로 거부한다
// (2026-08-04 실측, WebmOpusInspectorRealRecordingTests 참고).

// 스펙 상한은 60초지만 58초에 끊는다. 이 타이머는 100ms 간격이고 stop() 이후 마지막 chunk가
// 합쳐지기까지도 시간이 걸려서, 60_000에 끊으면 실제 녹음이 60.0초를 넘길 수 있다. 백엔드
// WebmOpusInspector는 durationSeconds > 60.0을 400으로 거부하므로, 하드컷으로 끝난 녹음이
// "녹음이 올바르지 않아요"로 실패한다. 2초 여유를 둔다.
const MAX_DURATION_MS = 58_000;
const VAD_INTERVAL_MS = 100;
/** 백엔드 STT 타임아웃 30초(AI_PIPELINE.md) + 업로드/응답 여유. */
const TRANSCRIBE_TIMEOUT_MS = 45_000;
const MIME_TYPE = "audio/webm;codecs=opus";

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
};

export type UseVoiceRecorderOptions = {
  /** 전사문이 확정됐을 때 호출된다. S02는 입력창, S04는 S05a 모달에 채운다. */
  onTranscript: (transcript: string) => void;
};

export function useVoiceRecorder({
  onTranscript,
}: UseVoiceRecorderOptions): VoiceRecorder {
  const [machine, dispatch] = useReducer(
    reduceVoiceMachine,
    INITIAL_VOICE_MACHINE,
  );
  const [elapsedMs, setElapsedMs] = useState(0);

  const streamRef = useRef<MediaStream | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const blobRef = useRef<Blob | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const vadTimerRef = useRef<number | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  // 최신 콜백을 참조해, prop이 매 렌더 새로 만들어져도 효과가 재실행되지 않게 한다.
  const onTranscriptRef = useRef(onTranscript);
  useEffect(() => {
    onTranscriptRef.current = onTranscript;
  });

  const releaseMicrophone = useCallback(() => {
    if (vadTimerRef.current !== null) {
      window.clearInterval(vadTimerRef.current);
      vadTimerRef.current = null;
    }
    // 트랙만 놓고 recorder를 그대로 두면 활성 상태로 남는다 — 참조를 버리기 전에 멈춘다.
    // 이미 stop()된 경우(정상 경로)는 inactive라 건너뛴다.
    const recorder = recorderRef.current;
    if (recorder && recorder.state !== "inactive") {
      try {
        recorder.stop();
      } catch {
        // 이미 정리된 recorder — 무시한다.
      }
    }
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    recorderRef.current = null;
    void audioContextRef.current?.close().catch(() => {});
    audioContextRef.current = null;
  }, []);

  // 권한 요청 → 스트림 확보.
  useEffect(() => {
    if (machine.status !== "requesting_permission" || streamRef.current) {
      return;
    }

    let cancelled = false;
    // 포맷 지원 확인이 권한 요청보다 먼저다 — 백엔드가 받지 못할 포맷으로만 녹음할 수 있는
    // 브라우저(Safari)에서 마이크 권한을 묻고 녹음까지 시킨 뒤 400으로 실패시키면,
    // 사용자는 "다시 녹음해주세요"를 받고 다시 시도해도 같은 곳에 갇힌다(capability.ts).
    const support = checkBrowserRecordingSupport();
    if (!support.supported) {
      dispatch({ type: "unsupported", reason: support.reason });
      return;
    }

    navigator.mediaDevices
      .getUserMedia({ audio: true })
      .then((stream) => {
        if (cancelled) {
          // 그 사이 취소됐다면 잡자마자 놓는다 — 표시등이 남으면 안 된다.
          stream.getTracks().forEach((track) => track.stop());
          return;
        }
        streamRef.current = stream;
        dispatch({ type: "permission_granted" });
      })
      .catch(() => {
        if (!cancelled) {
          dispatch({ type: "permission_denied" });
        }
      });

    return () => {
      cancelled = true;
    };
  }, [machine.status]);

  // 녹음 시작 + VAD/60초 감시.
  useEffect(() => {
    const stream = streamRef.current;
    if (machine.status !== "recording" || !stream || recorderRef.current) {
      return;
    }

    setElapsedMs(0);

    // MediaRecorder 생성과 start()는 던질 수 있다. 권한 승인과 이 효과 사이에 트랙이 끝나면
    // start()가 InvalidStateError를 던지는데, 효과에서 던진 예외는 React가 트리를 통째로
    // 언마운트시켜 빈 화면이 된다 — 이 플로우의 설계 원칙이 "막다른 길 없음"인데 여기만 죽는다.
    let recorder: MediaRecorder;
    try {
      recorder = new MediaRecorder(
        stream,
        MediaRecorder.isTypeSupported(MIME_TYPE) ? { mimeType: MIME_TYPE } : undefined,
      );
    } catch {
      dispatch({ type: "unsupported", reason: "no_recorder" });
      return;
    }
    recorderRef.current = recorder;
    chunksRef.current = [];
    blobRef.current = null;

    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) {
        chunksRef.current.push(event.data);
      }
    };
    recorder.onstop = () => {
      blobRef.current = new Blob(chunksRef.current, {
        type: recorder.mimeType || MIME_TYPE,
      });
      chunksRef.current = [];
      dispatch({ type: "blob_ready" });
    };
    // 녹음 중 장치가 빠지거나 권한이 회수되면 여기로 온다. 핸들러가 없으면 상태머신은
    // recording 에 머문 채 경과 시간만 돌다가 60초 컷으로 stopping 에 들어가 갇힌다.
    recorder.onerror = () => {
      dispatch({ type: "recording_failed" });
    };

    try {
      recorder.start(); // timeslice 금지 — 위 주석 참고
    } catch {
      dispatch({ type: "recording_failed" });
      return;
    }

    const startedAt = Date.now();

    const monitor = new SilenceMonitor();
    let analyser: AnalyserNode | null = null;
    let samples: Uint8Array<ArrayBuffer> | null = null;
    try {
      const audioContext = new AudioContext();
      audioContextRef.current = audioContext;
      // suspended 상태로 열리면 RMS가 계속 0이라 VAD가 "발화 없음"으로 오판해 8초에 녹음을
      // 끊는다. 탭이 사용자 제스처라 보통은 running이지만 iOS Safari에서 특히 취약하다.
      if (audioContext.state === "suspended") {
        void audioContext.resume().catch(() => {});
      }
      analyser = audioContext.createAnalyser();
      analyser.fftSize = 2048;
      audioContext.createMediaStreamSource(stream).connect(analyser);
      samples = new Uint8Array(analyser.fftSize);
    } catch {
      // AudioContext를 못 열면 VAD 없이 간다 — 재탭과 60초 컷은 그대로 동작한다.
      analyser = null;
    }

    vadTimerRef.current = window.setInterval(() => {
      const elapsed = Date.now() - startedAt;
      setElapsedMs(elapsed);

      if (elapsed >= MAX_DURATION_MS) {
        dispatch({ type: "stop", reason: "max_duration" });
        return;
      }

      if (!analyser || !samples) {
        return;
      }
      analyser.getByteTimeDomainData(samples);
      const decision = monitor.sample(rmsFromTimeDomain(samples), elapsed);
      if (decision === "stop_silence") {
        dispatch({ type: "stop", reason: "silence" });
      } else if (decision === "stop_no_speech") {
        dispatch({ type: "stop", reason: "no_speech" });
      }
    }, VAD_INTERVAL_MS);

    return () => {
      if (vadTimerRef.current !== null) {
        window.clearInterval(vadTimerRef.current);
        vadTimerRef.current = null;
      }
    };
  }, [machine.status]);

  // 정지 요청 → MediaRecorder.stop(). 마지막 chunk는 onstop에서 blob으로 합쳐진다.
  useEffect(() => {
    if (machine.status !== "stopping") {
      return;
    }
    const recorder = recorderRef.current;
    if (recorder && recorder.state !== "inactive") {
      recorder.stop();
      return;
    }
    // recorder 가 없거나(참조 소실) 이미 inactive 인(스스로 멈춘) 비정상 경로.
    // 여기서 아무것도 하지 않으면 "마무리 중..."에 갇힌 채 마이크를 계속 쥔다 —
    // stopping 에서는 탭도 무시되므로 새로고침 말고는 빠져나올 길이 없다.
    // onstop 이 이미 blob 을 채웠다면 그대로 전사로 넘기고, 아니면 녹음 실패로 처리한다.
    if (blobRef.current && blobRef.current.size > 0) {
      dispatch({ type: "blob_ready" });
    } else {
      dispatch({ type: "recording_failed" });
    }
  }, [machine.status]);

  // 마이크 점유 해제: 녹음/정지 구간을 벗어나는 모든 경로에서 예외 없이.
  useEffect(() => {
    if (!holdsMicrophone(machine.status)) {
      releaseMicrophone();
    }
  }, [machine.status, releaseMicrophone]);

  // 전사 요청.
  useEffect(() => {
    if (machine.status !== "transcribing") {
      return;
    }
    const blob = blobRef.current;
    if (!blob || blob.size === 0) {
      dispatch({ type: "transcribe_failed", kind: "empty" });
      return;
    }

    const controller = new AbortController();
    abortRef.current = controller;
    const timeout = window.setTimeout(
      () => controller.abort(),
      TRANSCRIBE_TIMEOUT_MS,
    );

    const form = new FormData();
    form.append("audio", blob, "recording.webm");

    fetch("/api/transcriptions", {
      method: "POST",
      body: form,
      credentials: "same-origin",
      signal: controller.signal,
    })
      .then(async (response) => {
        if (response.ok) {
          const body = (await response.json()) as { transcript?: string };
          dispatch({ type: "transcribed", transcript: body.transcript ?? "" });
          return;
        }
        if (response.status === 422) {
          dispatch({ type: "transcribe_failed", kind: "empty" });
          return;
        }
        if (response.status === 400 || response.status === 413) {
          dispatch({ type: "transcribe_failed", kind: "invalid_audio" });
          return;
        }
        if (response.status === 429) {
          // 오늘 치를 다 썼다. 재시도를 권하면 같은 429가 반복될 뿐이다.
          dispatch({ type: "transcribe_failed", kind: "rate_limited" });
          return;
        }
        dispatch({ type: "transcribe_failed", kind: "transient" });
      })
      .catch(() => {
        if (!controller.signal.aborted || controller.signal.reason !== "cancel") {
          dispatch({ type: "transcribe_failed", kind: "transient" });
        }
      })
      .finally(() => {
        window.clearTimeout(timeout);
        if (abortRef.current === controller) {
          abortRef.current = null;
        }
      });

    return () => {
      window.clearTimeout(timeout);
      controller.abort("cancel");
    };
  }, [machine.status]);

  // 확인 단계: 전사문을 화면에 넘기고 즉시 idle로 되돌린다.
  useEffect(() => {
    if (machine.status === "confirm" && machine.transcript) {
      onTranscriptRef.current(machine.transcript);
      dispatch({ type: "reset" });
    }
  }, [machine.status, machine.transcript]);

  // 언마운트: 화면을 떠나도 마이크와 진행 중 요청을 반드시 정리한다.
  useEffect(
    () => () => {
      releaseMicrophone();
      abortRef.current?.abort("cancel");
    },
    [releaseMicrophone],
  );

  return {
    status: machine.status,
    machine,
    elapsedMs,
    tap: useCallback(() => dispatch({ type: "tap" }), []),
    retry: useCallback(() => dispatch({ type: "retry" }), []),
    retryTranscribe: useCallback(() => dispatch({ type: "retry_transcribe" }), []),
    cancel: useCallback(() => dispatch({ type: "cancel" }), []),
  };
}
