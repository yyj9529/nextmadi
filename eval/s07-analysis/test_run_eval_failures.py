"""Offline tests for failures that must never become a passing S07 run."""

import json
import io
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_eval


def output():
    return {"expressions": [
        {field: "text" for field in run_eval.REQUIRED_VARIANT_FIELDS}
        for _ in range(3)
    ]}


def response(text, stop_reason="end_turn"):
    return SimpleNamespace(
        content=[SimpleNamespace(type="text", text=text)],
        stop_reason=stop_reason,
        usage=SimpleNamespace(input_tokens=100, output_tokens=200),
    )


class FailureTests(unittest.TestCase):
    def test_schema_requires_strings(self):
        for value in (17, True, ["text"], {"text": "value"}, "   "):
            with self.subTest(value=value):
                malformed = output()
                malformed["expressions"][0]["english"] = value
                self.assertTrue(run_eval.validate_schema(malformed))

    def test_schema_error_blocks_even_high_scores(self):
        trial = {"scores": {d: 5 for d in run_eval.DIMENSIONS},
                 "schema_errors": ["invalid field"], "error": None}
        case = {"id": "test", "trials": [trial],
                "aggregate": run_eval.aggregate_case([trial])}
        self.assertFalse(run_eval.evaluate_pass([case])[0])

    def test_execution_error_blocks_even_high_scores(self):
        trial = {"scores": {d: 5 for d in run_eval.DIMENSIONS},
                 "schema_errors": [], "error": "judge failed"}
        case = {"id": "test", "trials": [trial],
                "aggregate": run_eval.aggregate_case([trial])}
        self.assertFalse(run_eval.evaluate_pass([case])[0])

    def test_empty_run_is_not_a_pass(self):
        self.assertFalse(run_eval.evaluate_pass([])[0])

    def test_parse_failure_does_not_store_response_body(self):
        raw = "PRIVATE RESPONSE: please give more details"
        with patch.object(run_eval, "generate", return_value=(raw, 0.1)):
            trial = run_eval.run_trial(None, "", "", {"input_text": "synthetic"})
        self.assertEqual(trial["score_source"], "failure_penalty")
        self.assertEqual(trial["error_stage"], "generation")
        self.assertEqual(trial["response_shape"], "non_json_prefix")
        self.assertNotIn(raw, json.dumps(trial))

    def test_judge_parse_failure_preserves_billed_cost(self):
        client = Mock()
        client.messages.create.side_effect = [
            response(json.dumps(output())), response('{"invalid":')]
        case = {"input_text": "synthetic", "expected_behaviors": [],
                "expected_failure_modes": [], "input_mode": "say_it",
                "domain": "workplace", "act": "request"}
        trial = run_eval.run_trial(client, "", "", case)
        expected = sum(run_eval.cost_usd(model, response("").usage)
                       for model in (run_eval.GEN_MODEL, run_eval.JUDGE_MODEL))
        self.assertAlmostEqual(trial["cost_usd"], expected)
        self.assertEqual(trial["error_stage"], "judge")
        self.assertEqual(trial["stop_reason"], "end_turn")

    def test_truncated_response_is_not_scored(self):
        client = Mock()
        client.messages.create.return_value = response("{}", "max_tokens")
        trial = run_eval.run_trial(client, "", "", {"input_text": "synthetic"})
        self.assertEqual(trial["stop_reason"], "max_tokens")
        self.assertEqual(trial["score_source"], "failure_penalty")
        self.assertEqual(client.messages.create.call_count, 1)

    def test_invalid_judge_scores_are_failures(self):
        for value in (float("nan"), float("inf"), 6, 0, True):
            with self.subTest(value=value), \
                 patch.object(run_eval, "generate", return_value=(json.dumps(output()), 0.1)), \
                 patch.object(run_eval, "judge", return_value=({d: value for d in run_eval.DIMENSIONS}, 0.2)):
                trial = run_eval.run_trial(None, "", "", {"input_text": "synthetic"})
                self.assertIsNotNone(trial["error"])


class CommandTests(unittest.TestCase):
    def run_main(self, args, trial=None):
        with tempfile.TemporaryDirectory(dir=run_eval.RUNS_DIR) as directory, \
             patch.object(run_eval, "RUNS_DIR", Path(directory)), \
             patch.object(run_eval, "Anthropic", Mock()), \
             patch.object(run_eval, "load_dotenv", Mock()), \
             patch.object(run_eval, "git_sha", return_value="offline_test"), \
             patch.dict(run_eval.os.environ, {"ANTHROPIC_API_KEY": "synthetic-test-key"}), \
             patch.object(sys, "argv", ["run_eval.py", *args]), \
             patch.object(run_eval, "run_trial", return_value=trial) as call, \
             redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            try:
                status = run_eval.main()
            except SystemExit as error:
                status = error.code
            artifacts = [json.loads(p.read_text(encoding="utf-8"))
                         for p in Path(directory).glob("*.json")]
            return status, artifacts, call.call_count

    def test_subset_is_marked_and_not_a_full_suite_pass(self):
        trial = {"scores": {d: 5 for d in run_eval.DIMENSIONS},
                 "cost_usd": 0.0, "schema_errors": [], "error": None}
        status, artifacts, calls = self.run_main(
            ["--prompt-version", "v2", "--case-ids", "s07_042"], trial)
        self.assertEqual(status, 0)
        self.assertEqual(calls, 3)
        self.assertEqual(artifacts[0]["scope"], "subset")
        self.assertFalse(artifacts[0]["full_suite_passed"])
        self.assertEqual([c["id"] for c in artifacts[0]["cases"]], ["s07_042"])
        self.assertEqual(len(artifacts[0]["provenance"]["prompt_sha256"]), 64)

    def test_invalid_selection_makes_no_paid_calls(self):
        for args in (["--trials", "0"], ["--case-ids", "unknown"]):
            with self.subTest(args=args):
                status, artifacts, calls = self.run_main(args)
                self.assertEqual((status, artifacts, calls), (2, [], 0))

    def test_mismatched_baseline_is_rejected_before_calls(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "baseline.json"
            path.write_text(json.dumps({"cases": [], "gen_model": run_eval.GEN_MODEL,
                                       "judge_model": run_eval.JUDGE_MODEL,
                                       "trials_per_case": 3}), encoding="utf-8")
            status, artifacts, calls = self.run_main(["--baseline", str(path)])
        self.assertEqual((status, artifacts, calls), (2, [], 0))


if __name__ == "__main__":
    unittest.main()
