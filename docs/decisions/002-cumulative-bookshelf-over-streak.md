# ADR-002: No streak; cumulative bookshelf instead

Date: 2026-05-13
Status: Accepted

## Context

Many language learning apps use consecutive-day streak as an engagement mechanism. Initially considered for PhraseLog.

User research in Q1 2026 — Threads survey of Korean immigrants in the US (3 posts: `jungin_yoonjaemom` 10,900 views, `ggogi.eun` 17,845 views, `wooju504` 4,271 views; ~91 comments combined) plus 5 offline interviews in the Atlanta area — surfaced post-failure self-blame as the dominant emotional pattern. Sample comment:

> "집에 와서 내가 뭘 잘못했을까 곱씹어봄. 영어 서투른 게 문제인 거 같아."

PhraseLog's product promise is "confidence to respond without script." Adding a "you broke your streak" failure state on top of existing self-blame works against that promise for this specific user segment.

## Decision

No streak. Show cumulative count of saved expressions on the home screen and celebrate milestones as the bookshelf grows. The bookshelf only grows; it never resets, decays, or punishes absence.

Milestone thresholds, count display copy, and celebration design will be finalized in W1~3 planning.

## Consequences

The cumulative bookshelf matches the underlying data — expressions accumulate permanently in the library, so accumulation is the natural visualization. Streak would be a derivative visualization layered on session timestamps.

Retention impact is unmeasured. Streak is a well-known retention tool and we're declining to use it; D7/D30 retention may underperform streak-based competitors. If retention is materially below benchmarks after launch, this gets revisited with a new ADR — but not by adding streak (excluded for product-coherence reasons; "no streak" will be part of how the product is communicated, and reversing it would feel like a betrayal of stated values).

Milestone engagement (which thresholds users hit, screen view rates) will be tracked operationally to refine thresholds, but those numbers don't change the streak-vs-bookshelf decision.

## Why not

Variants of streak — auto-pause, weekly challenges, user-toggleable — either still introduce a failure state or defer the choice to users who mostly won't engage with it. None addresses the underlying conflict with the product promise.

## Related

- User research raw data: Threads survey + 5 offline interviews (Notion `Research Notes`).
- PPT v2.1: Friction Audit and Character System sections.
