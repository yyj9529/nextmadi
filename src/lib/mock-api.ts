// PPT 충실도 패스 공용 목 데이터.
// 실제 API 계약은 docs/api/openapi.yaml — 각 블록 주석에 대응하는
// 실제 메서드/경로를 적어 두어 후속 BFF 연동 시 교체 지점이 명확하도록 한다.
// 안정적인 목 ID: mock-analysis / mock-expression-1 / mock-session-1

export const MOCK_ANALYSIS_ID = "mock-analysis";
export const MOCK_EXPRESSION_ID = "mock-expression-1";
export const MOCK_SESSION_ID = "mock-session-1";

// ---- openapi.yaml 스키마와 동일한 형태의 타입 ----

export type LandingExample = {
  id: string;
  korean_text: string;
};

export type Coach = {
  id: string;
  slug: "mia" | "david" | "sarah";
  display_name: string;
  persona_summary: string;
  tts_voice_id: string;
};

export type ExpressionVariant = {
  id: string;
  variant_order: 1 | 2 | 3;
  tone_label: string;
  english_text: string;
  ipa: string;
  korean_pronunciation: string;
  pronunciation_tip: string | null;
  cultural_tip: string | null;
};

export type ExpressionListItem = {
  id: string;
  original_situation: string;
  english_text: string;
  tone_label: string;
  created_at: string;
  relative_time: string; // 표시용 — 실제 구현에선 created_at에서 계산
};

export type Expression = {
  id: string;
  source_type: "analysis" | "roleplay_result";
  original_situation: string;
  selected_variant_id: string;
  variants: ExpressionVariant[];
  review_card_id: string | null;
  next_review_at: string | null;
  created_at: string;
};

export type ReviewCard = {
  id: string;
  expression_id: string;
  next_review_at: string;
  current_interval_days: number;
  original_situation: string;
  variant: ExpressionVariant;
};

export type PracticeTurn = {
  id: string;
  turn_number: number;
  speaker: "user" | "coach";
  text_content: string;
  feedback_shown: boolean;
  // POST /practice/sessions/{id}/turns 응답의 feedback(TurnFeedback)을
  // 턴에 붙여 둔 표시용 필드.
  feedback: { natural_alternative: string; korean_comment: string } | null;
};

export type PracticeResult = {
  coach_encouragement: string;
  recommended_expressions: {
    english: string;
    tone_label: string;
    ipa: string;
    korean_pronunciation: string;
  }[];
  awkward_pairs: {
    user_said: string;
    natural_version: string;
    comment: string;
  }[];
  pronunciation_focus_words: string[];
  pronunciation_focus_comment: string;
};

// ---- S01: GET /landing/examples ----
// 활성 풀에서 무작위 3개 — 목에서는 고정 3개.
export const mockLandingExamples: LandingExample[] = [
  {
    id: "mock-landing-example-1",
    korean_text: "마트에서 줄 새치기한 사람한테 한마디 하고 싶었어",
  },
  {
    id: "mock-landing-example-2",
    korean_text: "병원에서 보험 카드 어떻게 보여줘야 할지 머뭇거렸어",
  },
  {
    id: "mock-landing-example-3",
    korean_text: "학교 선생님 말을 못 알아들었는데 다시 물어볼 영어가 안 떠올랐어",
  },
];

// ---- S03b / S11: GET /coaches ----
export const mockCoaches: Coach[] = [
  {
    id: "mock-coach-mia",
    slug: "mia",
    display_name: "Mia",
    persona_summary: "친절한 코치 · 격려·안심 위주",
    tts_voice_id: "mock-voice-mia",
  },
  {
    id: "mock-coach-david",
    slug: "david",
    display_name: "David",
    persona_summary: "근엄한 코치 · 정확성 강조",
    tts_voice_id: "mock-voice-david",
  },
  {
    id: "mock-coach-sarah",
    slug: "sarah",
    display_name: "Sarah",
    persona_summary: "프로페셔널 코치 · 뉘앙스·문화 컨텍스트",
    tts_voice_id: "mock-voice-sarah",
  },
];

// 코치 카드 표시용 예시 멘트 (s03b PNG 기준).
export const mockCoachSampleLines: Record<Coach["slug"], string> = {
  mia: "“괜찮아요! 천천히 해도 돼요”",
  david: "“이 표현이 더 정확합니다”",
  sarah: "“Native들은 이렇게도 말해요”",
};

// ---- S07 / S09: 표현 변형 3종 (GET /analysis/{id}, GET /expressions/{id}) ----
export const mockVariants: ExpressionVariant[] = [
  {
    id: "mock-variant-1",
    variant_order: 1,
    tone_label: "정중한",
    english_text: "Excuse me, I think there's a line.",
    ipa: "/ɪkˈskjuːz miː, aɪ θɪŋk ðɛrz ə laɪn/",
    korean_pronunciation: "익스큐즈 미, 아이 띵크 데어즈 어 라인",
    pronunciation_tip: "🗣️ 발음 팁: 'Excuse'의 X는 'gz' 소리",
    cultural_tip: "🇺🇸 미국에선 정중한 지적이 자연스러워요",
  },
  {
    id: "mock-variant-2",
    variant_order: 2,
    tone_label: "직설적",
    english_text: "Hey, the line starts back there.",
    ipa: "/heɪ, ðə laɪn stɑːrts bæk ðɛr/",
    korean_pronunciation: "헤이, 더 라인 스타츠 백 데어",
    pronunciation_tip: null,
    cultural_tip: null,
  },
  {
    id: "mock-variant-3",
    variant_order: 3,
    tone_label: "단호한",
    english_text: "Please go to the back of the line.",
    ipa: "/pliːz ɡoʊ tə ðə bæk əv ðə laɪn/",
    korean_pronunciation: "플리즈 고 투 더 백 오브 더 라인",
    pronunciation_tip: null,
    cultural_tip: null,
  },
];

// ---- S09: GET /expressions/{expression_id} (실데이터 연결 완료: #47) ----
// 상세/삭제/큐 제거는 BFF(/api/expressions/[id], /api/review/[id]/remove-from-queue)로 연동됨.
// 아래 목은 참조용으로만 남긴다. re-add-to-queue는 상세 GET이 제거된 카드 id를 안 내려 v1 보류.
export const mockExpressionDetail: Expression = {
  id: MOCK_EXPRESSION_ID,
  source_type: "analysis",
  original_situation: "줄 새치기 상황",
  selected_variant_id: "mock-variant-1",
  variants: mockVariants,
  review_card_id: "mock-review-card-1",
  next_review_at: "2026-06-15T09:00:00Z",
  created_at: "2026-06-12T09:00:00Z",
};

// S09 복습 정보 배너 표시용.
export const mockExpressionReviewMeta = {
  nextReviewLabel: "3일 후",
  lastResultLabel: "잘 기억함",
};

// ---- S08: GET /expressions?limit=20 (검색: GET /expressions?q={keyword}) ----
export const mockLibraryItems: ExpressionListItem[] = [
  {
    id: "mock-expression-1",
    original_situation: "줄 새치기 상황",
    english_text: "Excuse me, I think there's a line.",
    tone_label: "정중한",
    created_at: "2026-06-12T07:00:00Z",
    relative_time: "2일 전",
  },
  {
    id: "mock-expression-2",
    original_situation: "병원 증상 설명",
    english_text: "My daughter has had a fever for two days.",
    tone_label: "정중한",
    created_at: "2026-06-11T07:00:00Z",
    relative_time: "3일 전",
  },
  {
    id: "mock-expression-3",
    original_situation: "직장 갈등 대화",
    english_text: "I need to talk to you about something.",
    tone_label: "단호한",
    created_at: "2026-06-09T07:00:00Z",
    relative_time: "5일 전",
  },
  {
    id: "mock-expression-4",
    original_situation: "학부모 small talk",
    english_text: "Crazy week. How about you?",
    tone_label: "캐주얼",
    created_at: "2026-06-05T07:00:00Z",
    relative_time: "1주 전",
  },
];

// ---- S10: GET /review/today?limit=10 ----
// POST /review/{review_card_id}/submit → 목에서는 다음 카드로 진행.
export const mockReviewQueue: { cards: ReviewCard[]; total_due: number } = {
  cards: [
    {
      id: "mock-review-card-1",
      expression_id: "mock-expression-1",
      next_review_at: "2026-06-12T00:00:00Z",
      current_interval_days: 1,
      original_situation: "마트에서 줄 새치기한 사람한테 한마디 하고 싶었어요",
      variant: mockVariants[0],
    },
    {
      id: "mock-review-card-2",
      expression_id: "mock-expression-2",
      next_review_at: "2026-06-12T00:00:00Z",
      current_interval_days: 2,
      original_situation: "병원에서 아이 증상을 설명해야 했어요",
      variant: {
        id: "mock-variant-4",
        variant_order: 1,
        tone_label: "정중한",
        english_text: "My daughter has had a fever for two days.",
        ipa: "/maɪ ˈdɔːtər hæz hæd ə ˈfiːvər fər tuː deɪz/",
        korean_pronunciation: "마이 도터 해즈 해드 어 피버 포 투 데이즈",
        pronunciation_tip: null,
        cultural_tip: null,
      },
    },
    {
      id: "mock-review-card-3",
      expression_id: "mock-expression-3",
      next_review_at: "2026-06-12T00:00:00Z",
      current_interval_days: 1,
      original_situation: "직장 동료에게 할 말이 있다고 먼저 운을 떼고 싶었어요",
      variant: {
        id: "mock-variant-5",
        variant_order: 1,
        tone_label: "단호한",
        english_text: "I need to talk to you about something.",
        ipa: "/aɪ niːd tə tɔːk tə juː əˈbaʊt ˈsʌmθɪŋ/",
        korean_pronunciation: "아이 니드 투 톡 투 유 어바웃 썸띵",
        pronunciation_tip: null,
        cultural_tip: null,
      },
    },
  ],
  total_due: 3,
};

// ---- S11: GET /me + GET /usage/today ----
// PATCH /me { display_name | selected_coach_id } → 로컬 상태 갱신.
// DELETE /me → 확인 후 / 라우팅. POST /me/cancel-deletion은 v1 목 범위 밖.
export const mockMe = {
  id: "mock-user-1",
  email: "jiyoung@gmail.com",
  display_name: "지영",
  selected_coach_id: "mock-coach-david",
  is_onboarded: true,
  created_at: "2026-04-01T00:00:00Z",
  joined_label: "2026.04.01",
};

export const mockUsageToday = {
  roleplay_session_count: 1,
  daily_roleplay_limit: 2,
  analysis_count: 3,
  analysis_limit: null as number | null, // null = 무제한
};

// ---- S12: POST /practice/sessions → GET /practice/sessions/{session_id} ----
// 세션 시작 시 코치 오프닝 턴 포함. 이후 사용자 입력마다
// POST /practice/sessions/{session_id}/turns 가 user_turn + coach_turn +
// feedback을 돌려준다. 목에서는 아래 스크립트를 순서대로 재생한다.
export const mockSession = {
  id: MOCK_SESSION_ID,
  status: "active" as const,
  planned_turns: 3,
  coach_id: "mock-coach-david",
  expression_id: MOCK_EXPRESSION_ID,
  scenario_label: "식당에서 알레르기 알리기",
  opening_turn: {
    id: "mock-turn-1",
    turn_number: 1,
    speaker: "coach",
    text_content: "I'll help you practice ordering at a restaurant.",
    feedback_shown: false,
    feedback: null,
  } satisfies PracticeTurn,
};

// 사용자 발화 → 코치 응답 + 피드백 스크립트 (턴 진행 시 순서대로 소비).
export const mockTurnScript: {
  user: PracticeTurn;
  coach: PracticeTurn;
}[] = [
  {
    user: {
      id: "mock-turn-2",
      turn_number: 2,
      speaker: "user",
      text_content: "I'm allergic to peanuts.",
      feedback_shown: true,
      feedback: {
        natural_alternative: "I have a peanut allergy.",
        korean_comment: "이렇게 말하면 더 자연스러워요",
      },
    },
    coach: {
      id: "mock-turn-3",
      turn_number: 3,
      speaker: "coach",
      text_content: "Got it. Anything else I should know?",
      feedback_shown: false,
      feedback: null,
    },
  },
  {
    user: {
      id: "mock-turn-4",
      turn_number: 4,
      speaker: "user",
      text_content: "No, that's all. Thank you.",
      feedback_shown: false,
      feedback: null,
    },
    coach: {
      id: "mock-turn-5",
      turn_number: 5,
      speaker: "coach",
      text_content: "Perfect. Your table will be ready soon!",
      feedback_shown: false,
      feedback: null,
    },
  },
];

// ---- S12b: GET /practice/sessions/{id} + POST /practice/sessions/{id}/result ----
// 저장 버튼: POST /practice/sessions/{session_id}/save-expression
// { recommended_expression_index } → 목에서는 "저장됨 ✓" 토글.
export const mockPracticeResult: PracticeResult = {
  coach_encouragement: "David: 오늘 정말 잘하셨어요!",
  recommended_expressions: [
    {
      english: "I'm allergic to peanuts.",
      tone_label: "기본",
      ipa: "/aɪm əˈlɜːrdʒɪk tə ˈpiːnʌts/",
      korean_pronunciation: "아임 얼러직 투 피넛츠",
    },
  ],
  awkward_pairs: [
    {
      user_said: "I want eat",
      natural_version: "I'd like to eat",
      comment: "식당에선 더 정중한 표현이 자연스러워요",
    },
  ],
  pronunciation_focus_words: ["th", "r", "l"],
  pronunciation_focus_comment: "한국인이 자주 헷갈리는 발음 위주",
};

// ---- S04: GET /home/dashboard (실데이터 연결 완료: home/HomeDashboard.tsx, #55) ----
// S05a/S06 → POST /analysis (성공 시 /save/result/mock-analysis 라우팅)
// S02 음성 → POST /transcriptions 후 텍스트 확정 → POST /analysis
export const mockAnalysisResultPath = `/save/result/${MOCK_ANALYSIS_ID}`;
