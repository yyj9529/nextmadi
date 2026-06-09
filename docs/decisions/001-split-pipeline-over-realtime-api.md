# ADR-001: Split pipeline for voice (Whisper / Claude / TTS) instead of OpenAI Realtime API

Date: 2026-05-13
Status: Accepted

## Context

PhraseLog needs voice in, AI response, voice out. Two options:

- **OpenAI Realtime API** — one integrated voice-to-voice call.
- **Split pipeline** — Whisper (STT), Claude (LLM), OpenAI TTS as three separate calls.

The decision affects backend architecture and was made during planning (W1~3, see ADR-004).

## Decision

Split pipeline. Initial models: Whisper, Claude Sonnet 4.6 for deep analysis with Haiku 4.5 for fast per-turn feedback, OpenAI TTS.

Model names above are implementation-time defaults, not permanent architectural commitments. Before implementation, verify current availability, pricing, latency, and quality. Model choice will be formally re-evaluated in the Model Comparison phase (W17~20); the pipeline architecture itself is stable regardless of that outcome.

## Why split

Cost is expected to be substantially lower based on public per-API pricing — unmeasured, and not the main reason. Even at price parity, split pipeline gives:

- **Independent model selection per stage.** Whisper for STT, Claude for analysis, OpenAI for TTS — each can be swapped without touching the others.
- **Custom logic between LLM and TTS.** Per-turn coaching feedback during roleplay needs code between LLM response and audio output. Realtime's single call can't host that.
- **Cleaner avatar lip-sync integration** for planned v1.5+. TTS produces text + audio; lip-sync needs the text. Realtime's audio-only output requires extra speech-to-viseme work.
- **Cost-quality routing.** Cheap model (Haiku) for fast per-turn feedback; premium (Sonnet) for deep analysis. Realtime locks GPT-4o for everything.

Realtime would be simpler to wire up, but losing all four of the above is too much for a freemium product.

## Consequences

Higher end-to-end latency than Realtime — three sequential calls instead of one. Acceptable for guided turn-based roleplay; if alpha users report it as primary friction, free-form conversation mode will need a different architecture and this ADR gets revisited.

Three APIs to integrate means three retry/timeout/error-handling paths, three failure modes, three vendor cost lines to monitor.

Cost data will be collected during early development (token logging) and beta (production monitoring) for pricing-model design — not as verification of this decision, since arguments above hold regardless.

## Why not

- **OpenAI Realtime API.** Cost likely incompatible with freemium margins; locked model; awkward lip-sync.
- **Deepgram + ElevenLabs mix.** Under consideration for the Model Comparison phase (W17~20). OpenAI for both stages currently, pending benchmark data.

## Related

- ADR-003: Eval system runs on the LLM stage of this pipeline.
- ADR-004: Schedule that places this decision in the W1~3 planning phase.
