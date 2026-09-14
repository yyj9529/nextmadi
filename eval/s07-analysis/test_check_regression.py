#!/usr/bin/env python3  # 이 파일을 python3 인터프리터로 실행하라는 유닉스 지시자 (shebang)
"""Unit tests for check_regression() case-set handling.
# check_regression()의 케이스 집합 처리에 대한 단위 테스트

No API calls: every artifact here is a fixture built in-process.
# API 호출 없음. 여기 나오는 실행 결과(artifact)는 전부 프로세스 안에서 만든 fixture다

Run from the repo root:
# 리포지토리 루트에서 실행:
  python -m unittest discover -s eval/s07-analysis -p 'test_*.py'
"""

import json      # fixture를 JSON 파일로 쓰기 위한 표준 라이브러리
import sys       # 모듈 검색 경로를 조작하기 위한 표준 라이브러리
import tempfile  # 기준 파일을 임시 디렉터리에 만들기 위한 표준 라이브러리
import unittest  # 표준 단위 테스트 프레임워크 (추가 의존성 없음)
from pathlib import Path  # 파일 경로를 객체로 다루는 표준 라이브러리

sys.path.insert(0, str(Path(__file__).resolve().parent))  # run_eval.py를 import할 수 있도록 이 파일의 폴더를 검색 경로 맨 앞에 추가

from run_eval import DIMENSIONS, check_regression  # 테스트 대상 함수와 평가 차원 목록 가져오기


def make_artifact(case_ids, aggregate=4.5, dim_score=4.5, catastrophic=0) -> dict:  # 테스트용 실행 결과 딕셔너리를 만드는 도우미 함수
    """Minimal run artifact with only the fields check_regression reads."""  # check_regression이 읽는 필드만 담은 최소 실행 결과
    return {  # 실제 실행 결과와 같은 모양이되 회귀 판정에 필요한 키만 채운 딕셔너리 반환
        "aggregate_score": aggregate,                     # 전체 종합 점수
        "dim_averages": {d: dim_score for d in DIMENSIONS},  # 4개 차원 평균 점수를 모두 같은 값으로 설정
        "catastrophic_count": catastrophic,               # 심각 실패 케이스 수
        "cases": [{"id": cid} for cid in case_ids],       # 케이스 ID만 담은 목록 (집합 비교에 이 부분만 쓰인다)
    }


class CheckRegressionCaseSet(unittest.TestCase):  # check_regression의 케이스 집합 관련 동작을 검증하는 테스트 묶음
    def setUp(self) -> None:  # 각 테스트 실행 직전에 호출되는 준비 함수
        self._tmp = tempfile.TemporaryDirectory()  # 기준 파일을 둘 임시 디렉터리 생성
        self.addCleanup(self._tmp.cleanup)         # 테스트가 끝나면 임시 디렉터리를 지우도록 등록
        self.tmp_dir = Path(self._tmp.name)        # 임시 디렉터리 경로를 Path 객체로 보관

    def write_baseline(self, artifact: dict) -> Path:  # 기준 실행 결과를 임시 JSON 파일로 저장하고 경로를 반환하는 도우미
        path = self.tmp_dir / "baseline.json"          # 임시 디렉터리 안의 baseline.json 경로 구성
        path.write_text(json.dumps(artifact), encoding="utf-8")  # 딕셔너리를 JSON 문자열로 저장
        return path                                     # 저장한 파일 경로 반환

    def test_same_case_set_no_drop_is_ok(self) -> None:  # 케이스 집합이 같고 점수 하락도 없으면 통과해야 한다
        baseline = self.write_baseline(make_artifact(["s07_001", "s07_002"]))  # 2케이스 기준 파일 작성
        current = make_artifact(["s07_001", "s07_002"])                        # 같은 2케이스의 현재 실행 결과
        status, reasons = check_regression(current, baseline)  # 회귀 검사 실행
        self.assertEqual(status, "ok")   # 상태가 "ok"여야 한다
        self.assertEqual(reasons, [])    # 이유 목록은 비어 있어야 한다

    def test_same_case_set_score_drop_is_regression(self) -> None:  # 케이스 집합이 같은데 점수가 크게 떨어지면 회귀로 판정해야 한다
        baseline = self.write_baseline(make_artifact(["s07_001", "s07_002"], aggregate=4.5))  # 전체 점수 4.5인 기준
        current = make_artifact(["s07_001", "s07_002"], aggregate=3.0, dim_score=3.0)         # 전체 점수 3.0으로 하락한 현재 실행
        status, reasons = check_regression(current, baseline)  # 회귀 검사 실행
        self.assertEqual(status, "regressed")  # 상태가 "regressed"여야 한다
        self.assertTrue(reasons)               # 하락 이유가 최소 한 건 있어야 한다

    def test_different_case_set_demands_baseline_regeneration(self) -> None:  # 케이스가 늘어난 경우 재생성을 요구해야 한다
        # 실제 상황 재현: 커밋된 기준은 12케이스, 현재 test_cases.json은 22케이스다
        baseline = self.write_baseline(make_artifact([f"s07_{i:03d}" for i in range(1, 13)]))   # 12케이스 기준 파일
        current = make_artifact([f"s07_{i:03d}" for i in range(1, 23)])                          # 22케이스 현재 실행
        status, reasons = check_regression(current, baseline)  # 회귀 검사 실행
        self.assertEqual(status, "stale_baseline")  # 회귀/통과가 아니라 "기준 재생성 필요" 상태여야 한다
        joined = " ".join(reasons)                  # 이유 문자열들을 하나로 합쳐 내용 검사
        self.assertIn("case sets differ", joined)   # 케이스 집합이 다르다는 사실이 이유에 드러나야 한다
        self.assertIn("s07_013", joined)            # 현재 실행에만 있는 케이스가 목록에 나와야 한다
        self.assertIn("regenerate the baseline", joined)  # 사람이 할 조치가 안내되어야 한다

    def test_different_case_set_is_not_reported_as_regression(self) -> None:  # 집합이 다르면 점수가 아무리 떨어져도 회귀라고 부르면 안 된다
        baseline = self.write_baseline(make_artifact(["s07_001", "s07_002"], aggregate=5.0, dim_score=5.0))  # 만점에 가까운 2케이스 기준
        current = make_artifact(["s07_001", "s07_003"], aggregate=1.0, dim_score=1.0)  # 케이스 하나가 다르고 점수는 크게 낮은 현재 실행
        status, _ = check_regression(current, baseline)  # 회귀 검사 실행
        self.assertEqual(status, "stale_baseline")       # 점수 하락이 아니라 집합 불일치가 먼저 보고되어야 한다

    def test_case_count_equal_but_ids_differ_is_stale(self) -> None:  # 케이스 수가 같아도 ID가 다르면 비교 불가로 봐야 한다
        baseline = self.write_baseline(make_artifact(["s07_001", "s07_002"]))  # ID가 001, 002인 기준
        current = make_artifact(["s07_001", "s07_009"])                        # 수는 같지만 002 대신 009가 들어간 현재 실행
        status, reasons = check_regression(current, baseline)  # 회귀 검사 실행
        self.assertEqual(status, "stale_baseline")  # 개수가 아니라 집합으로 판단해야 한다
        joined = " ".join(reasons)                  # 이유 문자열들을 하나로 합쳐 내용 검사
        self.assertIn("s07_009", joined)            # 현재 실행에만 있는 케이스가 드러나야 한다
        self.assertIn("s07_002", joined)            # 기준에만 있는 케이스도 드러나야 한다


if __name__ == "__main__":  # 이 파일을 직접 실행할 때만 아래 코드 실행
    unittest.main()  # 단위 테스트 실행
