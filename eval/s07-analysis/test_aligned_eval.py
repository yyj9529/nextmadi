import copy
import json
import unittest
from types import SimpleNamespace
from unittest.mock import Mock

import aligned_eval as runner


class AlignedEvalTests(unittest.TestCase):
    def setUp(self):
        self.fixture = runner.read_json(runner.HERE / "judge_fixtures.json")[0]
        self.output = copy.deepcopy(self.fixture["output"])
        self.judgment = {"checks": {k: {"status": "pass", "evidence": "Visible answer matches the request."}
                                    for k in runner.CHECKS},
                         "scores": {d: 4 for d in runner.DIMENSIONS}}

    def test_all_fixtures_match_product_schema(self):
        for fixture in runner.read_json(runner.HERE / "judge_fixtures.json"):
            self.assertEqual(runner.validate_output(fixture["output"]), [], fixture["id"])

    def test_clarification_cannot_carry_expression_cards(self):
        self.assertTrue(runner.validate_output({"result_type": "needs_context", "question": "무슨 말인가요?", "expressions": self.output["expressions"]}))

    def test_word_and_legacy_schema_are_distinct(self):
        self.assertFalse(runner.validate_output({"result_type": "word", "word": {"english": "landlord", "meaning_ko": "집주인"}}))
        self.assertTrue(runner.validate_output({"expressions": self.output["expressions"]}))

    def test_wrong_or_whitespace_fields_fail_closed(self):
        self.output["assessment"]["summary"] = "   "
        self.assertTrue(runner.validate_output(self.output))
        self.assertTrue(runner.validate_output(None))
        self.assertTrue(runner.validate_output([]))

    def test_unknown_schema_keywords_are_not_silently_ignored(self):
        with self.assertRaises(ValueError):
            runner.schema_errors({}, {"new_constraint": True})

    def test_judge_requires_all_checks_and_evidence(self):
        self.assertTrue(runner.validate_judgment(self.judgment, self.output))
        self.judgment["checks"]["answers_question"]["evidence"] = ""
        self.assertFalse(runner.validate_judgment(self.judgment, self.output))

    def test_uncertain_is_never_passed(self):
        self.judgment["checks"]["no_invented_facts"]["status"] = "uncertain"
        self.assertFalse(runner.behavior_pass(self.judgment))

    def test_high_quality_does_not_override_behavior_failure(self):
        result = runner.summarize([{"status": "behavior_failed", "scores": {d: 5 for d in runner.DIMENSIONS}}], 1, True)
        self.assertFalse(result["passed"])
        self.assertFalse(result["full_suite_passed"])

    def test_failures_do_not_become_quality_scores(self):
        summary = runner.summarize([{"status": "execution_error", "scores": None}], 1)
        self.assertIsNone(summary["dimension_averages"])
        self.assertEqual(summary["scored_trials"], 0)
        self.assertFalse(summary["passed"])

    def test_missing_trials_block_pass_and_subset_never_passes_full(self):
        trial = {"status": "passed", "scores": self.judgment["scores"]}
        self.assertFalse(runner.summarize([trial], 2, True)["passed"])
        self.assertFalse(runner.summarize([trial], 1)["full_suite_passed"])

    def test_original_quality_thresholds_remain(self):
        for scores in ({d: 3.9 for d in runner.DIMENSIONS},
                       dict(zip(runner.DIMENSIONS, [3.4, 5, 5, 5])),
                       dict(zip(runner.DIMENSIONS, [1, 5, 5, 5]))):
            self.assertFalse(runner.summarize([{"status": "passed", "scores": scores}], 1)["passed"])

    def test_bad_score_types_fail_validation(self):
        for bad in [True, None, float("nan"), 0, 6]:
            self.judgment["scores"]["accuracy"] = bad
            self.assertFalse(runner.validate_judgment(self.judgment, self.output))

    def test_clarification_scores_are_not_forced_to_english_dimensions(self):
        output = {"result_type": "needs_context", "question": "어떤 말을 하고 싶으세요?"}
        self.assertFalse(runner.validate_judgment(self.judgment, output))
        self.judgment["scores"] = None
        self.assertTrue(runner.validate_judgment(self.judgment, output))

    def test_fixture_labels_and_hidden_tone_are_not_sent_to_judge(self):
        payload = runner.judge_payload(self.fixture["case"], self.output)
        for key in ["expected_pass", "legacy_draft_quality", "tone_intent"]:
            self.assertNotIn(key, payload)

    def test_budget_stops_before_sending_next_request(self):
        client = Mock()
        budget = runner.Budget(0.00001, 1, {"model": [3, 15]})
        with self.assertRaises(runner.BudgetStop):
            budget.call(client, "model", [{"role": "user", "content": "test"}], 4096, "generation")
        client.messages.create.assert_not_called()
        self.assertEqual(budget.calls, 0)

    def test_transport_failure_keeps_unknown_cost_reservation(self):
        client = Mock()
        client.messages.create.side_effect = RuntimeError("private provider error")
        budget = runner.Budget(1, 1, {"model": [3, 15]})
        with self.assertRaises(RuntimeError):
            budget.call(client, "model", [], 4096, "generation")
        self.assertGreater(budget.spent, 0)
        self.assertIsNone(budget.attempts[0]["cost_usd"])
        with self.assertRaises(runner.BudgetStop):
            budget.call(client, "model", [], 4096, "generation")

    def test_judge_is_not_called_for_invalid_final_schema(self):
        budget = Mock()
        budget.call.return_value = {"wrong": "shape"}
        trial = runner.run_trial(Mock(), budget, self.fixture["case"], "prompt", "judge", "gen", "judge-model")
        self.assertEqual(trial["status"], "schema_error")
        self.assertIsNone(trial["scores"])
        self.assertEqual(budget.call.call_count, 2)

    def test_success_uses_product_message_shape_and_token_cap(self):
        budget = Mock()
        budget.call.side_effect = [self.output, self.judgment]
        trial = runner.run_trial(Mock(), budget, self.fixture["case"], "prompt", "judge", "gen", "judge-model")
        self.assertEqual(trial["status"], "passed")
        call = budget.call.call_args_list[0].args
        self.assertEqual(call[3], 4096)
        self.assertEqual(call[2][0]["role"], "user")
        self.assertIn('"input_mode":"expressions"', call[2][0]["content"])

    def test_case_ids_preserved_and_versions_separated(self):
        result = runner.cases()
        self.assertEqual(len(result), 90)
        self.assertEqual(len({c["id"] for c in result}), 90)
        landlord = next(c for c in result if c["id"] == "s07_054")
        self.assertEqual(landlord["expected_action"], "needs_context")

    def test_calibration_does_not_accept_uncertain_as_correct_rejection(self):
        self.judgment["checks"]["preserves_intent"]["status"] = "uncertain"
        trial = {"status": "behavior_failed", "judgment": self.judgment}
        fixture = {"id": "bad", "expected_pass": False}
        self.assertEqual(runner.calibration_trial(trial, fixture)["status"], "behavior_failed")

    def test_calibration_detects_judge_that_passes_a_wrong_route(self):
        trial = {"status": "behavior_failed", "judgment": self.judgment}
        fixture = {"id": "bad", "expected_pass": False}
        self.assertEqual(runner.calibration_trial(trial, fixture)["status"], "behavior_failed")

    def test_paid_generation_output_survives_judge_failure(self):
        budget = Mock()
        budget.call.side_effect = [self.output, ValueError("private response")]
        trial = {}
        with self.assertRaises(ValueError):
            runner.run_trial(Mock(), budget, self.fixture["case"], "p", "j", "g", "j", trial=trial)
        self.assertEqual(trial["output"], self.output)
        self.assertIsNone(trial["scores"])

    def test_catastrophic_quality_is_a_stop_status(self):
        self.judgment["scores"]["accuracy"] = 1
        budget = Mock()
        budget.call.side_effect = [self.output, self.judgment]
        trial = runner.run_trial(Mock(), budget, self.fixture["case"], "p", "j", "g", "j")
        self.assertEqual(trial["status"], "quality_failed")

    def test_calibration_preserves_operational_errors(self):
        for status in ("schema_error", "judge_error", "execution_error", "budget_stopped"):
            trial = {"status": status, "scores": None}
            self.assertEqual(runner.calibration_trial(trial, {"id": "bad", "expected_pass": False})["status"], status)


if __name__ == "__main__":
    unittest.main()
