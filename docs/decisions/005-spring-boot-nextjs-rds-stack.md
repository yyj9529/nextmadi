# ADR-005: Spring Boot + Next.js + RDS PostgreSQL on AWS

Date: 2026-05-13
Status: Accepted

## Context

PhraseLog requires a backend handling LLM orchestration (the Whisper / Claude / TTS pipeline from ADR-001), 14 frontend screens, persistent storage for saved expressions and session data, and infrastructure that supports a freemium pricing model.

Two main approaches considered:

1. **BaaS (Supabase)** — bundled Postgres + auth + storage + edge functions.
2. **Conventional stack on AWS** — Spring Boot + Next.js + RDS + S3 + NextAuth.

## Decision

Conventional stack on AWS.

- **Backend**: Spring Boot on AWS EC2
- **Frontend**: Next.js on Vercel (Hobby initially; Pro at monetization)
- **Database**: PostgreSQL on AWS RDS (with pgvector for RAG planned in W21~24)
- **Storage**: S3 (audio files, TTS cache)
- **Auth**: NextAuth with Google + Kakao + email

## Why

1. **LLM orchestration fits Spring Boot's service layer.** The audio pipeline (ADR-001) involves async queueing, retry policies for three external APIs (Whisper, Claude, OpenAI TTS), and custom logic between the LLM and TTS stages for per-turn roleplay coaching. Spring Boot's service architecture handles this naturally. Supabase Edge Functions impose cold-start latency and execution-time limits that conflict with audio pipeline timing.

2. **Direct Postgres control matters for this workload.** Custom indexes on the saved-expressions library, pgvector for RAG retrieval (planned W21~24, see roadmap), connection pool tuning for concurrent audio API calls, and manual query plan inspection during cost optimization. The BaaS abstraction layer hides exactly the surface that needs control here.

3. **AWS ecosystem consistency.** RDS + S3 + EC2 in one environment unifies IAM, VPC, CloudWatch monitoring, and billing. A Supabase + AWS hybrid splits across two consoles, two IAM models, and two billing surfaces — operational overhead without offsetting benefit.

4. **Freemium margin predictability.** Supabase pricing scales by database size, bandwidth, and concurrent connections. PhraseLog's audio traffic to S3 and frequent DB writes make total Supabase cost difficult to predict at scale. AWS reserved instances and direct vendor pricing per service give clearer unit economics for a freemium model.

## Consequences

More operational responsibility — RDS backups, EC2 deployment, Spring Boot connection pooling, NextAuth configuration. Justified by the control benefits above.

Spring Boot + EC2 cold-start time and connection overhead are now the developer's responsibility rather than the provider's. Performance tuning happens at the application layer.

If the operational burden meaningfully blocks shipping product features (rather than just adding routine ops work that can be automated), this ADR gets revisited.

## Why not

- **Supabase.** Splits infrastructure across two providers, abstracts away the database control surface that this workload requires, and introduces unpredictable cost scaling. The bundled convenience does not offset these for an LLM-heavy product.

## Related

- ADR-001: Split pipeline runs as Spring Boot services on this infrastructure.
- ADR-004: Infrastructure setup is part of W1~3 planning DoD.
- Roadmap: pgvector for RAG planned W21~24.
