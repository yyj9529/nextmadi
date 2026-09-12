# PhraseLog — Project Context

PhraseLog is an AI communication coach built for Korean immigrants living in the United States. This document captures the product's identity, who it is for, and why it exists in a way that is intentionally durable — most of what follows should remain valid throughout v1 and beyond.

## What PhraseLog is

PhraseLog is a tool for turning communication regret into reusable practice. A user describes a situation in which they could not say what they meant — at a doctor's office, in a difficult conversation with a teacher, in an everyday exchange that went sideways. The app generates natural English expressions, saves them, and later helps the user rehearse those expressions through guided roleplay with a chosen coach persona.

The core unit of value is one saved expression paired with the context that produced it.

## What PhraseLog is not

PhraseLog is not a generic English-learning app. It does not focus on grammar drills, vocabulary spaced repetition, or formal language progression. Mainstream language-learning apps (the spaced-repetition vocabulary category) are not the relevant comparison.

PhraseLog is not a real-time translation tool. It does not interpret live conversations. The product builds skills before situations and processes them after, supporting the user's own performance during the situation — not replacing that performance with live interpretation.

PhraseLog is not a free-form AI chatbot. Open-ended conversation with an AI is already commodified by ChatGPT, Claude, and Gemini. PhraseLog's structure — situated input, guided roleplay, saved-expression library — is intentionally different from open chat.

## Core promise (v1)

> Turn what I couldn't say today into something I can say next time.

This articulates the v1 product loop: regret → save → review → next-time use. As PhraseLog evolves beyond v1 (free-form conversation, deeper coaching, proactive practice modes), this framing will likely shift. Treat it as the operating slogan for the launch phase, not a permanent brand statement.

## Primary user

Korean immigrants in the United States. Length of residence does not determine eligibility — user research (Threads survey of ~33,000 combined views across 3 posts, plus 5 offline interviews in the Atlanta area) found the same communication pain points in users with 2 years of residence and users with 20 years of residence.

The recurring profile: someone who can transact in English (work, shopping, basic services) but struggles in moments that require emotional precision, cultural nuance, or assertiveness — and who experiences self-blame after those moments.

## Primary user pain

User research surfaced five recurring patterns, each present across both the Threads survey and the offline interviews:

1. **Duration-independence.** Self-described English struggles persist regardless of years in the US. Time alone does not fix it.

2. **Script-dependence loop.** Users prepare scripts before a known interaction, fail to use them in the moment, then blame themselves and re-prepare. The cycle repeats without progress.

3. **Non-verbal survival only.** Users rely on body language, translation apps, drawings, and gestures rather than verbal repair strategies. Linguistic survival tools (asking for clarification, expressing partial understanding, deflecting gracefully) are underused.

4. **"Confidence" as the desired outcome.** Users do not ask for perfect English. They ask for the confidence to respond without scripts, accepting imperfect output.

5. **ChatGPT as the de facto current solution.** "I ask ChatGPT and then say it" appears as actual reported behavior. Whatever PhraseLog offers must outperform this ad-hoc workflow on the Korean-immigrant use case specifically.

Representative responses from the research, paraphrased (verbatim text is held in the
private research notes, not in this repo):

> 대화가 끝나고 집에 오면 내가 뭘 잘못했는지 계속 되짚고, 결국 영어가 부족한 탓으로 돌린다.

> 미리 스크립트를 만들어 연습해 가지만, 실제 대화에서는 연습한 문장을 쓸 기회가 거의 없다.

> 강의를 결제해도 꾸준히 이어가지 못하고, 필요한 순간이 올 때마다 후회만 반복한다.

The emotional pattern is post-failure self-blame. Product decisions throughout v1 are oriented around not amplifying this pattern (see ADR-002 for the streak/bookshelf decision derived from this research).

## MVP loop

The v1 product loop is:

```
User describes a situation
→ AI generates 3 English expressions with pronunciation and cultural context
→ User saves to library
→ User reviews later
→ User practices through guided roleplay with a coach persona
```

Each stage feeds the next. The library is not a passive collection; it feeds review, which feeds practice. Removing any stage breaks the loop and removes most of the product's value (see ADR-006 for the integrated-launch decision).

## Differentiation

The competitive landscape for PhraseLog is not other language-learning apps. It is:

- **ChatGPT and similar general AI.** Already used ad-hoc by target users. PhraseLog must add structure that ad-hoc ChatGPT lacks: persistent context per user, situated input rather than generic prompting, and review and practice loops on saved output.
- **Google Translate and other instant-translation tools.** Solve a different problem (instant comprehension) and do not produce reusable practice data.
- **Generic English-learning apps.** Optimize for vocabulary and grammar progression, not for the emotional and situational repair needs identified in user research.

The differentiation is not "AI English coaching" as a category — it is the specific structure of regret capture → saved expression → cultural context → guided practice for this specific user segment.

The positioning, in one line:

> Not "translate this right now." Not "study English every day." But "capture the moment you failed to express yourself and make it reusable."

## Non-goals for v1

These are intentionally excluded from v1 scope and should not be proposed as MVP features:

- Real-time translation or live conversation interpretation
- Free-form open-ended AI conversation as the primary interface
- Generic vocabulary or grammar instruction
- Multi-language support beyond Korean-to-English
- Monetization mechanics in v1 (Vercel Hobby tier supports the non-commercial phase; see ADR-005)

Some of these may be revisited post-launch based on user data. None are part of v1.

## Product principles

These should remain true throughout v1 and inform all decisions in the project.

1. **Reduce shame, not increase pressure.** Avoid mechanics that punish absence or create another failure state. The user already brings enough self-blame.

2. **Preserve the complete loop.** When cutting scope, reduce depth — never remove a stage of the regret → save → review → practice loop.

3. **Prefer controlled workflow over autonomous agents.** For v1, deterministic product flow is safer than agentic autonomy.

4. **Build from real user language.** Initial scenarios are hypotheses. Real user inputs should reshape examples, eval cases, and roadmap.

5. **Measure AI behavior from the beginning.** Prompt changes should not be judged only by intuition. Cost, latency, and quality go in logs from day one.

6. **Do not optimize for imaginary scale.** Do enough architecture for production learning. Do not overbuild distributed systems before launch.

## Public vs private context

This document and the broader `/docs/` repository are intended for public GitHub visibility and AI collaborator context. They should remain:

- Product- and engineering-centered, not personal
- Free of identifiable user research details
- Free of API keys, secrets, and account IDs
- Free of private financial or family context

Private working material — raw interview transcripts with identifiable information, personal motivation notes, draft thinking — lives in Notion. The project owner's memory (held by AI collaborators in working sessions) sits between these layers as a third tier.

If a passage is intended for the public repo, remove private emotional context before committing.

## Related documents

- `START_HERE.md` — entry point for AI collaborators
- `docs/decisions/` — ADRs covering architecture and product decisions
- `docs/PRD.md` — product requirements with screen-level specs
- Notion `Research Notes` — raw user research data
