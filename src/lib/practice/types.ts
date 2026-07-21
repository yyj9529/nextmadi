// S12 롤플레이 공용 타입. openapi.yaml의 PracticeSession / PracticeTurn / TurnFeedback와
// 백엔드 DTO(PracticeSessionResponse, PracticeTurnResponse, SubmitPracticeTurnResponse)에
// 대응한다. BFF lib과 클라이언트가 함께 쓴다.

export type RoleplayTurn = {
  id: string;
  turn_number: number;
  speaker: "user" | "coach";
  /** STT 실패/시스템 턴이면 null일 수 있다(openapi nullable). */
  text_content: string | null;
  /** TTS는 #30 — 현재 백엔드는 null. UI는 null(텍스트 전용)을 허용해야 한다. */
  tts_audio_url: string | null;
  stt_confidence: number | null;
  feedback_shown: boolean;
  created_at: string;
};

export type RoleplayTurnFeedback = {
  show_feedback: boolean;
  natural_alternative: string | null;
  korean_comment: string | null;
};

/**
 * Sonnet가 생성해 practice_sessions.result_json에 캐시하는 S12b 결과.
 * openapi PracticeResult / roleplay_result_v1.json 스키마에 대응한다.
 * (mock-api의 PracticeResult와 달리 실제 스키마엔 pronunciation_focus_comment가 없고,
 * 추천 표현엔 pronunciation_tip / cultural_tip가 있다.)
 */
export type RoleplayResult = {
  coach_encouragement: string;
  recommended_expressions: {
    english: string;
    tone_label: string;
    ipa: string;
    korean_pronunciation: string;
    pronunciation_tip: string;
    cultural_tip: string;
  }[];
  awkward_pairs: {
    user_said: string;
    natural_version: string;
    comment: string;
  }[];
  pronunciation_focus_words: string[];
};

/** GET /practice/sessions/{id} 상태. turns는 turn_number 오름차순. */
export type RoleplaySession = {
  id: string;
  status: "active" | "completed" | "abandoned";
  planned_turns: number;
  coach_id: string;
  expression_id: string | null;
  started_at: string;
  ended_at: string | null;
  /** S12b 캐시 결과. POST /result 성공 전에는 null(s12b.md AC1). */
  result_json: RoleplayResult | null;
  turns: RoleplayTurn[];
};

/** POST /practice/sessions 201 — 세션 필드 + 오프닝 코치 턴. */
export type StartSessionResult = {
  id: string;
  status: "active" | "completed" | "abandoned";
  planned_turns: number;
  coach_id: string;
  opening_turn: RoleplayTurn | null;
};

/** POST /practice/sessions/{id}/turns 200. */
export type SubmitTurnResult = {
  turn_consumed: boolean;
  /** turn_consumed=false일 때 재시도 안내 문구. */
  retry_prompt: string | null;
  user_turn: RoleplayTurn | null;
  coach_turn: RoleplayTurn | null;
  feedback: RoleplayTurnFeedback | null;
  session_status: "active" | "completed";
};
