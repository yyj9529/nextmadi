-- 익명 STT 하루 한도용 카운터.
--
-- anonymous_analysis_usage 와 같은 모양이지만 별도 테이블이다. 전사와 분석은 한 예산을
-- 공유하면 안 된다 — 오전사 한 번이 분석 기회를 태우면, 전사를 분석과 분리한 2-스텝 결정
-- (2026-06-10, s02.md US1-4) 자체가 무너진다. 그 분리의 목적이 "잘못 들린 문장에 LLM 호출과
-- 익명 2회 한도를 쓰지 않는 것"이기 때문이다.
CREATE TABLE anonymous_transcription_usage (
  ip_address INET NOT NULL,
  usage_date DATE NOT NULL,
  count INTEGER NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT pk_anonymous_transcription_usage PRIMARY KEY (ip_address, usage_date)
);

-- 날짜별 정리(오래된 행 삭제)용. anonymous_analysis_usage 의 idx_anon_usage_date 와 같은 목적.
CREATE INDEX idx_anon_transcription_usage_date ON anonymous_transcription_usage(usage_date);
