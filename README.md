# Sentinel - real-time AI fraud investigation platform

Sentinel ingests transactions in real time, flags anomalies with a deterministic
rules engine and a statistical anomaly scorer, hands anything flagged to an AI
agent that retrieves similar historical cases and drafts a human-readable
investigation report with a risk score and reasoning, and surfaces the result
on a live case-review dashboard — the way a senior fraud analyst's workflow
would look if most of it ran itself.

## Status: all four phases complete

- [x] **Phase 1** — Kafka ingestion, deterministic rules engine (velocity /
      amount-deviation / high-value), fraud case creation, REST API, local
      transaction simulator
- [x] **Phase 2** — statistical anomaly scoring: a z-score of each
      transaction against its own account's rolling history, combined with
      the rules engine into a single `anomalyScore`
- [x] **Phase 3** — AI investigation agent: pgvector similarity search over
      70 synthetic past-case notes + an Anthropic Claude call, running async
      off a dedicated Kafka topic, with retry/backoff and a fallback status
      so a flaky API call never loses a case
- [x] **Phase 4** — live case-review dashboard, Prometheus/Grafana wiring,
      this README

## Running locally

```bash
# 1. Start infra — Kafka, Postgres+pgvector, Prometheus, Grafana
docker compose up -d

# 2. (optional) supply your own Anthropic key for the AI investigation agent —
#    without it, the agent still runs, retries, and fails gracefully into
#    INVESTIGATION_FAILED instead of silently doing nothing
$env:ANTHROPIC_API_KEY = "sk-ant-..."     # PowerShell
# export ANTHROPIC_API_KEY="sk-ant-..."   # bash

# 3. Run the app — starts the Kafka consumers, the transaction simulator,
#    and serves the dashboard
./mvnw.cmd spring-boot:run
```

That's it — two commands (three if you're supplying a real API key). The
simulator publishes 1–3 synthetic transactions every 4 seconds automatically
(set `sentinel.simulator.enabled: false` in `application.yml` to pause it).

**What to open:**

| What | Where |
|---|---|
| Case review dashboard | http://localhost:8080 |
| REST API | http://localhost:8080/api/cases, `/api/transactions` |
| Grafana (Prometheus pre-wired, dashboard pre-loaded) | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| Actuator health/metrics | http://localhost:8080/actuator/health, `/actuator/prometheus` |

Run the test suite with `./mvnw.cmd test` — 24 tests covering the rules
engine, anomaly scoring, prompt building, and the investigation agent's
retry/fallback behavior (LLM calls mocked).

## Architecture

```
TransactionEvent (Kafka "transactions" topic, keyed by accountId)
        │
        ▼
TransactionConsumerService — persists the raw transaction
        │
        ├──▶ RulesEngineService        — velocity / deviation / high-value checks
        │
        └──▶ AnomalyScoringService     — z-score vs. account's own rolling history
        │
        ▼
   combinedScore = max(ruleScore, zScoreComponent)
   flagged = a rule fired  OR  combinedScore ≥ flag-threshold
        │
        ▼
FraudCase opened (status OPEN) ── publishes FraudCaseOpenedEvent
        │                              │
        │                              ▼
        │                    Kafka "fraud-cases-opened" topic
        │                              │
        │                              ▼
        │                    InvestigationAgentService (separate consumer group)
        │                              │
        │                    embed query ──▶ pgvector similarity search (case_notes)
        │                              │
        │                    build prompt ──▶ AnthropicClient (retry + backoff)
        │                              │
        │                    parse {riskScore, reasoning, recommendedAction}
        │                              │
        │              ┌───────────────┴───────────────┐
        │              ▼                                ▼
        │         REVIEWED                    INVESTIGATION_FAILED
        │       (report saved)              (error saved, never silent)
        ▼
Case Review Dashboard (polls /api/cases/all every 5s) ── analyst approves/dismisses
                                                          → CONFIRMED_FRAUD / DISMISSED
```

Everything is also emitting Micrometer metrics (`sentinel.transactions.ingested`,
`sentinel.cases.opened`, `sentinel.ai.agent.latency`) scraped by Prometheus and
rendered on the pre-provisioned Grafana dashboard.

## Design decisions

### Why the rules engine runs before the AI layer

Cheap, deterministic checks catch the obvious cases and give the system an
audit trail an auditor can actually verify — "velocity: 7 transactions in the
last minute" is something you can check by hand. The AI layer is reserved for
the genuinely ambiguous cases, where reasoning over context (account history,
similar past cases) adds value over a threshold. This mirrors how real fraud
teams are structured, and it's deliberate: new deterministic signals belong in
`RulesEngineService`/`AnomalyScoringService`, not downstream in the agent.

### Why the AI investigation agent is async, off its own Kafka topic

The alternative was `@Async` — simpler to wire, but in-memory: if the app
restarted between opening a case and the async method running, the
investigation would just vanish, with no record it was ever supposed to
happen. Publishing a `FraudCaseOpenedEvent` to a dedicated topic instead means
the investigation agent is a fully independent consumer group. It can crash,
restart, or fall behind without losing anything — the event sits in Kafka
until it's processed — and a 2–5 second LLM call never adds latency to the
transaction-ingestion consumer group's lag. It also reuses the event-driven
pattern already established for ingestion rather than introducing a second
concurrency model.

### Why case-note embeddings are computed locally instead of via a hosted API

Anthropic doesn't offer an embeddings endpoint, and the "obvious" choices
(OpenAI, Voyage AI) both mean a second API key, a second account, and a
second dependency for what's fundamentally a demo-scale retrieval problem —
70 seed notes and one query at a time. `EmbeddingService` instead uses a
deterministic feature-hashing embedding (bag-of-words tokens hashed into
fixed buckets, signed, L2-normalized) — the honest tradeoff is that it
captures shared vocabulary, not real semantic meaning, so two case notes
describing the same fraud pattern in very different words won't necessarily
land close together. For this project's synthetic notes, written with
consistent per-category vocabulary, retrieval still works well. A production
system would swap this for a real embedding model without touching anything
else — `CaseNoteRepository` only cares that it gets a `float[]` of a fixed
length back.

### Why `case_notes` is plain JDBC instead of Spring Data JPA

Hibernate has no built-in mapping for Postgres's `vector` column type. Rather
than pull in a Hibernate `UserType` for one table, `CaseNoteRepository` uses
`JdbcTemplate` directly — raw SQL, including pgvector's `<=>` cosine-distance
operator for top-k retrieval. It's the one place in the app that isn't a
`JpaRepository`, deliberately.

### A real bug this surfaced: `ddl-auto: update` and enum columns

Adding `CaseStatus.INVESTIGATION_FAILED` in Phase 3 didn't get picked up by
Hibernate's `ddl-auto: update` on an already-existing table — Postgres had
already generated a CHECK constraint listing only the enum values that
existed at first-create time, and `update` mode never revisits constraints on
existing columns. Every save with the new status started failing, which
threw out of the Kafka listener, which triggered Spring Kafka's default
redelivery — up to 10 attempts before giving up and permanently skipping the
message. A handful of cases got stuck mid-investigation before this was
caught. The constraint was fixed by hand for local dev; a production system
would use Flyway or Liquibase migrations specifically to avoid this class of
bug — `ddl-auto: update` is a Phase-1-speed shortcut, not something to trust
for schema changes that touch existing enum-backed columns.

### Why the dashboard is a single static HTML file, not React

`src/main/resources/static/index.html` — vanilla JS, `fetch()`, no build
step. Spring Boot serves it automatically at `/`. Given the scope (a table,
two buttons, 5-second polling), a React/Vite toolchain would add a build
step and a second `npm` dependency tree for no real functional gain. This
was an explicit "optimize for speed of delivery" call, not an oversight.

### Other tradeoffs worth naming

- **Grafana runs with anonymous viewer access enabled** (`GF_AUTH_ANONYMOUS_ENABLED`)
  so `localhost:3000` is immediately useful without a login step — fine for
  local dev, not something you'd ship.
- **The JDK-version pins in `pom.xml`** (Lombok 1.18.46, Mockito 5.23.0,
  Byte Buddy 1.17.7, all bumped above Spring Boot 3.3.2's managed defaults)
  exist because this environment runs a JDK newer than those defaults were
  built against — annotation processing and Mockito's inline mock maker both
  failed outright without the bump. Worth knowing about if this project ever
  moves to an older JDK: those overrides could likely come back out.
- **The simulator is a blunt instrument** — five fixed accounts, a flat 8%
  anomaly rate — good enough to exercise every code path locally, not a
  substitute for real transaction data or a proper load test.

## Tech stack

Java 21 · Spring Boot 3.3 · Spring Kafka · Spring Data JPA · PostgreSQL +
pgvector · Anthropic Claude API · Micrometer/Prometheus/Grafana · Lombok ·
Docker Compose
