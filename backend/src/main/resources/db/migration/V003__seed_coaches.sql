-- Seed the three v1 coach personas (Mia / David / Sarah) referenced by S03b and S11.
-- Source: prompts/roleplay/{slug}/v1.md persona blocks (prompt_template_ref points back to them).
-- tts_voice_id values are OpenAI TTS standard voices chosen as v1 defaults
-- (AI_PIPELINE.md leaves the final pick to listening tests); change via a later migration.
--
-- Idempotent on slug: re-running does not duplicate or clobber edited rows.
-- Rollback: DELETE FROM coach_profiles WHERE slug IN ('mia', 'david', 'sarah');

INSERT INTO coach_profiles (slug, display_name, persona_summary, tts_voice_id, prompt_template_ref)
VALUES
  ('mia', 'Mia',
   '친절한 코치. 긴장하거나 자책하기 쉬운 분께 잘 맞아요. 따뜻하고 차분하게, 다시 말해볼 여유를 주며 이끌어 줍니다.',
   'shimmer', 'prompts/roleplay/mia/v1.md'),
  ('david', 'David',
   '근엄한 코치. 직장·서비스·예약처럼 명확함이 필요한 상황 연습에 잘 맞아요. 공격적이지 않으면서 자신감 있게 말하도록 핵심을 짚어 줍니다.',
   'onyx', 'prompts/roleplay/david/v1.md'),
  ('sarah', 'Sarah',
   '프로페셔널 코치. 이웃·친구·학부모 모임 같은 일상 대화와 관계 회복 상황에 잘 맞아요. 친근하면서도 뜻은 분명하게 전달하도록 도와줍니다.',
   'nova', 'prompts/roleplay/sarah/v1.md')
ON CONFLICT (slug) DO NOTHING;
