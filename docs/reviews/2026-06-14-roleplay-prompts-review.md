# Review — Roleplay prompt v1 + persona prompts (#58)

- **Date:** 2026-06-14
- **Reviewer:** Claude Code (review-only; no code written)
- **Scope:** `prompts/roleplay/{init,turn,feedback,result}/v1.md` and personas
  `prompts/roleplay/{mia,david,sarah}/v1.md`
- **Context read:** #58, s12.md, s12b.md, PROJECT_CONTEXT.md, AI_PIPELINE.md
  (routing + schemas), data-model.md (`coach_profiles`, `feature_name` enum), and the
  downstream tickets #28, #26, #59, #60, #62.
- **Verdict:** **Small edits needed.** Tone and flow are strong. One coordination gap
  (persona composition contract) must be settled before #28/#59/#60 start, plus minor
  frontmatter/cleanup fixes. Nothing here is a hard blocker on the prompt content itself.

---

## 1. Product / tone — strong, ships as-is

The four feature prompts and three personas are well aligned with the product
principles in PROJECT_CONTEXT (shame reduction, confidence over perfection, complete
loop).

- **Shame-reducing feedback** is enforced, not just hoped for: feedback rule 6 bans
  "wrong"/"bad" in Korean; result rule 8 says "avoid shame, emphasize what to try next."
  Matches product principle 1.
- **Confidence over perfection** is the default posture: feedback is "quiet by default,"
  with explicit `show_feedback_false_when` for understandable-but-imperfect utterances.
  This is the right call and directly counters the script-dependence / self-blame loop.
- **Roleplay flow**: turn rules "do not dominate," "ask a natural follow-up,"
  "simplify rather than end if the user is confused" keep the user in a safe, moving
  scenario. Good.
- **Coach consistency**: Mia (warm), David (direct), Sarah (social) are genuinely
  distinct, and each persona carries a matching line for the Korean encouragement style
  used in `roleplay_result`. The voice rules are concrete enough to produce different
  outputs.
- **Schema fidelity**: every feature prompt's `<output_format>` matches its
  AI_PIPELINE schema field-for-field (init → planned_turns/scenario_setup/
  complexity_rationale; turn → coach_utterance; feedback → show_feedback +
  conditional fields; result → recommended_expressions/awkward_pairs/
  pronunciation_focus_words/coach_encouragement). #26 schema validation should pass.

### Product nit (small edit, not blocking)

- **No closure guidance on the final turn.** The app ends the session at
  `turn_number == planned_turns` (s12.md US2-6, #60). The turn prompt has no awareness
  of "near the end," so the last coach utterance can be an open follow-up question that
  the app then cuts off — leaving the conversation feeling dropped rather than resolved.
  Consider a turn rule like: "If the conversation is near its planned end, move toward a
  natural closing line instead of opening a new thread." The app still owns the hard
  stop; the prompt just makes the last line land. (The turn prompt does receive
  `planned_turns` + full history, so it has the signal to do this.)

---

## 2. Coordination gap — settle before #28 / #59 / #60 / #62

**The persona-composition contract is implicit.** This is the one item that should be
nailed down before the loader and roleplay backends are built.

- `coach_profiles.prompt_template_ref` points at the **persona** file
  (`prompts/roleplay/mia/v1.md`), per data-model.md:94 and AI_PIPELINE.md:106.
- But `feature_name` routing (AI_PIPELINE.md:99–102) points at the **feature** files
  (`init`/`turn`/`feedback`/`result`).
- So a single roleplay call needs **two files composed**: the feature prompt plus the
  persona injected as its "coach persona context" input. Every feature prompt lists
  "coach persona context" in `<inputs>`, but **no file contains an injection
  marker/placeholder**, and nothing documents the assembly order or format.

Why this matters per ticket:

- **#28 (Prompt Loader)** is specified to load and front-matter-strip a *single* file
  (its AC targets `prompts/s07/v1.md`). It has no notion of composing feature + persona.
  Either the loader gains a compose step, or the backend composes — decide and document.
- **#59 / #60 / #62** each pass "coach persona context" into the call and need the exact
  assembly contract (where persona text goes, how it's delimited).
- **Recommendation:** add one short section to AI_PIPELINE.md defining the composition
  (e.g., persona block prepended/inserted at a named marker in each feature prompt), and
  decide whether composition is the loader's job or the caller's. This is a doc decision,
  not a prompt rewrite.

---

## 3. Mechanical / frontmatter — small edits (separate from tone)

These do not affect output quality but will confuse #28/#26 if the loader assumes every
file is a callable, schema-producing prompt.

1. **Persona `output_schema: coach_persona_context_v1` references a schema that does not
   exist.** It is not among the five schemas in AI_PIPELINE.md / #26, and persona files
   emit no JSON. If the loader/validator maps `output_schema` → a registered validator,
   this errors or silently mismatches. Fix: either drop `output_schema` from persona
   front-matter, or give personas a distinct "context fragment" type the loader treats as
   non-validatable.
2. **Persona `model: claude-sonnet-4-6` is misleading.** Persona context is injected into
   both Sonnet calls (init/turn/result) *and* the Haiku call (feedback). Tying a persona
   fragment to one model has no runtime meaning. Drop it or mark it N/A.
3. **Persona `feature: roleplay_coach_persona` is not in the `feature_name` enum**
   (data-model.md:417–423). A loader keyed on `feature_name` cannot route it. Acceptable
   *if* the loader is explicitly designed to treat persona files as fragments, not
   features — but that needs to be a stated decision (ties to item 2 above).
4. **Empty stray directory `prompts/roleplay/coaches/`.** Nothing references it
   (`prompt_template_ref` uses the per-coach dirs). Remove it to avoid loader-glob
   surprises.

---

## 4. Minor observations (no action required for v1)

- **Persona-as-counterpart blend is intentional but subtle.** The coach "plays the other
  person in the scenario" (init rule 6, turn rule "continue as the other speaker") while
  keeping its persona warmth/directness/sociability. This is coherent and matches s12, but
  confirm it's the intended design — it's the kind of thing a future reviewer will re-flag.
- **No content-safety guard for sensitive scenarios** (medical/legal/distress). Prompts
  say "emotionally safe" but don't tell the counterpart to stay in a coaching frame rather
  than give medical/legal advice. Likely out of v1 scope; noting for the backlog.

---

## Verdict

**Small edits needed.** The prompt content is product-ready: tone, shame reduction,
confidence-over-perfection, flow, and coach distinctiveness all hold, and the schemas
match. Before #28/#59/#60/#62 implementation:

1. Document the **persona composition contract** in AI_PIPELINE.md and decide
   loader-vs-caller responsibility (the one real coordination item).
2. Fix persona front-matter (`output_schema`, `model`, `feature`) or define a fragment
   type the loader recognizes.
3. Remove the empty `prompts/roleplay/coaches/` directory.
4. (Optional, product) Add a final-turn closure rule to the turn prompt.

None of these block using the prompts; they prevent predictable integration friction.
