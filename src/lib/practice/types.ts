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

/** GET /practice/sessions/{id} 상태. turns는 turn_number 오름차순. */
export type RoleplaySession = {
  id: string;
  status: "active" | "completed" | "abandoned";
  planned_turns: number;
  coach_id: string;
  expression_id: string | null;
  started_at: string;
  ended_at: string | null;
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
