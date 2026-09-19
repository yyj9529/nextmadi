# S07 CI cost control

PR creation and updates run offline tests only, including drafts. The workflow has no provider key and no generation command. A green check proves offline verification, not model quality. Existing quality adoption thresholds remain unchanged.

Paid validation is a separate manual operator action after explicit approval of the candidate revision, cases, calls, verified prices, budget ceiling and stop conditions. No automatic paid workflow or full-suite fallback is retained. Do not run the unbudgeted legacy command as PR CI.

The S07 alignment candidate supplies a budgeted runner and staged proposal. Until that candidate and its prerequisites are available, paid validation remains unavailable through this workflow. Future manual CI must enforce the same controls before receiving provider credentials.

Keep this workflow change in S07 branches until it lands in main; draft status alone does not prevent paid jobs. If branch protection requires the old paid check, adjust the requirement explicitly rather than presenting offline success as a quality pass.

Verification: inspect YAML for pull_request triggering, contents-read permissions, no provider secrets and no paid command. Run existing offline unittest checks. No paid evaluation or deployment is authorized by this change.
