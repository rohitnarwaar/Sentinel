# Sentinel — technical design notes

This is the companion to `Sentinel-Requirements-Specification.docx`. That doc says
*what* the system does and *why* it exists. This one is about *how it's actually
built* — the code-level stuff, the parts that would come up if someone opened the
repo and started asking "wait, why did you do it this way." I'm not going to
re-explain anything that's already in the requirements doc (the failure-mode
write-ups, the config table, the FR numbers, the design-decision rationale) — go
there for that. This is more like the notes I'd leave for myself if I came back to
this in six months and forgot how half of it works.

Not going to pretend this is a clean, top-down design that I planned out in advance
either. Some of it is. Some of it I backed into after something broke. I'll say
which is which where it matters.

---

## Kafka wiring — the actual beans

There are two completely separate producer/consumer factory pairs in `KafkaConfig`,
one per topic. I didn't try to be clever and share a generic factory parameterized
by type — Spring Kafka's factories are typed per value class anyway (`ProducerFactory<String,
TransactionEvent>` vs `ProducerFactory<String, FraudCaseOpenedEvent>`), so trying to
genericize it would've just meant more reflection-y config-building code for no real
benefit. Copy-paste the config block, change the topic-specific bits. Fine.

```java
@Bean
public ConsumerFactory<String, FraudCaseOpenedEvent> caseEventConsumerFactory() {
    Map<String, Object> config = new HashMap<>();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    config.put(ConsumerConfig.GROUP_ID_CONFIG, "sentinel-investigation");
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.sentinel.dto");
    config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, FraudCaseOpenedEvent.class.getName());
    return new DefaultKafkaConsumerFactory<>(config);
}
```

One thing worth flagging honestly: the investigation listener factory is set to
`concurrency(2)` and the ingestion one to `concurrency(3)`. Neither of those numbers
actually buys real parallelism right now, because both topics only ever have a
single partition (default broker config, never touched it). Kafka can't hand out
more partitions than exist, so of those 2 or 3 listener threads, only one ever gets
assigned work — the rest just sit idle. I left the concurrency settings in anyway
because bumping the partition count later is a one-line docker-compose change plus
these numbers already being right, versus having to remember to add concurrency
settings I'd forgotten about. Small thing, but it's the kind of detail that looks
wrong if you don't know why it's there.

`ENABLE_IDEMPOTENCE_CONFIG = true` + `ACKS_CONFIG = "all"` on both producers — this
is the standard "don't duplicate or lose messages" pair. Given the whole point of
using Kafka here instead of a plain HTTP call is durability (a case published to
`fraud-cases-opened` should survive a restart), turning idempotence off would've
undercut the actual reason the topic exists.

---

## Rules engine + anomaly scorer — how the numbers actually get produced

The rules engine (`RulesEngineService.evaluate()`) is a dumb chain — velocity, then
deviation, then high-value, first one to fire wins and short-circuits the rest. No
scoring, no weighting between rules, just "did any single check trip." That's
deliberate simplicity, not a placeholder for something smarter later — a fraud rule
that only sometimes fires because it got outvoted by other rules is a much harder
thing to explain to an auditor than "rule X fired, here's why."

The anomaly scorer is more interesting because of the edge cases it has to eat.
Here's the actual stats:

```java
private double stdDev(double[] values, double mean) {
    if (values.length < 2) {
        return 0.0;
    }
    double sumSquaredDiff = 0.0;
    for (double v : values) {
        double diff = v - mean;
        sumSquaredDiff += diff * diff;
    }
    // Sample standard deviation (n-1): history is a sample used to estimate
    // the account's true spending distribution, not the whole population.
    return Math.sqrt(sumSquaredDiff / (values.length - 1));
}
```

Sample stddev (n-1), not population stddev (n). The account's last 20 transactions
are a sample of its "true" spending behavior, not the entire universe of
transactions it will ever make, so n-1 is the statistically correct call. Small
thing, but it's the kind of detail that's wrong in a lot of hand-rolled stats code
I've seen.

The part I actually had to think about was what happens when `stdDev == 0` — every
past transaction for the account was for the exact same amount. A z-score is
literally undefined there (division by zero), and I didn't want to just catch a
`NaN` downstream and hope for the best. What it does instead:

```java
if (stdDev == 0.0) {
    boolean matches = amount == mean;
    return new AnomalyResult(matches ? 0.0 : Double.POSITIVE_INFINITY, matches ? 0.0 : 1.0,
            matches
                    ? "matches account's constant transaction history"
                    : "deviates from account's previously constant transaction amount (always %.2f)"
                            .formatted(mean));
}
```

If the new amount matches the constant history exactly, score 0. If it's off by
even a cent, score 1.0 (max) — and the "z-score" field on the result is literally
`Double.POSITIVE_INFINITY`, which is a bit of a hack but it's honest: the deviation
really is infinite relative to a distribution with zero variance. I went back and
forth on whether to just return `0.0` there to keep the type "normal," but decided
a sentinel value that's honestly weird is better than a fake normal-looking number
that isn't meaningfully comparable to a real z-score.

`AnomalyScoringService` also has a package-private overload — `score(txn, history)`
— alongside the public `score(txn)` that hits the repository. That split exists
purely for tests: the unit tests call the two-arg version directly with a
hand-built `List<Transaction>` so they don't have to mock a repository just to hand
it a canned list. Same pattern shows up in a couple other services in the codebase.
It's a small thing but it made the tests a lot less annoying to write.

---

## The investigation agent — walking through `consume()` top to bottom

This is the one method in the whole app I rewrote the most times, so it's worth
walking through exactly what it does and why it's shaped this way.

```java
FraudCase fraudCase = fraudCaseRepository.findById(event.getCaseId()).orElse(null);
Transaction txn = transactionRepository.findById(event.getTransactionId()).orElse(null);

if (fraudCase == null || txn == null) {
    log.error("Cannot investigate case {} — case or transaction not found (transaction {})",
            event.getCaseId(), event.getTransactionId());
    return;
}
```

`.orElse(null)` instead of `.orElseThrow()`. I thought about this one. Throwing
would be more idiomatic Spring, but a missing case/transaction here isn't really an
*exceptional* condition worth an exception + stack trace + whatever the default
Kafka error-handling would do with it (redeliver forever, most likely, same as the
constraint-violation loop I ran into elsewhere — see the requirements doc). It's a
"this shouldn't happen, but if it does, log it and move on to the next message"
situation, so a null-check-and-return felt more honest than dressing it up as an
exception.

Then:

```java
fraudCase.setStatus(CaseStatus.INVESTIGATING);
fraudCaseRepository.save(fraudCase);
caseEventBroadcaster.broadcast(FraudCaseSummary.of(fraudCase, txn));

Timer.Sample sample = Timer.start(meterRegistry);
try {
    AiInvestigationReport report = investigate(fraudCase, txn);
    ...
} catch (Exception e) {
    ...
} finally {
    sample.stop(meterRegistry.timer("sentinel.ai.agent.latency"));
    fraudCaseRepository.save(fraudCase);
    caseEventBroadcaster.broadcast(FraudCaseSummary.of(fraudCase, txn));
}
```

Two things worth calling out here:

The `INVESTIGATING` save+broadcast happens *before* the try block, not inside it. If
that first save somehow throws, I want that to propagate up and let Kafka's normal
retry mechanics handle it, rather than silently swallowing it as if the investigation
"failed" when it never actually started. The try/catch is specifically wrapping the
*investigation* (the part that can fail for AI-specific reasons: bad JSON, network
timeout, whatever), not the whole method.

The `finally` block is doing double duty — it stops the latency timer *and* it's the
only place that persists the terminal status, on both the success and failure paths.
I like this because there's no way to add a new failure path later and forget to
save/broadcast — anything that reaches `finally` gets recorded, full stop. The
`catch (Exception e)` is intentionally broad (not `catch (LlmClientException e)` or
similar) because I want *anything* that goes wrong in `investigate()` — JSON parsing,
a null pointer I didn't anticipate, whatever — to land on `INVESTIGATION_FAILED`
instead of taking down the Kafka listener thread.

`parseReport()` is package-private, not private, purely so the unit test can call it
directly with hand-crafted strings (including the markdown-fenced-JSON case) without
having to go through the whole `consume()` flow just to test a regex:

```java
AiInvestigationReport parseReport(String rawResponse) throws Exception {
    String cleaned = rawResponse.trim();
    if (cleaned.startsWith("```")) {
        cleaned = cleaned.replaceFirst("^```(json)?", "").replaceFirst("```$", "").trim();
    }
    return objectMapper.readValue(cleaned, AiInvestigationReport.class);
}
```

This regex exists because both Claude and local Ollama models will sometimes wrap
their JSON output in a markdown code fence even when told explicitly not to
("respond with ONLY a JSON object... no markdown fences"). Models don't always
listen. Stripping a leading/trailing ``` ``` `` block (optionally tagged `json`)
before handing it to Jackson turned a meaningful chunk of "successful call, failed
parse" cases into successful parses.

---

## `AbstractRetryingLlmClient` — template method, basically

`AnthropicClient` and `OllamaClient` both extend this instead of each rolling their
own retry loop. The shared class owns `complete()` (marked `final` — subclasses
can't override it and accidentally skip the retry logic) and delegates the actual
HTTP call to an abstract `callOnce()`:

```java
@Override
public final String complete(String systemPrompt, String userPrompt) {
    Duration backoff = initialBackoff;
    RuntimeException lastError = null;

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            return callOnce(systemPrompt, userPrompt);
        } catch (RestClientException e) {
            lastError = e;
            log.warn("{} call failed (attempt {}/{}): {}", providerName(), attempt, maxAttempts, e.getMessage());
            if (attempt < maxAttempts) {
                sleep(backoff);
                backoff = backoff.multipliedBy(2);
            }
        }
    }
    throw new LlmClientException(providerName() + " call failed after " + maxAttempts + " attempts", lastError);
}
```

This is textbook Template Method pattern, not that I sat down and said "I shall now
apply the Template Method pattern" — I just didn't want to copy-paste a retry loop
into a second class when I added Ollama as a provider. Only catching
`RestClientException` specifically is worth noting: that's what `RestClient` throws
for both non-2xx responses *and* connection failures (`ResourceAccessException`,
which is a `RestClientException` subtype) — so "Ollama isn't running" and "Anthropic
returned a 401" both get the same retry treatment, which is what I want. A
`JsonProcessingException` from a malformed LLM response bubbles up separately and
isn't retried at this layer at all — that's handled one level up by
`InvestigationAgentService`'s catch-all, which just marks the whole investigation
failed rather than retrying the HTTP call again for a problem that a retry can't
fix (the model said the same wrong thing, retrying won't make it say something
different — well, actually it might, since these aren't deterministic, but that's a
different retry policy than "the network hiccuped," and I didn't want to conflate
the two).

---

## pgvector — the actual SQL, and why there's a string-building method

The similarity query:

```sql
SELECT narrative, label, embedding <=> ?::vector AS distance
FROM case_notes
ORDER BY distance ASC
LIMIT ?
```

`<=>` is pgvector's cosine distance operator — lower is more similar, 0 is an exact
match. Nothing fancy there. The part that's a little ugly is how the query
parameter actually gets bound. The JDBC driver has no native binding for pgvector's
`vector` type, so you can't just pass a `float[]` as a normal parameter — it has to
go in as a string literal in Postgres's array-literal syntax (`[0.12,-0.4,0.91,...]`)
and get cast inline with `::vector`. Hence:

```java
private static String toVectorLiteral(float[] embedding) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < embedding.length; i++) {
        if (i > 0) {
            sb.append(',');
        }
        sb.append(embedding[i]);
    }
    return sb.append(']').toString();
}
```

128 floats, comma-joined, done. I looked briefly at whether the official
`pgvector-java` driver extension would give a cleaner binding path, but pulling in
another dependency just to avoid a 10-line string builder for one repository felt
like the wrong trade. This whole class is already the one place in the app that
breaks from Spring Data JPA on purpose — might as well keep it self-contained.

## The embedding function itself

This is the part of the codebase I'd expect the most pushback on in a review, so
worth walking through exactly what it does, since "local embedding" can mean a lot
of different things and this is a pretty unglamorous one:

```java
String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ");
for (String token : normalized.split("\\s+")) {
    if (token.isBlank()) continue;
    int hash = token.hashCode();
    int index = Math.floorMod(hash, DIMENSIONS);
    float sign = ((hash >>> 31) == 0) ? 1f : -1f;
    vector[index] += sign;
}
return normalize(vector);
```

This is the "hashing trick" — lowercase the text, strip punctuation, split on
whitespace, and for every token, hash it into one of 128 buckets and add either +1
or -1 to that bucket depending on a bit of the hash. The sign bit is there
specifically so that two *different* words that happen to collide into the same
bucket (guaranteed to happen eventually with only 128 buckets and English
vocabulary) partially cancel out instead of just stacking — doesn't fully solve hash
collisions, but it's cheap and it measurably helps versus not doing it. After every
token's been folded in, the whole 128-dim vector gets L2-normalized (divide every
component by the vector's magnitude) so cosine distance behaves sensibly regardless
of how long the input text was.

What this is *not*: a real embedding model. It has zero notion that "card testing"
and "rapid small authorizations" are related concepts — it only "knows" about exact
token overlap. Two case notes describing the same fraud pattern in different words
won't cluster together. This works fine for this project specifically because I
wrote all 70 seed narratives myself with pretty consistent vocabulary per category
("velocity," "beneficiary," "geo mismatch" etc. show up over and over within a
category), so token-overlap retrieval actually does something useful. It would fall
over fast on real, varied, human-written fraud notes. I'm not pretending otherwise —
see the requirements doc for the fuller version of this trade-off, this section is
just about the mechanics of *how* it hashes, not *why* it's local.

---

## `FraudCaseSummary` and the batching

The one query worth showing here is the batched transaction lookup in
`CaseController.all()`, since it's the actual fix for the fetch-storm thing (full
incident write-up is in the requirements doc — this is just the code):

```java
List<FraudCase> page = fraudCaseRepository.findMostRecentlyActive(PageRequest.of(0, 100));
Map<String, Transaction> txnById = transactionRepository
        .findAllById(page.stream().map(FraudCase::getTransactionId).distinct().toList())
        .stream()
        .collect(Collectors.toMap(Transaction::getTransactionId, Function.identity()));
return page.stream()
        .map(c -> FraudCaseSummary.of(c, txnById.get(c.getTransactionId())))
        .toList();
```

`findAllById` on a `JpaRepository` generates a single `SELECT ... WHERE id IN (...)`
— that's the whole trick. 100 cases, one extra query, not 100. The `.distinct()`
before the `findAllById` call isn't strictly necessary (a `WHERE id IN (...)` with
duplicate values is harmless) but it keeps the `IN` clause from being needlessly
padded if, say, two cases somehow reference the same transaction (shouldn't happen
given how cases get created, but costs nothing to guard against).

`FraudCaseSummary.of()` is a static factory rather than a constructor, mostly
because the transaction argument can legitimately be null (defensive — a case's
linked transaction should always exist, but I didn't want a null pointer here to be
the thing that takes down a dashboard request if that assumption is ever wrong) and
a static factory reads better than a constructor with a comment explaining "yes,
the second arg can be null, that's fine."

---

## `CaseEventBroadcaster` — why `CopyOnWriteArrayList`

```java
private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
```

This is a genuine "know your data structure" choice, not a default I reached for
without thinking. `CopyOnWriteArrayList` is expensive to write to (every add/remove
copies the whole backing array) and cheap to iterate, with no locking needed on
read. That's exactly backwards from what you'd want for, say, a list with thousands
of writes per second — but this list holds one entry per *connected browser tab*,
which for a project like this is realistically single digits, and it gets iterated
on every single broadcast (every case status change), which happens far more often
than a tab opens or closes. Optimizing for cheap reads over cheap writes is the
right call here specifically because of that access pattern. If this were backing,
say, thousands of concurrent SSE subscribers, I'd reach for something else
entirely.

The dead-emitter cleanup in `broadcast()` collects failures into a second list
instead of removing from `emitters` while iterating it:

```java
List<SseEmitter> dead = new CopyOnWriteArrayList<>();
for (SseEmitter emitter : emitters) {
    try {
        emitter.send(...);
    } catch (IOException | IllegalStateException e) {
        dead.add(emitter);
    }
}
emitters.removeAll(dead);
```

`CopyOnWriteArrayList`'s iterator is actually safe against concurrent modification
(it iterates a snapshot), so this two-pass approach isn't strictly required to avoid
a `ConcurrentModificationException` the way it would be with a plain `ArrayList` —
but removing mid-iteration would still mean the snapshot the iterator's holding
doesn't reflect the removal, and I'd rather the code read unambiguously correct
than rely on knowing that particular implementation detail. Explicit two-pass, done.

---

## The frontend — this is the part the requirements doc barely touches

Everything above is backend. The dashboard (`static/index.html`) has its own
internal design that's genuinely nontrivial and worth documenting properly, since
"the dashboard shows cases live" doesn't explain any of the actual mechanics.

### State: one `Map`, nothing else

```js
const cases = new Map();           // caseId -> case
```

That's it. No framework state management, no Redux-alikes, just a `Map` keyed by
case ID that gets mutated by three different sources — the initial REST snapshot,
incoming SSE events, and the 30-second background resync — and a render pass that
always reads from the current state of that one `Map`. Every render function
(`renderQueue`, `renderPanel`, the two charts, the stat counters) independently
calls `Array.from(cases.values())` and derives whatever it needs. Nothing caches a
derived view that could go stale — it's all recomputed from the one source of truth
every time. Given the data volume here (low hundreds of cases, not millions), that's
cheap enough not to bother optimizing.

Sorting/trimming is centralized in one helper:

```js
function activityTime(c) { return new Date(c.updatedAt || c.createdAt).getTime(); }
function sortedCases() {
  return Array.from(cases.values()).sort((a, b) => activityTime(b) - activityTime(a));
}
```

Falls back to `createdAt` if `updatedAt` isn't there — leftover defensiveness from
when the backend added that field and old rows didn't have it yet (again, see the
requirements doc for that whole saga). Doesn't hurt to leave it in.

The map gets trimmed to the newest 120 entries after every write, matching (with a
little slack) the backend's own top-100 window, so the client doesn't slowly
accumulate every case it's ever seen across a long-running tab:

```js
const sorted = sortedCases();
if (sorted.length > 100) {
  for (const stale of sorted.slice(100)) cases.delete(stale.caseId);
}
```

### The report "streaming" reveal

This is the one bit of frontend state that's genuinely fiddly, so it gets its own
`Map`:

```js
const reveal = new Map(); // caseId -> { words, shown, timer, animated }
```

The AI report doesn't actually stream token-by-token from the backend — the backend
returns the whole parsed report in one shot once the LLM call finishes. The
word-by-word reveal in the UI is a purely client-side animation layered on top of
already-complete text, and I was careful about when it plays: a case that's
*already* `REVIEWED` when the page first loads shows its report instantly, no
animation. A case that transitions to `REVIEWED` while the tab is open — i.e., the
report genuinely just arrived — gets the animated reveal. That distinction is what
`snapshotLoaded` and the `fromSnapshot` flag on `upsertCase` are for: don't replay
old news as if it were happening live.

```js
function startReveal(caseId) {
  if (reveal.get(caseId)?.timer) return;
  const tick = () => {
    const entry = reveal.get(caseId);
    if (!entry) return;
    if (entry.shown >= entry.words.length) {
      clearInterval(entry.timer);
      entry.timer = null;
      renderQueue(); if (selectedId === caseId) renderPanel();
      return;
    }
    entry.shown = Math.min(entry.words.length, entry.shown + REVEAL_WORDS_PER_TICK);
    if (selectedId === caseId) renderPanel();
  };
  reveal.get(caseId).timer = setInterval(tick, REVEAL_MS);
}
```

`REVEAL_MS = 55`, 3 words per tick — that's just a pace that looked right, no
science behind those two numbers, I tweaked them by eye until it didn't feel too
slow or too twitchy. Each case gets its *own* `setInterval`, which means if three
cases finish investigation around the same time, there are three independent
timers ticking. That's fine at this scale; I wouldn't do it this way if hundreds of
cases could be streaming simultaneously, but the realistic ceiling here is "however
many the investigation agent can process concurrently," which given the whole
pipeline's throughput is small.

Worth noting: the per-tick callback only calls `renderPanel()`, not `renderQueue()`
— the full case list only gets re-rendered once, when the reveal *finishes*. That
was a deliberate call after running into a version that re-rendered the whole queue
on every tick and noticeably chugged with more than a couple of cases streaming at
once.

### `scheduleRender()` — coalescing bursts

```js
let renderQueued = false;
function scheduleRender() {
  if (renderQueued) return;
  renderQueued = true;
  requestAnimationFrame(() => {
    renderQueued = false;
    renderCounters(); renderFilters(); renderQueue();
    renderVolumeChart(); renderRiskChart();
    if (selectedId) renderPanel();
  });
}
```

If five SSE events land in the same tick of the event loop (plausible — the backend
sometimes broadcasts a burst when several investigations finish close together),
this collapses them into a single render pass on the next animation frame instead
of doing five full DOM rebuilds back to back. Cheap, standard debounce-ish pattern,
nothing clever about it, but it's the kind of thing that's easy to skip and then
regret once real traffic hits the page.

### The charts are hand-rolled SVG, not a library

Both charts (case volume trend, risk-score histogram) build raw SVG strings and
inject them via `innerHTML`. No charting library. For two chart types this simple
it didn't seem worth a dependency, and it means the charts pick up the exact same
color tokens as the rest of the page for free, since they're just CSS custom
properties read at render time.

The one bit of actual geometry worth explaining is the score rings (the little
circular progress indicators on each case card and the big one in the case panel)
— same trick both places, just different radius:

```js
const c = small ? CIRCUMF_SMALL : CIRCUMF_BIG;   // 2 * PI * r
const dash = (c * score / 100).toFixed(1) + ' ' + c.toFixed(1);
```

`stroke-dasharray` on an SVG circle takes a "dash length, gap length" pair. Set the
dash length to `score% of the full circumference` and the gap to the rest of the
circumference, and you get a ring that's visually filled to `score%` around — it's
just an arc-length calculation, nothing built into SVG that does "progress ring"
natively. The circle also gets `transform="rotate(-90 ...)"` so the fill starts at
12 o'clock instead of 3 o'clock, which is the direction people actually expect a
progress indicator to start from.

The risk histogram reuses the *exact same* `riskColor(score)` function that colors
the rings and the status pills elsewhere on the page — deliberately, so the chart's
color-coding means the same thing everywhere on screen instead of introducing a
second, unrelated color scale just for that one chart.

### The ticker's scroll loop

Small trick, but easy to miss reading the HTML: the ticker's marquee content is the
same list of transactions rendered *twice* back to back:

```js
el('tickerTrack').innerHTML = html + html;
```

paired with a CSS animation that translates the track by exactly -50% and loops. If
you only render the list once and animate it off-screen, there's a visible gap
before it "restarts." Doubling the content and only ever scrolling through the
first half's worth of distance means the second copy is always right there to pick
up seamlessly — a pretty standard CSS marquee trick, not something I invented, but
worth documenting since it's not obvious from just reading the HTML why the ticker
items appear to render twice.

---

## Build tooling — the actual pom.xml mechanics

The version overrides in `pom.xml`:

```xml
<properties>
    <java.version>21</java.version>
    <lombok.version>1.18.46</lombok.version>
    <mockito.version>5.23.0</mockito.version>
    <byte-buddy.version>1.17.7</byte-buddy.version>
</properties>
```

These work because Spring Boot's parent POM manages dependency versions through
Maven *properties* with these exact names, not hardcoded version numbers in the BOM
itself — so redeclaring the property in the child POM overrides what the parent
would've used, without needing a `<dependencyManagement>` override block. I didn't
know that mechanism cold going in; figured it out from how Spring Boot's own BOM is
structured (it's just a giant property list feeding version references) once
pinning Lombok alone didn't fix the Mockito/Byte Buddy problem — Byte Buddy is
pulled in transitively by Mockito, and Spring's BOM pins *it* separately via its own
property, so bumping Mockito's version alone wasn't enough; had to bump both
explicitly, same mechanism, different property name.

```xml
<parameters>true</parameters>
```

on the compiler plugin — this one's easy to overlook and it'll bite you in a
specific, confusing way if it's missing: Jackson can deserialize a Java `record`
via reflection, but only if it can see the constructor parameter *names* in the
compiled bytecode, which javac doesn't emit by default (you get `arg0`, `arg1`,
etc. instead of `riskScore`, `reasoning`). Without this flag, `AiInvestigationReport`
would fail to deserialize with an error that doesn't obviously point at "add
`-parameters`" — it just looks like Jackson can't figure out how to construct the
type.

---

## Docker Compose — a couple of specifics

Kafka's running in KRaft mode (no separate Zookeeper container) as a single node
playing both broker and controller roles:

```yaml
KAFKA_PROCESS_ROLES: broker,controller
KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
```

Fine for local dev, obviously not how you'd run this for real (KRaft wants an odd
number of controller nodes ≥3 for actual fault tolerance — one node has no
tolerance for anything).

Postgres is on host port 5433, not the standard 5432 — that's a real environment
detail, not an arbitrary choice, but the story behind *why* belongs in the
requirements doc, not here. This file's just noting the number is deliberate, not
a typo.

Grafana's provisioning is two bind-mounted directories, not manual dashboard setup:

```yaml
volumes:
  - ./observability/grafana/provisioning:/etc/grafana/provisioning
  - ./observability/grafana/dashboards:/etc/grafana/dashboards
```

The first mount tells Grafana where to find its datasource config (points at the
Prometheus container by service name) and where dashboard JSON files live; the
second is where the actual dashboard JSON sits. Point being: `docker compose up`
gives you a fully wired Grafana with the dashboard already there, zero clicking
through the UI to set it up by hand.

---

## Rough edges, honestly

Things I know aren't great and haven't fixed:

- The reveal-animation `Map` (`reveal`) and the case-data `Map` (`cases`) both key
  off `caseId` but never get cleaned up in a coordinated way — a case gets evicted
  from `cases` when the list trims to 120, but if it still has a `reveal` entry with
  a live timer, that timer does get cleared (there's cleanup code for it), but it's
  two separate maps doing what's conceptually one piece of per-case client state.
  Should probably be a single `Map<caseId, {case, revealState}>` instead of two
  parallel ones. Works fine as-is, just not as clean as it could be.
- No frontend tests at all. The backend has 24, the frontend has zero — I leaned on
  manually running the app and clicking through it (a lot, this session especially)
  instead. For a project this size that's a defensible trade, but it's not
  something I'd want to say out loud on a team with actual frontend test
  conventions.
- The SVG chart code and the score-ring code both compute a `stroke-dasharray` from
  a circumference, but they're two separate inline implementations rather than one
  shared helper. Small duplication, never bothered consolidating it.
