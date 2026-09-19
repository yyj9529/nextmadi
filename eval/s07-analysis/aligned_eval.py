"""Versioned, budget-limited S07 evaluator. Default is an offline preview, never a paid run."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
from datetime import datetime, timezone

import run_eval as legacy

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
CONTRACT_PATH = HERE / "behavior_contract.json"
CHECKS = ("answers_question", "no_invented_facts", "preserves_intent", "correct_route",
          "minimal_clarification", "appropriate_assessment")
DIMENSIONS = legacy.DIMENSIONS


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def cases():
    contract = read_json(CONTRACT_PATH)
    result = []
    for old in read_json(HERE / "test_cases.json")["cases"]:
        case = {"id": old["id"], "input_text": old["input_text"], "input_mode": "expressions",
                "expected_action": "expressions", "requirements": [],
                "legacy_input_mode": old["input_mode"], "legacy_draft_quality": old.get("draft_quality")}
        case.update(contract["overrides"].get(case["id"], {}))
        result.append(case)
    for case in contract["additional_cases"]:
        result.append({"input_mode": "expressions", "requirements": [], **case})
    return result


def schema_errors(value, schema, path="$"):
    """Validate the exact subset of draft-07 used by the canonical S07 schema; fail closed."""
    supported = {"$schema", "title", "oneOf", "type", "additionalProperties", "required",
                 "properties", "const", "enum", "minLength", "pattern", "minItems", "maxItems", "items"}
    if set(schema) - supported:
        raise ValueError("Unsupported canonical schema keyword")
    if "oneOf" in schema:
        return [] if sum(not schema_errors(value, s, path) for s in schema["oneOf"]) == 1 else [path + ": branch mismatch"]
    errors = []
    if "const" in schema and value != schema["const"]:
        errors.append(path + ": const")
    if "enum" in schema and value not in schema["enum"]:
        errors.append(path + ": enum")
    kind = schema.get("type")
    if kind == "object":
        if not isinstance(value, dict):
            return [path + ": object required"]
        properties = schema.get("properties", {})
        if set(schema.get("required", [])) - value.keys():
            errors.append(path + ": missing fields")
        if schema.get("additionalProperties") is False and value.keys() - properties.keys():
            errors.append(path + ": extra fields")
        for key, item in value.items():
            if key in properties:
                errors.extend(schema_errors(item, properties[key], path + "." + key))
    elif kind == "array":
        if not isinstance(value, list):
            return [path + ": array required"]
        if not schema.get("minItems", 0) <= len(value) <= schema.get("maxItems", math.inf):
            errors.append(path + ": item count")
        for index, item in enumerate(value):
            errors.extend(schema_errors(item, schema["items"], f"{path}[{index}]"))
    elif kind == "string":
        if not isinstance(value, str):
            return [path + ": string required"]
        if len(value) < schema.get("minLength", 0):
            errors.append(path + ": short string")
        if "pattern" in schema:
            import re
            if not re.search(schema["pattern"], value):
                errors.append(path + ": pattern")
    return errors


def validate_output(output):
    return schema_errors(output, read_json(ROOT / "backend/src/main/resources/schemas/s07_analysis_v2.json"))


def validate_judgment(judgment, output):
    if not isinstance(judgment, dict) or set(judgment) != {"checks", "scores"}:
        return False
    checks = judgment["checks"]
    if not isinstance(checks, dict) or set(checks) != set(CHECKS):
        return False
    for check in checks.values():
        if not isinstance(check, dict) or set(check) != {"status", "evidence"}:
            return False
        if check["status"] not in ("pass", "fail", "uncertain") or not isinstance(check["evidence"], str) or not check["evidence"].strip():
            return False
    scores = judgment["scores"]
    if output["result_type"] != "expressions":
        return scores is None
    return (isinstance(scores, dict) and set(scores) == set(DIMENSIONS)
            and all(type(v) in (int, float) and math.isfinite(v) and 1 <= v <= 5 for v in scores.values()))


def behavior_pass(judgment):
    return all(judgment["checks"][key]["status"] == "pass" for key in CHECKS)


class BudgetStop(RuntimeError):
    pass


class Budget:
    def __init__(self, dollars, calls, prices):
        if not math.isfinite(dollars) or dollars <= 0 or calls < 1:
            raise ValueError("Positive finite budget and call limit required")
        self.limit, self.call_limit, self.prices = dollars, calls, prices
        self.spent, self.calls, self.attempts = 0.0, 0, []

    def call(self, client, model, messages, max_tokens, stage):
        price = self.prices[model]
        # Byte count deliberately overestimates text tokens, plus protocol overhead.
        bound = ((len(json.dumps(messages, ensure_ascii=False).encode("utf-8")) + 1024) * price[0]
                 + max_tokens * price[1]) / 1_000_000
        if self.calls >= self.call_limit or self.spent + bound > self.limit:
            raise BudgetStop("next-call bound exceeds approval")
        self.calls += 1
        self.spent += bound  # Keep reserved maximum if transport/usage is unknown.
        attempt = {"stage": stage, "model": model, "reserved_usd": bound, "cost_usd": None, "status": "execution_error"}
        self.attempts.append(attempt)
        response = client.messages.create(model=model, max_tokens=max_tokens, messages=messages)
        usage = response.usage
        actual = (usage.input_tokens * price[0] + usage.output_tokens * price[1]) / 1_000_000
        self.spent += actual - bound
        attempt.update(cost_usd=actual, status="received", stop_reason=response.stop_reason)
        if response.stop_reason != "end_turn":
            raise ValueError("incomplete response")
        return json.loads(legacy.response_text(response))


def judge_payload(case, output):
    # Never send fixture expected labels, legacy tone preferences, or legacy draft labels.
    return {key: case[key] for key in ("input_text", "input_mode", "expected_action", "requirements")} | {"output": output}


def run_trial(client, budget, case, prompt, judge_prompt, gen_model, judge_model, output=None, trial=None):
    if trial is None:
        trial = {}
    trial.update(case_id=case["id"], status="execution_error", scores=None)
    if output is None:
        user = json.dumps({"input_text": case["input_text"], "input_mode": case["input_mode"]}, ensure_ascii=False, separators=(",", ":"))
        messages = [{"role": "user", "content": prompt + "\n\n" + user}]
        output = budget.call(client, gen_model, messages, 4096, "generation")
        trial["output"] = output
        if validate_output(output):
            trial["first_attempt_schema_error"] = True
            # Match the product's one schema retry; no quality retries.
            messages.append({"role": "user", "content": "이전 응답이 요구된 JSON 스키마(s07_analysis_v2)를 만족하지 않았습니다. 설명이나 코드펜스 없이, 스키마 제약을 지킨 유효한 JSON만 다시 출력하세요."})
            output = budget.call(client, gen_model, messages, 4096, "schema_retry")
    trial["output"] = output  # Only fixed synthetic cases, never production requests.
    errors = validate_output(output)
    if errors:
        return trial | {"status": "schema_error", "schema_errors": errors}
    payload = json.dumps(judge_payload(case, output), ensure_ascii=False)
    judgment = budget.call(client, judge_model, [{"role": "user", "content": judge_prompt + "\n\n" + payload}], 4000, "judge")
    if not validate_judgment(judgment, output):
        return trial | {"status": "judge_error"}
    status = "passed" if behavior_pass(judgment) and output["result_type"] == case["expected_action"] else "behavior_failed"
    if status == "passed" and judgment["scores"] and min(judgment["scores"].values()) < 2:
        status = "quality_failed"
    return trial | {"status": status, "judgment": judgment, "scores": judgment["scores"]}


def calibration_trial(trial, fixture):
    if trial["status"] in ("schema_error", "execution_error", "judge_error", "budget_stopped"):
        return trial | {"fixture_id": fixture["id"], "expected_pass": fixture["expected_pass"]}
    judgment = trial.get("judgment")
    # A deterministic route check must not hide a judge that approved the wrong answer.
    decided = judgment is not None and all(c["status"] != "uncertain" for c in judgment["checks"].values())
    matched = decided and behavior_pass(judgment) == fixture["expected_pass"]
    return trial | {"status": "passed" if matched else "behavior_failed",
                    "fixture_id": fixture["id"], "expected_pass": fixture["expected_pass"], "scores": None}


def summarize(trials, expected_count, full=False):
    counts = {status: sum(t["status"] == status for t in trials) for status in
              ("passed", "behavior_failed", "quality_failed", "schema_error", "execution_error", "judge_error", "budget_stopped", "unjudged")}
    scored = [t["scores"] for t in trials if t.get("scores") is not None]
    averages = {d: sum(s[d] for s in scored) / len(scored) for d in DIMENSIONS} if scored else None
    quality = (not scored or (sum(averages.values()) / 4 >= 4.0
               and min(averages.values()) >= 3.5 and all(min(s.values()) >= 2 for s in scored)))
    passed = len(trials) == expected_count and expected_count > 0 and counts["passed"] == expected_count and quality
    return {"counts": counts, "expected_trials": expected_count, "completed_trials": len(trials),
            "scored_trials": len(scored), "dimension_averages": averages, "passed": passed,
            "scope": "full" if full else "subset", "full_suite_passed": full and passed}


def fingerprints():
    paths = [CONTRACT_PATH, HERE / "test_cases.json", HERE / "judge_v4.md", HERE / "aligned_eval.py",
             HERE / "judge_fixtures.json", HERE / "run_eval.py", ROOT / "prompts/s07/v3.md",
             ROOT / "backend/src/main/resources/s07_input_policy.json",
             ROOT / "backend/src/main/resources/schemas/s07_analysis_v2.json"]
    return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", choices=["generate", "calibrate", "repeat", "full"], default="generate")
    parser.add_argument("--execute", action="store_true", help="Only after explicit user approval")
    parser.add_argument("--budget-usd", type=float)
    parser.add_argument("--max-calls", type=int)
    parser.add_argument("--prices", type=float, nargs=4, metavar=("GEN_IN", "GEN_OUT", "JUDGE_IN", "JUDGE_OUT"))
    parser.add_argument("--prior", type=Path, help="Successful generation artifact for repeat, repeat artifact for full")
    parser.add_argument("--calibration", type=Path, help="Successful matching calibration artifact")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    contract, all_cases = read_json(CONTRACT_PATH), cases()
    selected = all_cases if args.stage == "full" else [c for c in all_cases if c["id"] in contract["subset"]]
    fixtures = read_json(HERE / "judge_fixtures.json")
    print(json.dumps({"stage": args.stage, "case_ids": [c["id"] for c in selected],
                      "fixtures": len(fixtures), "trials": 3 if args.stage in ("repeat", "full") else 1,
                      "executing": args.execute}, ensure_ascii=False))
    if not args.execute:
        return 0
    if not args.output or not args.prices or not args.budget_usd or not args.max_calls:
        parser.error("Execution requires output, current verified prices, budget and max calls")
    if args.output.exists():
        parser.error("Refusing to overwrite an existing artifact")
    if any(not math.isfinite(p) or p <= 0 for p in args.prices):
        parser.error("Prices must be positive finite USD/MTok")
    hashes = fingerprints()
    prior = None
    for path, required_stage in [(args.prior, "generate" if args.stage == "repeat" else "repeat"),
                                 (args.calibration, "calibrate")]:
        if path:
            artifact = read_json(path)
            if artifact.get("hashes") != hashes or artifact.get("stage") != required_stage or not artifact.get("passed"):
                parser.error("Prerequisite artifact is failed, stale or from the wrong stage")
            if path == args.prior:
                prior = artifact
    if args.stage in ("repeat", "full") and (not prior or not args.calibration):
        parser.error("Repeat/full requires a passed previous stage and judge calibration")
    if legacy.Anthropic is None:
        parser.error("Existing anthropic SDK is unavailable; no dependency was installed")
    if not os.environ.get("ANTHROPIC_API_KEY"):
        parser.error("ANTHROPIC_API_KEY is required")
    gen_model, judge_model = legacy.GEN_MODEL, legacy.JUDGE_MODEL
    budget = Budget(args.budget_usd, args.max_calls, {gen_model: args.prices[:2], judge_model: args.prices[2:]})
    client = legacy.Anthropic(max_retries=0)  # Never hide SDK retries outside the approved budget.
    prompt = legacy.strip_front_matter((ROOT / "prompts/s07/v3.md").read_text(encoding="utf-8"))
    judge_prompt = (HERE / "judge_v4.md").read_text(encoding="utf-8")
    trials = list(prior["trials"]) if args.stage == "repeat" else []
    work = [(c, i, None) for c in selected for i in range(3 if args.stage in ("repeat", "full") else 1)
            if not any(t["case_id"] == c["id"] and t["trial"] == i for t in trials)]
    if args.stage == "calibrate":
        work = [(f["case"], 0, f) for f in fixtures]
    expected = len(fixtures) if args.stage == "calibrate" else len(selected) * (3 if args.stage in ("repeat", "full") else 1)
    artifact = {"version": contract["version"], "stage": args.stage, "hashes": hashes,
                "models": [gen_model, judge_model], "prices": args.prices,
                "created_at": datetime.now(timezone.utc).isoformat(), "trials": trials,
                "prerequisites": [str(p) for p in (args.prior, args.calibration) if p]}
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def checkpoint():
        artifact.update(summarize(trials, expected, args.stage == "full"))
        artifact.update(attempts=budget.attempts, cost_or_reserved_usd=budget.spent, calls=budget.calls)
        args.output.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    checkpoint()
    for case, index, fixture in work:
        trial = {"case_id": case["id"], "status": "execution_error", "scores": None}
        try:
            trial = run_trial(client, budget, case, prompt, judge_prompt, gen_model, judge_model,
                              fixture["output"] if fixture else None, trial=trial)
            if fixture:
                trial = calibration_trial(trial, fixture)
        except BudgetStop:
            trial.update(status="budget_stopped", scores=None)
        except Exception as error:
            trial.update(status="execution_error", scores=None,
                         error_type=type(error).__name__)  # Never log exception text or credentials.
        trial["trial"] = index
        trials.append(trial)
        checkpoint()
        if trial["status"] != "passed":
            break  # A core problem stops the full run immediately. Failed trials remain.
    return 0 if artifact["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
