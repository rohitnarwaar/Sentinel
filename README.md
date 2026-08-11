# Sentinel — real-time AI fraud investigation platform

Sentinel ingests transactions in real time, flags anomalies with a deterministic
rules engine, and (in the next build phase) hands flagged cases to an AI agent
that retrieves similar historical cases and drafts a human-readable
investigation report with a risk score and reasoning — the way a senior fraud
analyst would.

## Status: Phase 1 complete (ingestion + rules engine)

- [x] Kafka event pipeline (`transactions` topic, keyed by `accountId`)
- [x] Spring Boot ingestion service, consumes + persists every event
- [x] Deterministic rules engine: velocity checks, amount-deviation checks,
      high-value threshold
- [x] Fraud case creation on flag
- [x] REST API for transactions and cases
- [x] Local transaction simulator (scheduled job, ~8% anomalous by design)
- [ ] Phase 2: anomaly scoring model (statistical or lightweight ML service)
- [ ] Phase 3: AI investigation agent — pgvector similarity search over past
      case notes + LLM-generated report
- [ ] Phase 4: dashboard, Actuator/Prometheus/Grafana wiring, polish

## Running locally

```bash
# 1. Start infra
docker compose up -d

# 2. Run the app
./mvnw spring-boot:run
```

The simulator starts automatically and publishes 1–3 transactions every 4
seconds. Watch the logs for `Opened fraud case ...` lines, or hit:

```bash
curl http://localhost:8080/api/cases
curl http://localhost:8080/api/transactions
```

## Architecture

```
Transaction stream (Kafka)
        │
        ▼
Ingestion service (Spring Boot) — validates, persists
        │
        ▼
Rules engine — velocity / deviation / high-value checks
        │
        ▼
Anomaly detector [phase 2] — scores against account history
        │
        ▼
AI investigation agent [phase 3] — RAG over past cases, drafts report
        │
        ▼
Case dashboard — live alerts, risk score, AI report, analyst review
```

## Why the rules engine runs before the AI layer

Cheap, deterministic checks catch the obvious cases and give the system an
audit trail an auditor can actually verify. The AI layer is reserved for the
genuinely ambiguous cases — where the reasoning, not just a threshold, adds
value. This mirrors how real fraud teams are structured, and it's a
deliberate design choice worth mentioning in interviews.

## What's next (phase 2 and 3, in order)

1. **Anomaly scoring** — replace the rule-based score with an actual model.
   Simplest defensible version: z-score of transaction amount against the
   account's rolling history, exposed as a tiny FastAPI service so the
   project also demonstrates polyglot service boundaries.
2. **Vector store** — embed synthetic "past case notes" (you'll write ~50-100
   realistic fraud/non-fraud case summaries) into Postgres via pgvector.
3. **AI agent** — given a newly opened `FraudCase`, retrieve the top-k similar
   past cases by embedding similarity, then prompt an LLM to produce a
   structured JSON report: `{ riskScore, reasoning, recommendedAction }`.
   Store it back on the `FraudCase` entity.
4. **Dashboard** — a simple React or even server-rendered view listing open
   cases with their AI report, so a reviewer can approve/dismiss.

## Tech stack

Java 21 · Spring Boot 3.3 · Spring Kafka · Spring Data JPA · PostgreSQL +
pgvector · Micrometer/Prometheus · Docker Compose
