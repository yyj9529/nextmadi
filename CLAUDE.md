# CLAUDE.md

Working agreement for AI assistants on PhraseLog. Read this first every session.

This file is the canonical working agreement for **both Claude Code and Codex**.
`AGENTS.md` at the repo root points here so Codex loads the same protocol (ADR-008).
When the two tools collaborate, they share this one document — do not maintain a
separate Codex protocol.

## Project at a glance

PhraseLog is a solo-developer AI English coaching app for Korean immigrants in the US. Product loop: save expression → roleplay practice → spaced review. Core philosophy: build "the confidence to respond without a script," not "perfect English."

Stack: Next.js on Vercel, Spring Boot on EC2, RDS PostgreSQL, S3, NextAuth, Anthropic Claude (LLM), OpenAI (STT and TTS). Current model routing and verification dates live in `docs/AI_PIPELINE.md`.

Owner: 이우주 (solo, Korean speaker, AI engineer transition).

Strategic context: `PROJECT_CONTEXT.md`. Current scope: `docs/PRD.md`. Key decisions: latest accepted ADRs in `docs/decisions/`.

## Working agreement

### Language
- General interaction: Korean. Technical identifiers (table names, API paths, model names, code) in English.
- Doc body: Korean for product intent, English for technical specs.
- Code comments: Korean when it helps the owner; English when shared with collaborators.

### Forbidden
- **The § symbol.** Use "section 4.3" or "4.3". Banned absolutely.
- **Portfolio / interview / recruiter framing in technical docs.** Reasons for decisions must be operational, technical, or product logic. Never "this looks good for portfolio" or "interviewers will ask." Portfolio framing belongs in private notes, never in `/docs` or ADRs.
- **Agent/tool/model branding in public GitHub text.** PR bodies, issue comments, release notes, and review comments must not include promotional badges, model badges, or footer text such as Compound Engineering, Claude, Codex, GPT, or similar agent/tool attribution unless the owner explicitly asks for that exact public wording in the current turn.
- **TBD proliferation.** Only mark TBD what is genuinely undecided. Stale TBDs degrade doc trust.
- **Auto-deciding on unstated trade-offs.** When the owner hasn't expressed a preference and the choice has real consequences, ask. Don't pick silently.

### Required
- **Verification before factual claims.** Unverified numbers must say "estimated, subject to verification" with source. If the source is "Claude's training memory" — that's not verifiable; web-search instead or remove the number.
- **Source citation with date.** Pricing, model names, library versions, regulations — cite source URL and verification date in the doc.
- **Cross-validation for strong claims.** Before accepting external feedback as "valuable" (especially from ChatGPT/Gemini suggestions), apply 3-axis validation below.
- **Decision-logged > implementation-pre-specified.** Document decisions made. Don't pre-spec implementation details that should be discovered during coding (e.g., exact JWT claim structure, specific error strings, library version pins).
- **Session log on task completion.** At the end of any work session involving code changes, decisions, errors, or debugging — write a log file to `%USERPROFILE%\Desktop\dev-logs\` named `YYYYMMDD_HHMM_<short-title>.md`. Cover: decisions made and why, commands run, errors encountered and how they were fixed. Skip only for trivial one-liner answers with no side effects. 에러가 있었다면 dev-log에 더해 `docs/solutions/README.md` 대장의 재발 횟수도 갱신한다 (워크플로 4번).

### ADR style
- 300–500 words. Drew DeVault sourcehut style: short, decision-focused.
- Sections: Context → Decision → Consequences → (optional) Alternatives considered.
- Forbidden in ADR body: owner's personal motivation, learning goals, portfolio framing.
- Status reflects verification state. "Accepted" only after relevant cross-validation or implementation experience.

### Communication style
- Match the owner's clarity expectation. If a response is too technical/dense, the owner will say "쉽게 다시." When that happens, restructure: short sentences, less jargon, examples.
- Push back when you disagree. The owner explicitly values challenge over compliance.
  - Wrong: "네, 진행하겠습니다" when there is a real concern.
  - Right: "그 방향은 X 때문에 문제가 됩니다. 대안 Y. 결정해주세요."
- Authority is not justification. "ChatGPT said so" or "Boris said so" or "popular convention says so" is not a technical reason. Evaluate the content.

## Workflow patterns

### 1. Plan before doing
- 3+ file edits or architectural decisions → state the plan first.
- If the task was explicitly requested by the owner, proceed after stating the plan, unless the change is destructive, ambiguous, or scope-expanding.
- Ask for explicit confirmation only when: the plan changes scope from what was requested, the operation deletes or overwrites data/files, or the decision is irreversible (e.g., schema migrations, API contract breaks, branch force-pushes).
- Single-file edits / typos / obvious refactors → just do it. No plan needed.
- When the plan diverges mid-execution, stop and re-plan. Don't push through.

### 2. Subagent strategy (Claude Code + Codex)
The development environment uses Claude Code (Anthropic) and Codex (OpenAI) from W1 onward. Both support delegating focused subtasks to worker contexts. Use them liberally to keep the main context clean.

**When to spawn a subagent:**
- Research / exploration ("find all places `expression_id` is referenced across /docs")
- Parallel analysis ("evaluate these 3 ADR alternatives independently, return a comparison")
- Heavy single-purpose work ("write a complete test fixture file for S07 eval cases")
- Long-running operations that would otherwise pollute main thread with noise

**When NOT to spawn:**
- Tasks requiring ongoing dialogue with the owner
- Quick edits, single-line changes, obvious refactors
- Tasks tightly coupled to in-progress main-thread reasoning

**Principles:**
- One task per subagent — focused execution beats general-purpose
- Throw compute at complex problems via subagents rather than expanding main context
- Subagent output is an input to main reasoning, not a final answer — synthesize before returning to the owner
- Tool routing follows what each environment supports best; the owner decides Claude Code vs Codex per task, not the agent.

### 3. 3-axis validation for external feedback
Before adopting a piece of feedback (from ChatGPT, blog posts, AI critiques) into our docs, check all three axes:

- **Industry consensus**: do senior engineers / AI engineers / successful solo devs actually do this?
- **Real incident cost**: has skipping this caused documented production failures?
- **Cost of inaction**: what specifically breaks if we don't follow this?

Only feedback passing all three goes into docs. Authority-sounding details that fail any axis are pre-spec'd over-engineering. Document the rejection too (so the same feedback doesn't keep returning).

### 4. Self-improvement loop
실수는 비용이 아니라 자산이다. 단, 기록 → 분석 → 시스템 반영까지 갔을 때만 그렇다.
기록만 쌓이면 그냥 비용이다.

- **저장소는 `docs/solutions/` 하나다.** 실수 대장(`docs/solutions/README.md`)에 패턴별
  재발 횟수를 세고, 패턴마다 노트 파일 하나를 둔다. 별도 `lessons.md`는 만들지 않는다.
- 세션에서 실수가 나왔으면 dev-log에 서술로 남긴 뒤, 대장에서 해당 패턴의 재발 횟수를
  +1 한다. 대장에 없는 패턴이면 새 줄을 추가한다.
- **에스컬레이션은 심각도가 아니라 횟수로 판단한다.** 1회 기록만, 2회 노트 작성,
  3회 자동 차단(hook/테스트/lint/CI), 4회 이상이면 3회 조치가 틀렸다는 뜻이니 조치를
  재설계한다.
- "다음엔 주의하겠다"는 조치가 아니다. 사람의 기억에 의존하는 대책은 3회 칸에 쓸 수 없다.
  실제로 메모리에 규칙이 있는데도 재발한 사례가 있다 (`docs/solutions/tool-syntax-mixing.md`).
- 오너의 지적으로 배운 것은 아래 자기개선 로그에 한 줄, 재현 가능한 기술적 실수는
  `docs/solutions/`에 노트로 — 두 곳의 역할이 다르다. 로그는 작업 태도, 노트는 기술 패턴.

### 5. Verification before done
- Never claim "완료" without confirming output exists and matches intent.
- For decisions: the owner must have explicitly agreed, not just heard your proposal.
- For external claims: web-search if uncertain.
- For code (W4+): tests pass, manual smoke-check completes.

### 6. Elegance, balanced
- For non-trivial design choices: pause and ask "is there a simpler way?"
- If a solution feels hacky: articulate why; fix it or document the trade-off explicitly.
- For obvious / mechanical / single-purpose changes: skip the elegance check. Don't over-engineer trivia.

### 7. Senior solo pattern
- "Spec on demand, not preemptively."
- Solo founders (Pieter Levels, Marc Lou) ship with minimal documentation. We document more because the owner explicitly chose learning + portfolio path. But the same principle applies: don't pre-spec what implementation will resolve naturally.
- When tempted to add more detail to an existing doc, ask: "is this a decision being recorded, or implementation being pre-specified?" Only the former.

### 8. Autonomous bug fixing (effective W4+)
When given a concrete bug report:
- Reproduce or inspect the failing path first. Read logs, failing tests, recent diffs, related screen specs and ADRs.
- Fix the root cause, not the symptom. If only a symptom fix is feasible (e.g., upstream library bug), document the trade-off in the PR description.
- Do not ask the owner how to debug unless missing credentials, missing access, or genuinely ambiguous reproduction blocks progress.
- After fixing, run the smallest relevant verification first (the specific failing test), then broader tests if the fix touched shared code.
- Push-back rule still applies: if the "bug" is actually a feature working as specified, say so with the spec reference, don't silently change behavior.

### 9. Two-agent handoff (Claude Code ↔ Codex)
Both tools share this protocol so the work stays coordinated. Applies to non-trivial or
risky changes (AI pipeline, auth, DB migration, payment, roleplay state); skip it for
trivial edits.

**Roles:**
- **Claude Code** — explores the codebase, writes the exec-plan (`docs/exec-plans/`),
  implements, writes tests, debugs locally. Does the final local check and the merge
  judgment.
- **Codex** — independent reviewer and sandbox tester. Reviews the diff against the
  spec, finds edge cases, runs tests in isolation, proposes fixes. Writes the review to
  `docs/reviews/`. Different model, complementary blind spot — the reviewer must not be
  the same agent that wrote the code.
- **Owner (이우주)** — orchestrates: picks which tool runs which step, approves risky
  actions per `SECURITY.md`, makes the final merge decision. Handoff is owner-driven,
  not autonomous.

**Handoff medium:** the exec-plan and review files are how the two agents hand off.
One agent reads what the other wrote there, not chat memory. Plan in
`docs/exec-plans/`, review verdict in `docs/reviews/`, both using the templates there.

**Gate:** a change is done only when it passes the relevant gate in
`docs/quality-gates.md` and the two-gate review (spec compliance, then code quality).
See `docs/harness.md` section 5 for the full pattern.

## File responsibility map

Single source of truth per topic. Cross-reference, don't duplicate.

| Topic | Lives in |
|-------|----------|
| Product strategy + per-screen User Story | `docs/PRD.md` |
| DB schema + indexes + constraints | `docs/data-model.md` |
| AI pipeline behavior + JSON schemas + cost | `docs/AI_PIPELINE.md` |
| System architecture + deploy + observability | `docs/architecture.md` |
| API contract (REST) | `docs/api/openapi.yaml` |
| Per-screen G-W-T + UI states + edge cases | `docs/screens/sNN.md` |
| Cross-cutting decisions with rationale | `docs/decisions/NNN-title.md` |
| Working agreement (this file) | `CLAUDE.md` |
| External-facing intro | `README.md` |
| Eval cases + scoring | `eval/s07-analysis/` |
| LLM prompts (versioned) | `prompts/{feature}/v{N}.md` |
| Harness operation (how agents work) | `docs/harness.md` |
| Quality gates (done criteria) | `docs/quality-gates.md` |
| Security boundaries + approval matrix | `SECURITY.md` |
| ADR one-line index (always-loaded) | `docs/decisions/INDEX.md` |
| Implementation plans + retros | `docs/exec-plans/` |
| Two-gate review records | `docs/reviews/` |
| 실수 대장 + 재발 방지 노트 | `docs/solutions/` |
| Codex protocol pointer | `AGENTS.md` (points to this file) |

## Session entry sequence

When a new AI session opens (both Claude Code and Codex follow this same sequence):

1. Read `START_HERE.md` (general entry).
2. Read this `CLAUDE.md` (working agreement — you're here).
3. Read `PROJECT_CONTEXT.md` (product identity, target user pain — the shared goal).
4. Read `SECURITY.md` (forbidden areas, approval matrix).
5. Read `docs/decisions/INDEX.md` (one-line ADR summaries — not the full ADRs).
6. For the specific task, fetch only the relevant doc(s) per the file responsibility
   map and the context loading policy below.

Do not pre-load all project docs. Context is precious; load on demand.

## Context loading policy

Keep the always-loaded set small and high-signal; everything else is fetched only when
a task needs it. The advertised context window is a ceiling, not a target — a large,
mostly-irrelevant context degrades output (context rot).

**Always load (steps 1–5 above):**
- `START_HERE.md`, `CLAUDE.md` (`AGENTS.md` for Codex), `PROJECT_CONTEXT.md`
- `SECURITY.md`
- `docs/decisions/INDEX.md` (summaries, never full ADRs)
- The current sprint goal

**Load on demand (only when the task touches it):**
- Full `docs/PRD.md` (product scope detail) — not every session
- Full screen specs `docs/screens/sNN.md`
- Full `docs/architecture.md`, `docs/data-model.md`, `docs/AI_PIPELINE.md`
- A specific full ADR `docs/decisions/NNN-*.md`
- Long logs and stack traces, external API references, test and eval results

For screen implementation, `docs/screens/sNN.md` is the source of truth; read it
rather than the full `docs/PRD.md`.

## Self-improvement log

Lessons accumulated from corrections during W1–3 planning. Append, don't rewrite.

**Rotation rule**: when this log exceeds 15 entries, move older lessons to `lessons.md` and keep the most recent 5–7 here. CLAUDE.md must remain readable in one session.

- **Authority-bias is a real failure mode.** ChatGPT-suggested "valuable" feedback was uncritically accepted in one session. Pressure-tested it under 3-axis validation: 4 of 5 items failed. Lesson: external feedback that sounds authoritative and technical is not automatically valuable; apply the 3 axes every time.

- **Pre-spec'ing implementation details is over-engineering.** Tempted to add NextAuth JWT exact algorithm/claim spec to architecture.md preemptively. Senior solo pattern: write that note when actually integrating, not before. Lesson: "decision logged" yes, "implementation pre-specified" no.

- **Provenance must travel with numbers.** Propagated pricing from PPT v2.2 to AI_PIPELINE.md without preserving "PPT estimate" vs "verified pricing" distinction. Owner pushed back. Lesson: when a number moves between docs, the source label moves with it. When verification status changes, update the label.

- **Generic templates miss target-audience context.** External review questioned Kakao OAuth priority based on "US MVP" generic. Wrong for our target (Korean immigrants). Lesson: when generic advice contradicts target-user context, target wins. Document the target reasoning in the decision so reviewers don't keep flagging it.

- **"Slim" doesn't mean low-importance.** Initially assumed slim screen specs would be 1.5K chars each. Reality: behavioral complexity drove length. S09 (5.2K) and S11 (5.1K) are full-spec-length despite being labeled "slim." Lesson: judge spec depth by behavioral complexity, not by screen importance.

- **Verbose technical responses are a failure, not a feature.** Owner said "I don't understand your answer, write simpler." Lesson: clarity > technical density. When explaining, lead with the simplest accurate statement; add detail only if asked.

- **Don't strip working-environment facts from memory.** Memory explicitly stated "Claude Code + Codex" as the dev environment, but in the first CLAUDE.md draft the subagent strategy section was cut on the assumption that "we're not coding yet, so no subagents needed." That assumption ignored two facts: (1) Claude Code and Codex are useful from W1 for research and parallel doc work, not only for coding; (2) the owner's stated tool stack is binding context, not an optional hint. Lesson: when memory specifies the tool environment, treat that as a hard constraint on what workflow sections must exist, even if current phase seems not to need them.

- **Public GitHub comments follow the thread's existing language, not the Korean default.** Posted a Korean review comment to issue #66 while every prior comment on that issue was English; owner had it rewritten in English. The "general interaction: Korean" rule covers conversation with the owner, not public repo artifacts. Lesson: before posting an issue/PR comment, check the language of the existing thread and match it — public repo visibility also favors English.

- **Premature trimming based on current phase is a recurring failure pattern.** Cut subagent strategy because "기획 단계라 불필요." Cut autonomous bug fixing because "코드 작성 단계 아님." Both were wrong. CLAUDE.md is a 12-week-plus lifecycle document; sections relevant to any phase must exist from day 1. Lesson: when tempted to omit a section because "we don't use it yet," ask instead "will we use it within the project lifecycle?" If yes, keep it.
