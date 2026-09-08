"""Deterministic checks on test_cases.json (no API calls).

Enforces the scenario coverage taxonomy in docs/EVAL_PLAN.md ("Scenario coverage
taxonomy"): every case uses only the listed domain / act / input_mode codes, ids are
unique, and the coverage floors hold. Runs in CI before any model-based grading so a
malformed or lopsided case set fails fast and for free.
"""

import json
import unittest
from collections import Counter
from pathlib import Path

CASES_PATH = Path(__file__).resolve().parent / "test_cases.json"

DOMAINS = {
    "housing", "finance", "government", "healthcare", "school", "workplace",
    "job_search", "shopping", "dining", "neighbors", "driving_emergency",
    "remote_support", "community", "friendship",
}
ACTS = {
    "request", "refuse", "complain", "apologize", "small_talk", "assert", "clarify",
    "bad_news", "negotiate", "compliment", "emotion", "written", "social_norm",
}
INPUT_MODES = {"say_it", "check_it", "fix_it", "robustness"}
REQUIRED = ["id", "input_text", "tone_intent", "domain", "act", "input_mode",
            "expected_behaviors", "expected_failure_modes"]

MIN_PER_DOMAIN = 2
MIN_PER_ACT = 2


def load_cases():
    return json.loads(CASES_PATH.read_text(encoding="utf-8"))["cases"]


class TestCaseSchema(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases = load_cases()

    def test_required_fields_present(self):
        for c in self.cases:
            for key in REQUIRED:
                self.assertIn(key, c, f"{c.get('id')} missing {key}")
            self.assertTrue(c["input_text"].strip(), f"{c['id']} empty input_text")
            self.assertTrue(c["expected_behaviors"], f"{c['id']} no expected_behaviors")
            self.assertTrue(c["expected_failure_modes"], f"{c['id']} no expected_failure_modes")

    def test_ids_unique(self):
        ids = [c["id"] for c in self.cases]
        dupes = [i for i, n in Counter(ids).items() if n > 1]
        self.assertEqual(dupes, [], f"duplicate ids: {dupes}")

    def test_codes_are_from_taxonomy(self):
        for c in self.cases:
            self.assertIn(c["domain"], DOMAINS, f"{c['id']} domain={c['domain']}")
            self.assertIn(c["act"], ACTS, f"{c['id']} act={c['act']}")
            self.assertIn(c["input_mode"], INPUT_MODES, f"{c['id']} input_mode={c['input_mode']}")

    def test_input_text_within_db_cap(self):
        # data-model.md caps expression input at 500 characters.
        for c in self.cases:
            self.assertLessEqual(len(c["input_text"]), 500, f"{c['id']} input_text over 500 chars")

    def test_domain_coverage_floor(self):
        counts = Counter(c["domain"] for c in self.cases)
        thin = {d: counts[d] for d in DOMAINS if counts[d] < MIN_PER_DOMAIN}
        self.assertEqual(thin, {}, f"domains under {MIN_PER_DOMAIN} cases: {thin}")

    def test_act_coverage_floor(self):
        counts = Counter(c["act"] for c in self.cases)
        thin = {a: counts[a] for a in ACTS if counts[a] < MIN_PER_ACT}
        self.assertEqual(thin, {}, f"acts under {MIN_PER_ACT} cases: {thin}")

    def test_every_input_mode_present(self):
        modes = {c["input_mode"] for c in self.cases}
        self.assertEqual(modes, INPUT_MODES, f"missing input modes: {INPUT_MODES - modes}")


if __name__ == "__main__":
    unittest.main()
