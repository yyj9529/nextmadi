# ADR-003: Eval system split — S07 analysis vs S12 roleplay

Date: 2026-05-13
Status: Accepted
Supersedes: Original monolithic eval design in storyboard PPT v2.1 (Eval main and Eval guide sections)

## Context

The PPT defined a single eval system: 5 scenarios (Doctor, Hospital, School, Auto Shop, Book Club) running through LLM-as-judge with a golden dataset, regression tests via GitHub Actions, and a dashboard.

Cross-validation against another AI model caught the flaw: those 5 scenarios are multi-turn conversational situations — they belong to S12 roleplay validation, not S07 analysis validation. S07 (PhraseLog's core feature) is single-turn: user input → 3 English expressions + IPA + pronunciation tip + cultural tip. Different output structure, different failure modes, different eval criteria.

Running both through one eval produces mixed signals. When the score drops, which subsystem regressed?

## Decision

Three tiers.

**Tier 1 — S07 mini eval, during planning (W1~3).** Test cases with LLM-as-judge (Sonnet 4.6) and JSON schema validation, living in `eval/s07-analysis/`. Purpose: catch S07 prompt regressions during W4~8 development. Test case count and judge prompt are finalized in planning. Part of the W1~3 Definition of Done (ADR-004).

**Tier 2 — S12 roleplay eval, post-launch.** Different criteria entirely: conversation flow, coach utterance ratio, feedback tone, cost per session. Built after ~50 real user inputs accumulate (event-triggered, not date-locked). The 5 scenarios get redefined based on what users actually do.

**Tier 3 — Golden dataset + dashboard, later.** Native validator labeling (spouse, doctor friend, writer friend — confirmed available) on real production data. GitHub Actions PR integration. Dashboard tool to be chosen when this tier starts.

## Why this split

S07 and S12 have different output structures and different failure modes. JSON schema matters for S07; conversation flow matters for S12. Mixed scores are diagnostically useless.

Tier 1 needs to exist before development starts because prompt iteration during W4~8 needs a regression signal — otherwise "is this prompt change better?" is answered by intuition.

Tiers 2 and 3 must wait for real user data. The original 5 scenarios were guesses about user input patterns. Real production input may reveal that some scenarios dominate and others are near-zero. Building S12 eval on hypothetical scenarios optimizes for the wrong thing.

## Consequences

PPT Eval main and Eval guide sections (v2.1) are now incorrect and need revision.

Two eval pipelines to maintain long-term. Acceptable — they serve different purposes and can't be sensibly merged.

Tier 1 LLM-as-judge scoring is subjective at first. Judge prompts will need iteration before scores are trustworthy. If judge-vs-human agreement stays consistently low after a meaningful sample, shift Tier 1 to rule-based checks (JSON schema, keyword presence) instead of LLM judgment.

If real usage patterns turn out very different from the initial 5 scenarios (likely), Tier 2 scenarios get redefined — that's not a failure of this ADR; it's the reason Tier 2 waits for data.

## Why not

- **Full eval system pre-launch.** Threatens W12 launch and optimizes for hypothetical data.
- **All eval post-launch.** Loses prompt iteration regression detection during W4~8 — the most active prompt-design phase.

## Notes

This ADR was reached after cross-validation against multiple AI models. An initial proposal of "manual sanity check pre-launch, full eval post-launch" missed that the 5 scenarios are S12-specific, not S07-specific. The two-tier separation above is the result. Validating high-stakes architectural decisions against multiple sources — peers, advisors, or independent AI models — is worth repeating; different sources surface different blind spots.

## Related

- ADR-001: The pipeline this eval runs against (LLM stage).
- ADR-004: Schedule that places Tier 1 in W1~3 planning DoD.
- PPT v2.1: Eval main and Eval guide sections (need revision).
