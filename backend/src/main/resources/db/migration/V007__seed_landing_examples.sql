-- Seed the S01 landing example pool. Table was created in V001 but never populated.
-- S01 samples `... WHERE is_active = true ORDER BY random() LIMIT 3`; tapping a card
-- pre-fills /try (S02), so each row is a first-person desire/goal statement matching
-- the /try placeholder tone ("친구한테 서운한 마음을 정중하게 표현하고 싶어요"). Desire
-- framing (…하고 싶어요) keeps each row a clean S07 analysis input — the AI knows what
-- the user wants to say — while still evoking the regret context that produced it.
--
-- Copy intent (PROJECT_CONTEXT.md): moments a Korean immigrant in the US couldn't say
-- what they meant — emotional precision, cultural nuance, assertiveness, and the
-- clarification/repair strategies underused per user research. No grammar-drill framing.
--
-- Idempotent per row on korean_text (landing_examples has no unique constraint):
-- re-running never duplicates and never clobbers edited rows.
-- Rollback:
--   DELETE FROM landing_examples WHERE korean_text IN (
--     '병원에서 증상을 좀 더 정확하게 설명하고 싶어요.',
--     '회의에서 다른 의견을 분위기 상하지 않게 말하고 싶어요.',
--     '아이 담임 선생님과 상담할 때 하고 싶은 말을 분명하게 전하고 싶어요.',
--     '층간소음 때문에 이웃한테 조심해 달라고 정중하게 부탁하고 싶어요.',
--     '산 물건을 부담 없이 환불해 달라고 요청하고 싶어요.',
--     '오래 못 본 친구에게 서운했던 마음을 관계 상하지 않게 전하고 싶어요.',
--     '주문한 음식이 잘못 나왔을 때 부담 없이 다시 말하고 싶어요.',
--     '보험 문제로 전화해서 따질 때 하고 싶은 말을 분명하게 하고 싶어요.',
--     '부담스러운 부탁을 미안한 마음 없이 정중하게 거절하고 싶어요.',
--     '이웃이 인사하며 말을 걸어올 때 자연스럽게 대화를 이어가고 싶어요.',
--     '서비스가 잘못됐을 때 화내지 않으면서도 분명하게 항의하고 싶어요.',
--     '상대가 한 말을 못 알아들었을 때 자연스럽게 다시 물어보고 싶어요.'
--   );

INSERT INTO landing_examples (korean_text)
SELECT v.korean_text
FROM (
  VALUES
    ('병원에서 증상을 좀 더 정확하게 설명하고 싶어요.'),
    ('회의에서 다른 의견을 분위기 상하지 않게 말하고 싶어요.'),
    ('아이 담임 선생님과 상담할 때 하고 싶은 말을 분명하게 전하고 싶어요.'),
    ('층간소음 때문에 이웃한테 조심해 달라고 정중하게 부탁하고 싶어요.'),
    ('산 물건을 부담 없이 환불해 달라고 요청하고 싶어요.'),
    ('오래 못 본 친구에게 서운했던 마음을 관계 상하지 않게 전하고 싶어요.'),
    ('주문한 음식이 잘못 나왔을 때 부담 없이 다시 말하고 싶어요.'),
    ('보험 문제로 전화해서 따질 때 하고 싶은 말을 분명하게 하고 싶어요.'),
    ('부담스러운 부탁을 미안한 마음 없이 정중하게 거절하고 싶어요.'),
    ('이웃이 인사하며 말을 걸어올 때 자연스럽게 대화를 이어가고 싶어요.'),
    ('서비스가 잘못됐을 때 화내지 않으면서도 분명하게 항의하고 싶어요.'),
    ('상대가 한 말을 못 알아들었을 때 자연스럽게 다시 물어보고 싶어요.')
) AS v(korean_text)
WHERE NOT EXISTS (
  SELECT 1 FROM landing_examples le WHERE le.korean_text = v.korean_text
);
