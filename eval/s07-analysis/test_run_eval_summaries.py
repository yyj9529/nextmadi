"""Deterministic tests for the reporting summaries added to run_eval.py on 2026-09-06
(exec-plan 2026-09-06-eval-case-criteria-revision, sections C and E). No API calls:
the functions take already-aggregated results and only regroup them."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_eval import summarize_drafts, summarize_input_modes, summarize_pairs  # noqa: E402

DIMS = ["naturalness", "accuracy", "cultural_appropriateness", "tone_match"]


def result(cid, mode, tone_match=4.0, draft_quality=None, pair_of=None, handlings=("n/a",)):
    dim_mean = {d: 4.0 for d in DIMS}
    dim_mean["tone_match"] = tone_match
    return {
        "id": cid, "input_mode": mode, "domain": "workplace", "act": "assert",
        "draft_quality": draft_quality, "pair_of": pair_of,
        "aggregate": {"case_score": 4.0, "dim_mean": dim_mean, "dim_variance": {}, "min_dim_across_trials": 4},
        "trials": [{"scores": dim_mean, "draft_handling": h} for h in handlings],
    }


class TestDraftSummary(unittest.TestCase):
    def test_counts_false_alarms_and_missed_flaws_per_trial(self):
        results = [
            result("g1", "check_it", draft_quality="good", handlings=("kept", "rewritten", "kept")),
            result("f1", "check_it", draft_quality="flawed", handlings=("rewritten", "kept", "kept")),
            result("s1", "say_it"),  # ignored: no draft_quality
        ]
        self.assertEqual(summarize_drafts(results), {
            "good_draft_trials": 3, "false_alarms": 1,
            "flawed_draft_trials": 3, "missed_flaws": 2,
        })

    def test_missing_handling_is_not_counted_as_failure(self):
        # A judge that never emitted draft_handling (judge-v2 artifacts) yields zeros, not noise.
        results = [result("g1", "check_it", draft_quality="good", handlings=("n/a",))]
        self.assertEqual(summarize_drafts(results)["false_alarms"], 0)


class TestPairSummary(unittest.TestCase):
    def test_delta_is_twin_minus_original(self):
        results = [
            result("s07_041", "say_it", tone_match=4.5),
            result("s07_079", "say_it", tone_match=2.5, pair_of="s07_041"),
        ]
        rows = summarize_pairs(results)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["twin"], "s07_079")
        self.assertEqual(rows[0]["original"], "s07_041")
        self.assertEqual(rows[0]["delta"], -2.0)

    def test_twin_without_original_in_run_is_skipped(self):
        results = [result("s07_079", "say_it", pair_of="s07_041")]
        self.assertEqual(summarize_pairs(results), [])


class TestInputModeSummary(unittest.TestCase):
    def test_groups_by_mode_with_counts(self):
        results = [
            result("a", "say_it", tone_match=5.0),
            result("b", "say_it", tone_match=3.0),
            result("c", "check_it", tone_match=2.0),
        ]
        out = summarize_input_modes(results)
        self.assertEqual(out["say_it"]["n"], 2)
        self.assertEqual(out["say_it"]["tone_match"], 4.0)
        self.assertEqual(out["check_it"]["n"], 1)
        self.assertEqual(out["check_it"]["tone_match"], 2.0)


if __name__ == "__main__":
    unittest.main()
