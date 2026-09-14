#!/usr/bin/env python3  # 이 파일을 python3 인터프리터로 실행하라는 유닉스 지시자 (shebang)
"""
S07 mini eval runner (Tier 1).
# S07 최소 평가 실행기 (1단계)

Implements EVAL_PLAN.md Tier 1 + ADR-009 (trial repetition for variance).
# EVAL_PLAN.md 1단계 + ADR-009(분산 측정을 위한 반복 실행) 구현

What it does, per the "Building with the Claude API" eval workflow:
# "Claude API로 구축하기" 평가 워크플로우 기준 동작 설명:
  1. Load the S07 prompt under prompts/s07/v{N}.md (front-matter stripped).
  #  1. prompts/s07/v{N}.md 에서 S07 프롬프트 로드 (front-matter 제거)
  2. For each case in eval/s07-analysis/test_cases.json:
  #  2. eval/s07-analysis/test_cases.json 의 각 케이스에 대해:
       - GENERATE: call the S07 model with system=prompt, user=input_text  -> JSON
       #     생성: S07 모델 호출 (시스템=프롬프트, 유저=입력 텍스트) → JSON
       - CODE-BASED GRADING: validate against the s07_analysis_v1 schema
       #     코드 기반 채점: s07_analysis_v1 스키마 유효성 검사
       - MODEL-BASED GRADING: call the judge with judge_prompt.md -> 4 Likert scores
       #     모델 기반 채점: judge_prompt.md로 판정 모델 호출 → 리커트 4개 점수
     Repeat N times (default 3) to measure run-to-run variance.
     #   실행 간 분산 측정을 위해 N번 반복 (기본값 3)
  3. Aggregate: per-case score = mean of 4 dims, averaged over trials; record variance.
  #  3. 집계: 케이스 점수 = 4개 차원 평균의 시험 평균; 분산 기록
  4. Write a run artifact under eval/runs/{ISO8601}_{git_sha}.json (per-trial raw scores).
  #  4. eval/runs/{ISO8601}_{git_sha}.json 에 실행 결과 파일 저장 (시험별 원시 점수)
  5. Optionally compare to a baseline run for CI pass/fail (--baseline).
  #  5. 선택적으로 기준 실행과 비교해 CI 통과/실패 판정 (--baseline 옵션)

This calls the Anthropic API directly (the backend service does not exist until W4),
# 백엔드 서비스는 W4까지 없으므로 Anthropic API를 직접 호출
which matches EVAL_PLAN.md: "runs S07 against each case using the prompt under
# EVAL_PLAN.md와 일치: "prompts/s07/v{current}.md 아래 프롬프트로
prompts/s07/v{current}.md".
# 각 케이스에 대해 S07 실행"

Usage:
# 사용법:
  python eval/s07-analysis/run_eval.py
  # .env 파일에 ANTHROPIC_API_KEY가 있으면 자동으로 로드됨
  # 기본 실행
  python eval/s07-analysis/run_eval.py --trials 3 --prompt-version v1
  # 시험 횟수와 프롬프트 버전을 지정해서 실행
  python eval/s07-analysis/run_eval.py --baseline eval/runs/<previous>.json   # CI mode
  # CI 모드: 이전 실행 결과 파일과 비교
"""

from __future__ import annotations  # 파이썬 3.9 이전 버전에서도 최신 타입 힌트 문법 사용 허용

import argparse   # 커맨드라인 인수(--trials 등)를 파싱하는 표준 라이브러리
import json       # JSON 데이터 읽기/쓰기 표준 라이브러리
import os         # 운영체제 기능(환경변수 조회 등) 표준 라이브러리
import re         # 정규표현식(패턴 매칭) 표준 라이브러리
import statistics  # 평균, 분산 등 통계 계산 표준 라이브러리
import subprocess  # 외부 프로세스(터미널 명령어)를 실행하는 표준 라이브러리
import sys         # 파이썬 인터프리터 제어 및 프로그램 강제 종료 표준 라이브러리
from datetime import datetime, timezone  # 날짜·시간 처리 + UTC 시간대 지원
from pathlib import Path               # 파일·폴더 경로를 객체로 다루는 표준 라이브러리

try:  # 아래 코드 실행을 시도하고 오류가 나면 except로 이동
    from anthropic import Anthropic  # Anthropic API 클라이언트 클래스 가져오기
except ImportError:  # anthropic 패키지가 설치되지 않았으면 실행
    sys.exit("Missing dependency. Run: pip install anthropic python-dotenv")  # 안내 메시지 출력 후 프로그램 종료

try:
    from dotenv import load_dotenv  # .env 파일을 환경변수로 로드하는 함수
except ImportError:
    sys.exit("Missing dependency. Run: pip install python-dotenv")

# --- Config ------------------------------------------------------------------  # 전역 설정값 섹션 구분선

REPO_ROOT = Path(__file__).resolve().parents[2]  # 이 파일의 절대 경로에서 2단계 상위 = 리포지토리 루트 폴더
EVAL_DIR = REPO_ROOT / "eval" / "s07-analysis"   # 평가 파일들(test_cases.json 등)이 있는 폴더 경로
RUNS_DIR = REPO_ROOT / "eval" / "runs"            # 실행 결과(artifact JSON)를 저장할 폴더 경로

GEN_MODEL = "claude-sonnet-4-6"   # S07 응답 생성에 사용할 AI 모델 (AI_PIPELINE.md 라우팅 기준)
JUDGE_MODEL = "claude-opus-5"  # 채점(판정)에 사용할 AI 모델. 생성 모델과 반드시 다른 모델 (EVAL_PLAN Judge prompt 절, 2026-09-05)

# Pricing per MTok (AI_PIPELINE.md, verified 2026-05-23). Used for run cost reporting.  # MTok당 단가 (2026-05-23 확인). 실행 비용 보고에 사용
PRICE = {  # USD per MTok, platform.claude.com/docs/en/about-claude/pricing 에서 2026-09-05 확인
    "claude-sonnet-4-6": {"in": 3.0, "out": 15.0},
    "claude-opus-5": {"in": 5.0, "out": 25.0},
}

DIMENSIONS = ["naturalness", "accuracy", "cultural_appropriateness", "tone_match"]  # 평가할 4개 품질 차원: 자연스러움, 정확도, 문화 적절성, 어조 일치
REQUIRED_VARIANT_FIELDS = [      # S07 출력 JSON에서 각 표현 변형이 반드시 가져야 할 필드 목록
    "english", "tone_label", "ipa",                                   # 영어 표현, 어조 레이블, 국제음성기호(IPA)
    "korean_pronunciation", "pronunciation_tip", "cultural_tip",      # 한국어 발음, 발음 팁, 문화 팁
]

# Pass / regression thresholds (EVAL_PLAN.md Tier 1)  # 통과/회귀 판정 임계값 (EVAL_PLAN.md 1단계 기준)
PASS_AGGREGATE_MIN = 4.0           # 전체 평균 점수가 이 값 이상이어야 "통과"
PASS_DIM_AVG_MIN = 3.5             # 각 차원 평균 점수가 이 값 이상이어야 "통과"
CATASTROPHIC_CASE_MIN = 2.0        # 이 값 미만 점수가 있으면 해당 케이스는 "심각한 실패"로 분류
REGRESSION_AGGREGATE_DROP = 0.3   # 기준 대비 전체 점수가 이만큼 이상 하락하면 회귀로 판정
REGRESSION_DIM_DROP = 0.5          # 기준 대비 특정 차원 점수가 이만큼 이상 하락하면 회귀로 판정


# --- Helpers -----------------------------------------------------------------  # 유틸리티 함수 섹션 구분선

def strip_front_matter(text: str) -> str:  # 문자열을 받아 YAML front-matter를 제거한 나머지 문자열을 반환하는 함수
    """Remove a leading YAML front-matter block (--- ... ---)."""  # 맨 앞의 YAML front-matter 블록(--- ... ---) 제거
    if text.startswith("---"):   # 텍스트가 "---"로 시작하면 (front-matter가 존재할 가능성)
        end = text.find("\n---", 3)  # 3번째 문자 이후에서 "\n---" 위치 탐색 (닫는 구분선 찾기)
        if end != -1:               # 닫는 구분선을 찾았으면
            return text[end + 4:].lstrip("\n")  # 구분선 다음 위치부터 반환 (앞쪽 빈 줄 제거)
    return text  # front-matter가 없으면 원문 그대로 반환


def extract_json(raw: str) -> dict:  # 모델이 출력한 문자열에서 JSON을 파싱해 딕셔너리로 반환하는 함수
    """Parse model output as JSON, tolerating ```json fences (course error handling)."""  # ```json 코드 펜스를 허용하면서 JSON 파싱
    cleaned = re.sub(r"```(?:json)?", "", raw).strip()  # ```json 또는 ``` 마커를 정규식으로 제거한 뒤 앞뒤 공백 제거
    return json.loads(cleaned)  # 정제된 문자열을 JSON으로 파싱해 딕셔너리 반환


def git_sha() -> str:  # 현재 git 커밋의 짧은 해시(SHA)를 문자열로 반환하는 함수
    try:  # 오류가 발생할 수 있는 코드 실행 시도
        return subprocess.check_output(         # 터미널 명령어를 실행하고 출력 결과를 반환
            ["git", "rev-parse", "--short", "HEAD"], cwd=REPO_ROOT, text=True  # 리포지토리 루트에서 짧은 HEAD 해시 조회
        ).strip()  # 반환된 문자열의 앞뒤 공백·줄바꿈 제거
    except Exception:  # git 명령어 실패 시 (git 미설치 또는 리포지토리 아닌 경우)
        return "nogit"  # 대체 문자열 반환


def cost_usd(model: str, usage) -> float:  # 모델 이름과 API 토큰 사용량을 받아 달러 비용을 계산하는 함수
    p = PRICE.get(model)  # PRICE 딕셔너리에서 해당 모델의 단가 정보 조회
    if not p:              # 단가 정보가 없는 모델이면
        return 0.0         # 0달러 반환 (알 수 없는 모델이므로 계산 불가)
    return (usage.input_tokens * p["in"] + usage.output_tokens * p["out"]) / 1_000_000  # 입력·출력 토큰 각각 단가를 곱해 합산 후 MTok 단위로 나눠 달러 계산


# --- Code-based grading (schema validation) ----------------------------------  # 코드 기반 채점(스키마 유효성 검사) 섹션 구분선

def validate_schema(output: dict) -> list[str]:  # 모델 출력 딕셔너리를 받아 스키마 위반 목록을 반환하는 함수
    """Return a list of schema violations. Empty list == valid s07_analysis_v1."""  # 스키마 위반 목록 반환. 빈 목록이면 s07_analysis_v1로 유효
    if not isinstance(output, dict):   # 모델이 객체가 아닌 JSON(배열·문자열·null)을 반환한 경우
        return [f"top-level output is {type(output).__name__}, expected an object"]  # 이 함수는 try 밖에서 호출되므로, 여기서 예외를 내면 실행 전체가 죽는다. 스키마 위반으로 기록하고 넘어간다
    errors: list[str] = []             # 오류 메시지를 담을 빈 리스트 초기화
    variants = output.get("expressions")  # 출력 딕셔너리에서 "expressions" 키의 값 가져오기
    if not isinstance(variants, list):    # expressions 값이 리스트가 아니면 (아예 없거나 타입 오류)
        return ["'expressions' missing or not a list"]  # 즉시 오류 메시지 하나를 담은 리스트 반환
    if len(variants) != 3:                              # 표현식 변형 개수가 정확히 3개가 아니면
        errors.append(f"expected exactly 3 variants, got {len(variants)}")  # 오류 메시지를 리스트에 추가
    for i, v in enumerate(variants):   # 각 변형을 인덱스(i)와 값(v)으로 함께 순회
        if not isinstance(v, dict):    # 변형이 딕셔너리(객체) 형태가 아니면
            errors.append(f"variant {i} is not an object")  # 오류 메시지 추가
            continue                   # 아래 필드 검사를 건너뛰고 다음 변형으로 이동
        for field in REQUIRED_VARIANT_FIELDS:  # 필수 필드 목록을 하나씩 순회
            if not v.get(field):               # 해당 필드가 없거나 빈 값이면
                errors.append(f"variant {i} missing/empty field '{field}'")  # 오류 메시지 추가
    return errors  # 수집된 오류 메시지 목록 반환 (없으면 빈 리스트 = 유효)


# --- Model calls -------------------------------------------------------------  # AI 모델 API 호출 섹션 구분선

def response_text(resp) -> str:  # API 응답에서 텍스트 블록만 골라 이어붙여 반환하는 함수
    """Join the text blocks of a response.

    Do not assume content[0] is text: models that return a reasoning block put a
    ThinkingBlock first, and ThinkingBlock has no .text (2026-09-06, judge=Opus 5).
    """
    # content는 블록 리스트다. 생각(thinking) 블록이 먼저 오는 모델이 있으므로 type으로 걸러낸다.
    parts = [b.text for b in resp.content if getattr(b, "type", None) == "text"]
    if not parts:  # 텍스트 블록이 하나도 없으면 파싱할 것이 없다
        raise ValueError(f"no text block in response (blocks: {[getattr(b, 'type', '?') for b in resp.content]})")
    return "".join(parts)  # 텍스트 블록이 여러 개면 순서대로 이어붙인다


def generate(client: Anthropic, system_prompt: str, input_text: str) -> tuple[str, float]:  # S07 모델을 호출해 응답 텍스트와 비용을 묶음으로 반환하는 함수
    resp = client.messages.create(  # Anthropic 메시지 API를 호출해 응답 객체 받기
        model=GEN_MODEL,            # 사용할 생성 모델 ID 지정
        max_tokens=2048,            # 응답 최대 토큰 수 제한 (이 이상 생성 안 함)
        system=system_prompt,       # 시스템 프롬프트 전달 (S07 동작 지침)
        messages=[{"role": "user", "content": input_text}],  # 유저 입력 메시지를 리스트 형태로 전달
    )
    return response_text(resp), cost_usd(GEN_MODEL, resp.usage)  # 첫 번째 응답 텍스트와 달러 비용을 튜플로 반환


def judge(client: Anthropic, judge_prompt: str, case: dict, output: dict) -> tuple[dict, float]:  # 판정 모델을 호출해 점수 딕셔너리와 비용을 묶음으로 반환하는 함수
    payload = {                                         # 판정 모델에 넘길 데이터 딕셔너리 구성 시작
        "input_text": case["input_text"],               # 원래 입력 텍스트 (무엇을 평가하는지)
        "tone_intent": case.get("tone_intent"),         # 의도한 어조 (없는 케이스는 None)
        "expected_behaviors": case["expected_behaviors"],       # 기대하는 올바른 동작 목록
        "expected_failure_modes": case["expected_failure_modes"],  # 예상되는 실패 패턴 목록
        "input_mode": case["input_mode"],               # say_it / check_it / fix_it / robustness (judge-v3부터 전달)
        "domain": case["domain"],                       # 생활 영역 코드
        "act": case["act"],                             # 화행 코드
        "draft_quality": case.get("draft_quality"),     # check_it 전용: 사용자 초안이 good/flawed 인지. 그 외 None
        "pair_of": case.get("pair_of"),                 # 암시 단서 쌍둥이면 원본 케이스 id, 아니면 None
        "output": output,                               # S07 모델이 실제 생성한 출력
    }
    resp = client.messages.create(         # Anthropic 메시지 API를 판정용으로 호출
        model=JUDGE_MODEL,                 # 사용할 판정 모델 ID 지정
        max_tokens=4000,                   # 판정 응답 최대 토큰 수 제한. Opus 5는 thinking 블록 토큰도 여기서 차감하므로 1000이면 JSON이 잘린다 (2026-09-06 확인: stop_reason=max_tokens)
        system=judge_prompt,               # 판정 기준이 담긴 시스템 프롬프트 전달
        messages=[{"role": "user", "content": json.dumps(payload, ensure_ascii=False)}],  # payload를 JSON 문자열로 변환해 유저 메시지로 전달 (한국어 보존)
    )
    return extract_json(response_text(resp)), cost_usd(JUDGE_MODEL, resp.usage)  # 판정 JSON 파싱 결과와 달러 비용을 튜플로 반환


# --- One trial ---------------------------------------------------------------  # 단일 시험 실행 섹션 구분선

def run_trial(client, system_prompt, judge_prompt, case) -> dict:  # 한 케이스에 대해 생성+채점 1회를 실행하고 결과 딕셔너리를 반환하는 함수
    """One generate + grade pass for one case. Returns scores + diagnostics."""  # 한 케이스의 생성+채점 1회 실행. 점수와 진단 정보 반환
    trial = {"schema_errors": [], "scores": None, "cost_usd": 0.0, "error": None}  # 시험 결과 딕셔너리 초기값 설정 (스키마 오류, 점수, 비용, 오류 메시지)
    try:  # 생성·파싱 오류가 발생할 수 있는 코드 실행 시도
        raw, gen_cost = generate(client, system_prompt, case["input_text"])  # S07 모델 호출 → 원시 응답 텍스트와 생성 비용 받기
        trial["cost_usd"] += gen_cost   # 생성 비용을 이 시험의 총비용에 누적
        output = extract_json(raw)      # 원시 응답 텍스트에서 JSON 파싱해 딕셔너리로 변환
    except Exception as e:              # 생성 API 호출 또는 JSON 파싱이 실패한 경우
        trial["error"] = f"generation/parse failed: {e}"  # 오류 내용을 문자열로 저장
        trial["scores"] = {d: 1 for d in DIMENSIONS}      # 모든 평가 차원 점수를 최저값 1로 설정
        return trial                    # 채점 단계를 건너뛰고 조기 반환

    schema_errors = validate_schema(output)   # 생성된 출력이 스키마를 준수하는지 검사
    trial["schema_errors"] = schema_errors    # 스키마 오류 목록을 시험 결과에 저장

    try:  # 채점(판정) 오류가 발생할 수 있는 코드 실행 시도
        verdict, judge_cost = judge(client, judge_prompt, case, output)  # 판정 모델 호출 → 점수 딕셔너리와 비용 받기
        trial["cost_usd"] += judge_cost                                   # 채점 비용을 총비용에 누적
        trial["scores"] = {d: float(verdict[d]) for d in DIMENSIONS}     # 각 차원 점수를 실수(float)로 변환해 저장
        trial["failure_modes_observed"] = verdict.get("failure_modes_observed", [])  # 판정 모델이 관찰한 실패 패턴 목록 저장
        trial["rationale"] = verdict.get("rationale", {})                 # 판정 근거 딕셔너리 저장
        trial["draft_handling"] = verdict.get("draft_handling", "n/a")    # check_it 초안 처리: kept / rewritten / n/a (judge-v3)
    except Exception as e:                  # 채점 API 호출 또는 파싱이 실패한 경우
        trial["error"] = f"judge failed: {e}"           # 오류 내용을 문자열로 저장
        trial["scores"] = {d: 1 for d in DIMENSIONS}   # 모든 평가 차원 점수를 최저값 1로 설정
    return trial  # 완성된 시험 결과 딕셔너리 반환


# --- Aggregation -------------------------------------------------------------  # 집계 함수 섹션 구분선

def aggregate_case(trials: list[dict]) -> dict:  # 여러 시험 결과 목록을 받아 차원별 평균·분산으로 집계하는 함수
    """Mean across trials per dimension + variance (ADR-009)."""  # 차원별 시험 평균 및 분산 계산 (ADR-009 기준)
    per_dim_mean, per_dim_var = {}, {}  # 차원별 평균과 분산을 담을 빈 딕셔너리 두 개 초기화
    for d in DIMENSIONS:  # 4개 평가 차원을 하나씩 순회
        vals = [t["scores"][d] for t in trials]  # 모든 시험에서 해당 차원의 점수만 추출해 리스트 생성
        per_dim_mean[d] = round(statistics.mean(vals), 3)  # 해당 차원 점수들의 평균 계산 (소수점 3자리 반올림)
        per_dim_var[d] = round(statistics.pvariance(vals), 3) if len(vals) > 1 else 0.0  # 시험이 2회 이상이면 모분산 계산, 1회면 분산 없으므로 0.0
    case_score = round(statistics.mean(per_dim_mean.values()), 3)  # 4개 차원 평균들의 평균 = 케이스 최종 종합 점수
    return {                              # 집계 결과 딕셔너리 생성해 반환
        "case_score": case_score,         # 케이스 종합 점수 (4차원 평균의 평균)
        "dim_mean": per_dim_mean,         # 차원별 평균 점수 딕셔너리
        "dim_variance": per_dim_var,      # 차원별 분산 딕셔너리 (클수록 시험 간 결과가 불안정)
        "min_dim_across_trials": min(     # 모든 시험의 모든 차원 중 가장 낮은 점수 (심각한 실패 탐지용)
            t["scores"][d] for t in trials for d in DIMENSIONS  # 시험×차원 모든 조합의 점수를 순회해 최솟값 구하기
        ),
    }


def summarize_input_modes(results: list[dict]) -> dict:
    """Per-input_mode dimension averages. Reported, not gated (exec-plan 2026-09-06 section C)."""
    # 입력 형태별 차원 평균. 게이트가 아니라 보고용. 평균에 묻히는 check_it 실패를 눈에 보이게 한다.
    out = {}
    for mode in sorted({r["input_mode"] for r in results}):
        rows = [r for r in results if r["input_mode"] == mode]
        out[mode] = {
            "n": len(rows),
            **{d: round(statistics.mean(r["aggregate"]["dim_mean"][d] for r in rows), 3) for d in DIMENSIONS},
        }
    return out


def summarize_drafts(results: list[dict]) -> dict:
    """check_it false-alarm / missed-flaw counts (Lin et al. 2026, Data Analysis: a correct segment
    flagged is a false alarm; left alone is an accurate non-intervention). Counted per trial."""
    # 좋은 초안을 고쳤으면 false_alarm, 나쁜 초안을 그대로 뒀으면 missed_flaw. 시험(trial) 단위로 센다.
    fa = mf = good_trials = flawed_trials = 0
    for r in results:
        q = r.get("draft_quality")
        if not q:
            continue
        for t in r["trials"]:
            h = t.get("draft_handling", "n/a")
            if q == "good":
                good_trials += 1
                fa += h == "rewritten"
            elif q == "flawed":
                flawed_trials += 1
                mf += h == "kept"
    return {"good_draft_trials": good_trials, "false_alarms": fa,
            "flawed_draft_trials": flawed_trials, "missed_flaws": mf}


def summarize_pairs(results: list[dict]) -> list[dict]:
    """tone_match delta between an implicit-cue twin (pair_of) and its explicit original.
    Nasim et al. 2026 section 3.2: same scenario, instruction present vs absent; the gap is the
    implicit-adaptation shortfall. Negative delta = the model needs to be told."""
    # 쌍둥이(말투 미지정) 대비 원본(말투 지정)의 tone_match 차이. 음수면 "시키지 않으면 못 한다".
    by_id = {r["id"]: r for r in results}
    rows = []
    for r in results:
        src = by_id.get(r.get("pair_of") or "")
        if not src:
            continue
        rows.append({
            "twin": r["id"], "original": src["id"],
            "tone_match_original": src["aggregate"]["dim_mean"]["tone_match"],
            "tone_match_twin": r["aggregate"]["dim_mean"]["tone_match"],
            "delta": round(r["aggregate"]["dim_mean"]["tone_match"] - src["aggregate"]["dim_mean"]["tone_match"], 3),
        })
    return rows


def evaluate_pass(cases: list[dict]) -> tuple[bool, list[str]]:  # 전체 케이스 결과를 받아 통과 여부(True/False)와 실패 이유 목록을 반환하는 함수
    reasons = []  # 실패 이유 메시지를 담을 빈 리스트 초기화
    aggregate = statistics.mean(c["aggregate"]["case_score"] for c in cases)  # 모든 케이스 종합 점수의 전체 평균 계산
    if aggregate < PASS_AGGREGATE_MIN:  # 전체 평균이 최소 통과 기준 미만이면
        reasons.append(f"aggregate {aggregate:.3f} < {PASS_AGGREGATE_MIN}")  # 실패 이유를 리스트에 추가
    for d in DIMENSIONS:  # 각 평가 차원을 순회
        dim_avg = statistics.mean(c["aggregate"]["dim_mean"][d] for c in cases)  # 해당 차원의 전체 케이스 평균 계산
        if dim_avg < PASS_DIM_AVG_MIN:  # 차원 평균이 최소 기준 미만이면
            reasons.append(f"dimension '{d}' avg {dim_avg:.3f} < {PASS_DIM_AVG_MIN}")  # 실패 이유 추가
    catastrophic = [c["id"] for c in cases              # 심각한 실패 케이스의 ID만 추출해 리스트 생성
                    if c["aggregate"]["min_dim_across_trials"] < CATASTROPHIC_CASE_MIN]  # 최솟값이 심각 임계값 미만인 케이스 필터
    if catastrophic:  # 심각한 실패 케이스가 하나라도 있으면
        reasons.append(f"catastrophic cases (<{CATASTROPHIC_CASE_MIN}): {catastrophic}")  # 해당 ID 목록과 함께 실패 이유 추가
    return (len(reasons) == 0), reasons  # 실패 이유가 없으면 True(통과), 이유 목록과 함께 튜플로 반환


def case_ids(artifact: dict) -> set[str]:  # 실행 결과(artifact)에 들어 있는 케이스 ID 집합을 반환하는 함수
    """Case ids present in a run artifact."""  # 실행 결과 파일에 포함된 케이스 ID 목록
    return {c["id"] for c in artifact.get("cases", [])}  # cases 배열의 각 항목에서 id만 뽑아 집합으로 반환


def check_regression(current: dict, baseline_path: Path) -> tuple[str, list[str]]:  # 현재 결과와 기준 파일을 비교해 판정 상태 문자열과 이유 목록을 반환하는 함수
    """CI gate vs a baseline run artifact (EVAL_PLAN regression criteria).

    Returns one of three states, not a pass/fail boolean:
    # 통과/실패 두 값이 아니라 세 가지 상태 중 하나를 반환한다:
      "ok"             - comparable baseline, no regression
      #                  비교 가능한 기준이고 회귀 없음
      "regressed"      - comparable baseline, scores dropped past a threshold
      #                  비교 가능한 기준인데 점수가 임계값 넘게 하락함
      "stale_baseline" - the two runs cover different case sets, so their
                         aggregate scores are means over different populations
                         and subtracting them says nothing about quality
      #                  두 실행의 케이스 집합이 다르다. 전체 점수는 서로 다른 모집단의
      #                  평균이므로 빼도 품질에 대해 아무것도 말해주지 않는다
    """
    base = json.loads(baseline_path.read_text(encoding="utf-8"))  # 기준 실행 결과 JSON 파일을 읽어 딕셔너리로 파싱

    current_ids, base_ids = case_ids(current), case_ids(base)  # 현재 실행과 기준 실행의 케이스 ID 집합을 각각 계산
    if current_ids != base_ids:  # 두 집합이 다르면 회귀 판정 자체가 성립하지 않으므로
        stale_reasons = [        # 재생성이 필요한 이유를 담을 리스트를 만들고 첫 줄에 규모 차이를 적음
            f"case sets differ: baseline has {len(base_ids)} cases, "  # 기준 실행의 케이스 수 표시
            f"current run has {len(current_ids)}"                       # 현재 실행의 케이스 수 표시
        ]
        added = sorted(current_ids - base_ids)    # 현재 실행에만 있는 케이스 ID를 정렬해 목록화
        removed = sorted(base_ids - current_ids)  # 기준 실행에만 있는 케이스 ID를 정렬해 목록화
        if added:    # 현재 실행에만 있는 케이스가 있으면
            stale_reasons.append(f"only in current run: {', '.join(added)}")  # 해당 ID 목록을 이유에 추가
        if removed:  # 기준 실행에만 있는 케이스가 있으면
            stale_reasons.append(f"only in baseline: {', '.join(removed)}")   # 해당 ID 목록을 이유에 추가
        stale_reasons.append(  # 마지막으로 사람이 취해야 할 조치를 안내
            "regenerate the baseline on the current case set and promote it "  # 현재 케이스 집합으로 기준을 다시 만들고
            "with 'git add -f' (see eval/runs/README.md)"                      # git add -f로 승격하라는 안내 (eval/runs/README.md 참고)
        )
        return "stale_baseline", stale_reasons  # 회귀 판정 대신 "기준 재생성 필요" 상태로 반환

    reasons = []  # 회귀 이유 메시지를 담을 빈 리스트 초기화
    if base["aggregate_score"] - current["aggregate_score"] > REGRESSION_AGGREGATE_DROP:  # 기준 전체 점수와 현재 점수의 차이가 허용 하락폭 초과하면
        reasons.append(              # 회귀 이유 문자열 생성 후 리스트에 추가
            f"aggregate dropped {base['aggregate_score']:.3f} -> "   # 기준 전체 점수 표시
            f"{current['aggregate_score']:.3f} (> {REGRESSION_AGGREGATE_DROP})"  # 현재 점수와 허용 한도 표시
        )
    for d in DIMENSIONS:  # 각 평가 차원을 순회
        if base["dim_averages"][d] - current["dim_averages"][d] > REGRESSION_DIM_DROP:  # 해당 차원 점수 하락이 허용 한도 초과하면
            reasons.append(          # 회귀 이유 문자열 생성 후 리스트에 추가
                f"dimension '{d}' dropped {base['dim_averages'][d]:.3f} -> "  # 기준 차원 점수 표시
                f"{current['dim_averages'][d]:.3f} (> {REGRESSION_DIM_DROP})"  # 현재 차원 점수와 허용 한도 표시
            )
    if current["catastrophic_count"] > base.get("catastrophic_count", 0):  # 현재 심각 실패 케이스 수가 기준보다 늘었으면
        reasons.append(              # 회귀 이유 문자열 생성 후 리스트에 추가
            f"catastrophic cases increased "                                      # 심각 실패 케이스 증가 표시
            f"{base.get('catastrophic_count', 0)} -> {current['catastrophic_count']}"  # 기준 수 → 현재 수 표시
        )
    return ("ok" if not reasons else "regressed"), reasons  # 회귀 이유가 없으면 "ok", 있으면 "regressed"를 이유 목록과 함께 반환


# --- Main --------------------------------------------------------------------  # 메인 실행 함수 섹션 구분선

def main() -> int:  # 프로그램 진입점 함수. 실행 성공 시 0, 실패 시 1을 정수로 반환
    ap = argparse.ArgumentParser()  # 커맨드라인 인수 파서 객체 생성
    ap.add_argument("--trials", type=int, default=3, help="trials per case (ADR-009, default 3)")  # --trials: 케이스당 반복 횟수 옵션 (정수, 기본값 3)
    ap.add_argument("--prompt-version", default="v1", help="S07 prompt version under prompts/s07/")  # --prompt-version: 사용할 프롬프트 파일 버전 옵션 (기본값 v1)
    ap.add_argument("--baseline", type=Path, default=None, help="baseline run artifact for CI gate")  # --baseline: CI 회귀 비교용 기준 실행 결과 파일 경로 옵션
    args = ap.parse_args()  # 실제 커맨드라인에서 입력된 인수들을 파싱해 args 객체에 저장

    load_dotenv(REPO_ROOT / ".env")  # 리포지토리 루트의 .env 파일을 환경변수로 로드 (파일 없으면 무시)

    if not os.environ.get("ANTHROPIC_API_KEY"):  # 환경변수 ANTHROPIC_API_KEY가 설정되지 않았으면
        sys.exit("ANTHROPIC_API_KEY가 없습니다. .env 파일에 설정하거나 환경변수로 직접 지정하세요.")  # 안내 메시지를 출력하고 프로그램 종료

    client = Anthropic()  # Anthropic API 클라이언트 인스턴스 생성 (API 키는 환경변수에서 자동으로 읽음)
    system_prompt = strip_front_matter(  # S07 시스템 프롬프트를 로드하고 front-matter를 제거
        (REPO_ROOT / "prompts" / "s07" / f"{args.prompt_version}.md").read_text(encoding="utf-8")  # 지정된 버전의 프롬프트 마크다운 파일을 UTF-8로 읽기
    )
    judge_prompt = strip_front_matter(   # 판정 프롬프트를 로드하고 front-matter를 제거
        (EVAL_DIR / "judge_prompt.md").read_text(encoding="utf-8")  # judge_prompt.md 파일을 UTF-8로 읽기
    )
    cases = json.loads((EVAL_DIR / "test_cases.json").read_text(encoding="utf-8"))["cases"]  # test_cases.json을 읽어 파싱한 뒤 "cases" 키의 값(케이스 목록) 가져오기

    print(f"Running {len(cases)} cases x {args.trials} trials "   # 실행 케이스 수 × 시험 횟수 정보 출력
          f"(prompt s07/{args.prompt_version}, judge {JUDGE_MODEL})\n")  # 프롬프트 버전과 판정 모델 이름 출력 후 빈 줄 삽입

    results, total_cost = [], 0.0  # 케이스별 결과를 담을 빈 리스트와 총 비용(달러) 0으로 초기화
    for case in cases:  # 테스트 케이스 목록을 하나씩 순회
        trials = [run_trial(client, system_prompt, judge_prompt, case)  # 한 케이스에 대해 시험 1회 실행
                  for _ in range(args.trials)]   # args.trials 횟수만큼 반복 (리스트 컴프리헨션)
        total_cost += sum(t["cost_usd"] for t in trials)  # 이 케이스의 모든 시험 비용 합산 후 총비용에 누적
        agg = aggregate_case(trials)              # 시험 결과들을 집계해 케이스 점수 계산
        results.append({"id": case["id"],                               # 케이스 ID와
                        "domain": case["domain"], "act": case["act"],   # 분류표 3축(EVAL_PLAN 'Scenario coverage taxonomy')을 포함해
                        "input_mode": case["input_mode"],
                        "draft_quality": case.get("draft_quality"),   # check_it 초안 품질 태그 (없으면 None)
                        "pair_of": case.get("pair_of"),               # 쌍둥이 원본 id (없으면 None)
                        "aggregate": agg, "trials": trials})  # 집계 결과와 시험별 원시 결과를 results에 추가
        flag = "  ⚠ high variance" if max(agg["dim_variance"].values()) > 0.5 else ""  # 최대 분산이 0.5 초과하면 경고 문자열 생성, 아니면 빈 문자열
        print(f"  {case['id']:<9} score={agg['case_score']:.2f}"   # 케이스 ID(왼쪽 정렬 9자)와 종합 점수 출력
              f"  min_dim={agg['min_dim_across_trials']}{flag}")    # 최솟값 차원 점수와 분산 경고 출력

    aggregate_score = round(statistics.mean(r["aggregate"]["case_score"] for r in results), 3)  # 모든 케이스 종합 점수의 전체 평균 계산 (소수점 3자리)
    dim_averages = {d: round(statistics.mean(r["aggregate"]["dim_mean"][d] for r in results), 3)  # 각 차원별 전체 평균을 딕셔너리로 생성
                    for d in DIMENSIONS}   # 4개 평가 차원 각각에 대해 계산 (딕셔너리 컴프리헨션)
    catastrophic_count = sum(   # 심각한 실패 케이스 수를 계산
        1 for r in results if r["aggregate"]["min_dim_across_trials"] < CATASTROPHIC_CASE_MIN  # 최솟값이 임계값 미만인 케이스마다 1을 더함
    )
    passed, pass_reasons = evaluate_pass(results)  # 통과 여부(True/False)와 실패 이유 목록 평가

    artifact = {   # 파일로 저장할 실행 결과(artifact) 딕셔너리 구성 시작
        "timestamp": datetime.now(timezone.utc).isoformat(),  # 현재 UTC 시각을 ISO 8601 형식 문자열로 저장
        "git_sha": git_sha(),               # 현재 git 커밋 짧은 해시 저장
        "prompt_version": args.prompt_version,  # 사용한 프롬프트 버전 문자열 저장
        "judge_model": JUDGE_MODEL,         # 사용한 판정 모델 ID 저장
        "gen_model": GEN_MODEL,             # 사용한 생성 모델 ID 저장
        "trials_per_case": args.trials,     # 케이스당 시험 횟수 저장
        "aggregate_score": aggregate_score, # 전체 종합 점수 저장
        "dim_averages": dim_averages,       # 차원별 평균 점수 딕셔너리 저장
        "catastrophic_count": catastrophic_count,  # 심각한 실패 케이스 수 저장
        "passed": passed,                   # 통과 여부 (True 또는 False) 저장
        "pass_reasons": pass_reasons,       # 실패 이유 목록 저장 (통과 시 빈 리스트)
        "estimated_cost_usd": round(total_cost, 4),  # 총 예상 비용(달러) 저장 (소수점 4자리 반올림)
        "by_input_mode": summarize_input_modes(results),  # 입력 형태별 차원 평균 (보고용, 게이트 아님)
        "draft_summary": summarize_drafts(results),       # check_it false_alarm / missed_flaw 집계 (보고용)
        "pair_deltas": summarize_pairs(results),          # 암시 단서 쌍둥이 vs 원본 tone_match 차이 (보고용)
        "cases": results,                   # 케이스별 상세 결과 목록 저장
    }

    RUNS_DIR.mkdir(parents=True, exist_ok=True)  # runs 디렉토리가 없으면 생성 (이미 있어도 오류 없음, 상위 폴더도 자동 생성)
    out_path = RUNS_DIR / f"{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}_{git_sha()}.json"  # 결과 파일 경로: YYYYMMDDTHHMMSSz_해시.json 형식
    out_path.write_text(json.dumps(artifact, ensure_ascii=False, indent=2), encoding="utf-8")  # artifact를 들여쓰기 2칸 JSON 문자열로 변환해 파일에 저장

    print(f"\naggregate={aggregate_score:.3f}  dims={dim_averages}")   # 빈 줄 후 전체 점수와 차원별 평균 출력
    print(f"drafts={artifact['draft_summary']}")                          # 좋은 초안 잘못 고침 / 나쁜 초안 방치 횟수
    print(f"pair_deltas={[(p['twin'], p['delta']) for p in artifact['pair_deltas']]}")  # 쌍둥이별 tone_match 차이
    print(f"cost=${total_cost:.4f}  artifact={out_path.relative_to(REPO_ROOT)}")  # 총 비용(달러)과 저장된 파일의 상대 경로 출력
    print(f"PASS={passed}" + ("" if passed else f"  reasons={pass_reasons}"))  # 통과 여부 출력, 실패하면 이유도 함께 출력

    exit_code = 0 if passed else 1  # 통과하면 종료 코드 0(성공), 실패하면 1(실패)
    if args.baseline:  # --baseline 인수가 제공된 경우 (CI 회귀 검사 모드)
        status, reg_reasons = check_regression(artifact, args.baseline)  # 기준 파일 대비 판정 상태와 이유 확인
        if status == "stale_baseline":  # 케이스 집합이 달라 회귀 판정이 성립하지 않는 경우
            print("REGRESSION_CHECK=SKIPPED  BASELINE_REGENERATION_REQUIRED")  # 회귀 검사를 건너뛰었고 기준 재생성이 필요함을 출력
            for reason in reg_reasons:      # 재생성이 필요한 이유를 한 줄씩 순회
                print(f"  - {reason}")      # 사람이 읽을 수 있도록 들여쓰기해 출력
            exit_code = 1  # 게이트가 조용히 통과하지 않도록 종료 코드를 1(실패)로 설정
        else:  # 케이스 집합이 같아 회귀 판정이 성립하는 경우
            ok = status == "ok"  # 상태가 "ok"이면 회귀 없음
            print(f"REGRESSION_OK={ok}" + ("" if ok else f"  reasons={reg_reasons}"))  # 회귀 검사 결과 출력, 회귀 발생 시 이유도 함께 출력
            if not ok:      # 회귀가 발생했으면 (기준보다 성능이 떨어졌으면)
                exit_code = 1  # 종료 코드를 1(실패)로 설정
    return exit_code  # 최종 종료 코드 반환 (0=성공, 1=실패)


if __name__ == "__main__":  # 이 파일이 직접 실행될 때만 아래 코드 실행 (다른 파일에서 import 시에는 실행 안 됨)
    raise SystemExit(main())  # main() 함수 실행 후 반환된 종료 코드(0 또는 1)로 프로그램 종료
