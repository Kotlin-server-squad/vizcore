# Phase 1: Foundation & Production Readiness — Research

**Researched:** 2026-06-11
**Domain:** Kotlin/Ktor backend repair + frontend contract fix
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** Big-bang delete — remove the entire forked `backend/src/main/kotlin/com/jh/proj/coroutineviz/session/` subtree in one change, fix compilation against `coroutine-viz-core`, and prove parity with the test suite plus the audit's headless REST checks (scenario run → snapshot shape unchanged).
- **D-02:** Any fork-only behavior discovered during the delete surfaces as compile/test failure and is reconciled *into core* (core is the single source of truth — CONCERNS.md already assessed core as "more complete").
- **D-03:** Explicit `SerializersModule` — one module file registering every `@Serializable` `VizEvent` subclass (all packages: coroutine, job, flow, dispatcher, deferred, channel, sync) in `polymorphic(VizEvent::class) { … }`, installed on the server's Json (ContentNegotiation + SSE serialization path).
- **D-04:** Guard against future drift with a registration-completeness test: reflect over (or enumerate) all `@Serializable` VizEvent subtypes and assert each is resolvable in the polymorphic scope — a newly added event type that isn't registered must fail CI, not production.
- **D-05:** Do NOT seal the hierarchy — sealing would force all event classes into one package (large refactor, deferred).
- **D-06:** JVM flag selection — `-Dlogback.configurationFile=…/logback-prod.xml` set in the Dockerfile/compose-prod ENTRYPOINT. No janino conditional config, no separate image stage. Dev runs remain on the default `logback.xml`.
- **D-07:** Add a `PrometheusMeterRegistry` and a `/metrics` scrape endpoint alongside the existing `LoggingMeterRegistry`, and wire the five missing ADR-020 metrics into both. This pre-positions Phase 4 observability with ~20 lines + 1 dependency.
- **maxEvents:** default **10000** via application.yaml key (core EventStore already defaults to 10_000)
- **/api/health + /api/live + /api/ready** canonical, /health kept as alias
- **FIX-02** resolved on the FRONTEND (ValidationPanel consumes real `{sessionId, results[], timing}` shape); backend contract unchanged

### Claude's Discretion
- Health `version` field source (build-generated vs manifest), exact Ktor module layout for the SerializersModule install, metric naming details within ADR-020's set, test-host vs integration-test style per existing TESTING.md conventions.

### Deferred Ideas (OUT OF SCOPE)
- supervisorScope in User Registration + real withTimeout in Report Generation — scenario-semantics polish batch (post-Phase-1)
- "Simulate failure" toggle in Scenario Controls — Phase 2 frontend work
- VizScope Job context + honest `success:false` on failed scenario runs — rides with the scenario-polish batch
- Scenario card duration-text corrections; Deep Nesting "5 levels" off-by-one — cosmetic backlog
- `X-Accel-Buffering: no` SSE header (ADR-020, noted in CONCERNS.md) — Phase 4 SSE/perf work
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| FIX-01 | VizEvent polymorphic SerializersModule + ContentNegotiation/SSE wiring | §FIX-01 section: complete 66-class inventory, two Json instances identified, install point in Serialization.kt + SessionRoutes.kt |
| FIX-02 | Frontend ValidationPanel/ValidationResult adaptation to real `{results[], timing}` contract | §FIX-02 section: exact backend shape documented, frontend type mismatch lines identified, test fixture changes mapped |
| FIX-03 | VizScope cause-type classification (not message-contains-label) | §FIX-03 section: exact line + fix pattern verified in VizScope.kt:182 |
| FIX-04 | Cancellation scenario targeted child cancel | §FIX-04 section: exact lines to uncomment/delete in ScenarioRunner.kt:117,124 |
| FND-01 | Delete backend/src/main session fork; compile against core only | §FND-01 section: 10-file inventory, wildcard import list, compilation risk analysis |
| FND-02 | Bounded EventStore wiring via application.yaml + SessionManager.configure() | §FND-02 section: core SessionManager.configure(maxEventsPerSession) signature verified |
| FND-03 | Regression test asserting store.all().size <= maxEvents | §Validation Architecture: test pattern via existing testApplication/runScenario infrastructure |
| PROD-01 | /api/health + /api/live + /api/ready + /health alias | §PROD-01 section: existing HealthRoutes.kt structure, version field options |
| PROD-02 | Logback prod-profile selection wired in Dockerfile/compose-prod | §PROD-02 section: logstash-logback-encoder dependency gap identified, D-06 JVM flag wiring |
| PROD-03 | CORS config test (behavior unchanged, just add a test) | §PROD-03 section: HTTP.kt already config-driven; test-host pattern from TESTING.md |
| PROD-04 | OpenAPI spec validates; health + validate schemas updated | §PROD-04 section: spec is 2270-line hand-maintained YAML; paths to update identified |
| PROD-05 | Full ADR-020 7-metric set wired via Micrometer | §PROD-05 section: ADR-020 exact metric names, 5 missing metrics, MetricsWiring.kt wiring pattern |
</phase_requirements>

---

## Summary

Phase 1 repairs four high-severity runtime defects and completes the production surface of a brownfield Ktor 3.3 backend that is ~92% feature-complete but has several critical wiring gaps. The entire backend test suite is green (221/221) yet two of the four defects were invisible to the test suite because the tests mock or bypass the broken serialization and contract paths. All root causes are pinned with file:line evidence from the 2026-06-11 runtime walkthrough and scenario audit.

The dominant piece of work is FIX-01 + FND-01 together: the VizEvent hierarchy has 66 concrete `@Serializable` subclasses spread across 9 package groups, none of which are registered in a polymorphic `SerializersModule`, and the running server uses 10 forked session classes that shadow the authoritative `coroutine-viz-core` implementations. Deleting the fork (FND-01) and writing a `SerializersModule` (FIX-01) unblock SSE streaming, the Events tab, the conditional Channels/Flow/Sync/Jobs tabs, and the bounded EventStore (FND-02). The remaining items — two-line fix in VizScope, two-line fix in ScenarioRunner, three new health endpoints, log profile wiring, a CORS test, an OpenAPI update, and five Micrometer metrics — are individually small once the fork is gone.

**Primary recommendation:** Execute in order FIX-01 → FIX-03 → FIX-04 → FND-01 → FND-02/03 → PROD-01..05, with FIX-02 (frontend only) parallelizable. The FIX wave must complete before FND-01 because the fork's `EventContext` functions are imported by `VizScope.kt` — after deletion the same symbols resolve from core, which is identical behaviour.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| VizEvent polymorphic serialization | API / Backend (Ktor Serialization plugin) | — | ContentNegotiation + SSE route both need the same SerializersModule on the same Json instance |
| Validation contract fix | Frontend | — | Backend shape unchanged per D-02; only ValidationPanel + api.ts types change |
| VizScope completion handler | API / Backend (instrumentation wrappers layer) | — | `VizScope.kt` is Layer 1; fix is a single `when` branch change |
| Cancellation scenario | API / Backend (scenario runner) | — | `ScenarioRunner.kt` — 2-line change |
| Session fork deletion | API / Backend (session layer) | — | `backend/src/main/.../session/` subtree deleted; classpath resolves to core |
| Bounded event store wiring | API / Backend (Application startup) | Config (application.yaml) | `SessionManager.configure()` called from `Application.kt`; key in yaml |
| Health endpoints | API / Backend (routes) | — | New routes in `HealthRoutes.kt`; `/health` alias via redirect or route alias |
| Logback prod profile | CDN / Static (Docker/compose layer) | — | JVM flag in ENTRYPOINT; no backend code change needed |
| CORS config test | API / Backend (test layer) | — | Tests config read path in `HTTP.kt` |
| OpenAPI accuracy | API / Backend (spec file) | — | Hand-maintained `documentation.yaml` update |
| ADR-020 metrics | API / Backend (MetricsWiring.kt) | — | Extends existing `wireMetrics()` function |

---

## Standard Stack

### Core (all already in `backend/build.gradle.kts`) [VERIFIED: codebase grep]

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `io.ktor:ktor-serialization-kotlinx-json` | 3.3.2 | JSON serialization for ContentNegotiation + SSE | Already declared; provides `json()` DSL for ContentNegotiation |
| `kotlinx-serialization` | (transitive via Ktor) | `SerializersModule` + `polymorphic{}` DSL | The required polymorphic scope registration API |
| `io.micrometer:micrometer-registry-prometheus` | 1.6.13 | Prometheus metrics scrape | Already declared; `PrometheusMeterRegistry` already instantiated in Monitoring.kt |
| `io.ktor:ktor-server-metrics-micrometer` | 3.3.2 | Micrometer Ktor integration | Already declared; `MicrometerMetrics` plugin installed |
| `ch.qos.logback:logback-classic` | 1.4.14 | Logging | Already declared; logback-prod.xml exists |

### Supporting (new addition needed) [ASSUMED — Maven Central lookup confirmed version, authoritative source not consulted]

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `net.logstash.logback:logstash-logback-encoder` | 8.1 | JSON log encoding for `logback-prod.xml` | PROD-02 only — `logback-prod.xml` already references `LogstashEncoder` but this dep is absent from `build.gradle.kts` |

**Installation (only new dependency):**
```bash
# In backend/build.gradle.kts dependencies block:
implementation("net.logstash.logback:logstash-logback-encoder:8.1")
```

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Explicit `subclass(…)` registration in SerializersModule | Reflection scan at startup | Reflection scan is simpler to write but harder to test for completeness and adds startup overhead; explicit list + completeness test (D-04) is the locked decision |
| logstash-logback-encoder for prod JSON | janino conditional config | D-06 locks JVM flag approach; janino not needed |
| PrometheusMeterRegistry | LoggingMeterRegistry | D-07 locks Prometheus alongside existing Logging registry |

---

## Package Legitimacy Audit

No new npm/PyPI packages are introduced by this phase. The single new JVM dependency (`logstash-logback-encoder`) is a Maven artifact, not an npm package.

| Package | Registry | Age | Downloads | Source Repo | Verdict | Disposition |
|---------|----------|-----|-----------|-------------|---------|-------------|
| `net.logstash.logback:logstash-logback-encoder` | Maven Central | ~12 yrs | ~50M/month (central stats) | github.com/logfellow/logstash-logback-encoder | [ASSUMED] | Approved — well-known, industry-standard JSON logging encoder |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

*Note: `logstash-logback-encoder` is tagged `[ASSUMED]` because version was confirmed via Maven Central search API but not from official Ktor/Logback documentation. The package itself is an industry standard used by Spring Boot in production, but formal [VERIFIED] status requires an authoritative doc reference.*

---

## Architecture Patterns

### System Architecture Diagram

```
[Instrumentation Wrappers] ──emit VizEvent──► [VizSession.send()]
       VizScope.kt                                   │
       (FIX-03: invokeOnCompletion fix)      ┌───────┼──────────┐
                                             ▼       ▼          ▼
                                        EventStore  EventBus  Snapshot
                                        (FND-02:    (live     (projections)
                                        bounded     stream)
                                        ArrayDeque)
                                             │          │
                                    ┌────────┴──────────┘
                                    ▼
                         GET /api/sessions/{id}/events
                         GET /api/sessions/{id}/stream (SSE)
                              │
                         FIX-01: SerializersModule
                         Json.encodeToString(event)  ← currently crashes
                              │
                         Frontend useEventStream
                         → Events tab, Channels tab, etc.

[POST /api/validate/session/{id}]
    │ returns ValidationResponse{sessionId, results:List<ValidationResult>, timing}
    │ (backend shape unchanged)
    ▼
[Frontend ValidationPanel]  ← FIX-02: reads data.results / data.timing
    (currently reads data.errors / data.warnings → crash)

[/health] ──alias──► [/api/health]  ← PROD-01
[/api/live]
[/api/ready]

[Monitoring.kt] → wireMetrics(registry)  ← PROD-05 extends with 5 more metrics
[/metrics-micrometer] → rename to [/metrics]  ← per ADR-020
```

### Recommended Project Structure (additions only)

```
backend/src/main/kotlin/com/jh/proj/coroutineviz/
├── Serialization.kt          # CHANGE: add vizEventSerializersModule val + pass to json()
├── VizEventSerializersModule.kt  # NEW: defines the SerializersModule with all 66 subclasses
├── Monitoring.kt             # CHANGE: rename /metrics-micrometer → /metrics; extend wireMetrics()
├── MetricsWiring.kt          # CHANGE: add 5 missing ADR-020 metrics
├── routes/
│   └── HealthRoutes.kt       # CHANGE: add /api/health, /api/live, /api/ready; keep /health alias
├── wrappers/
│   └── VizScope.kt           # CHANGE: fix invokeOnCompletion when-branch (FIX-03)
├── scenarios/
│   └── ScenarioRunner.kt     # CHANGE: restore child1.cancel(), remove job.cancel() (FIX-04)
└── (delete) session/         # FND-01: delete entire subtree

backend/src/main/resources/
├── application.yaml          # FND-02: add session.maxEvents key (default 10000)
├── logback-prod.xml          # unchanged (exists)
└── logback.xml               # unchanged

backend/Dockerfile            # PROD-02: add -Dlogback.configurationFile
docker-compose.prod.yml       # PROD-02: add -Dlogback.configurationFile to ENTRYPOINT

frontend/src/
├── types/api.ts              # FIX-02: update ValidationResult type
├── components/validation/
│   └── ValidationPanel.tsx   # FIX-02: read data.results, derive errors/warnings by type filter
└── hooks/
    └── use-validation.ts     # FIX-02: type update (may be no-op if type is inferred)
```

### Pattern 1: kotlinx.serialization Polymorphic Module for non-sealed interface

**What:** A `SerializersModule` that registers all concrete subclasses of a non-sealed interface in a `polymorphic { }` scope, installed on a custom `Json` instance passed to ContentNegotiation and used directly in the SSE route.

**When to use:** Any time `kotlinx.serialization` must serialize/deserialize a type that is an interface (not a sealed class) where the concrete type is known only at runtime.

**Example (the install pattern for this phase):** [ASSUMED — standard kotlinx.serialization API, well-known]

```kotlin
// VizEventSerializersModule.kt
val vizEventSerializersModule = SerializersModule {
    polymorphic(VizEvent::class) {
        subclass(CoroutineCreated::class)
        subclass(CoroutineStarted::class)
        // ... all 66 subclasses ...
    }
}

// Serialization.kt — pass to ContentNegotiation
fun Application.configureSerialization() {
    val json = Json {
        serializersModule = vizEventSerializersModule
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    install(ContentNegotiation) {
        json(json)
    }
}

// SessionRoutes.kt — replace bare Json.encodeToString with the shared instance
// Two locations at lines 199 and 215:
// data = Json.encodeToString(event)
// becomes:
// data = appJson.encodeToString(VizEvent.serializer(), event)
// or: data = json.encodeToString<VizEvent>(event)  (with the module-aware instance)
```

**Critical detail:** `Json.encodeToString(event)` at `SessionRoutes.kt:199` and `:215` uses the **default `Json` instance** (no module). It must be replaced with the module-aware instance. The simplest wiring: expose the `Json` instance as an `Application`-scoped attribute or a top-level val in `Serialization.kt`, and import it in `SessionRoutes.kt`.

### Pattern 2: Registration-Completeness Test (D-04)

**What:** A backend unit test that enumerates all data classes in the `events` package tree and asserts each is registered in the `SerializersModule`.

**When to use:** D-04 is locked — write this test alongside FIX-01.

```kotlin
// VizEventSerializersModuleTest.kt
@Test
fun `all VizEvent subclasses are registered in SerializersModule`() {
    val module = vizEventSerializersModule
    val knownSubclasses = listOf(
        CoroutineCreated::class, CoroutineStarted::class, /* ... all 66 */
    )
    for (klass in knownSubclasses) {
        val json = Json { serializersModule = module }
        // Create a minimal instance and encode as VizEvent — should not throw
        // Alternative: assert module.getContextual(klass) != null
        assertNotNull(
            module.getPolymorphic(VizEvent::class, klass.serializer()),
            "Missing registration for ${klass.simpleName}"
        )
    }
}
```

### Pattern 3: Ktor testApplication route regression test

**What:** Existing pattern from `HealthRoutesTest.kt` and `SessionRoutesTest.kt` — use `testApplication { application { module() } }` with a JSON-configured client.

**When to use:** All backend regression tests for FIX-01, FIX-03, FIX-04, FND-03.

```kotlin
// Regression pattern from SessionRoutesTest.kt / HealthRoutesTest.kt [VERIFIED: codebase read]
@Test
fun `GET events returns 200 after scenario run`() = testApplication {
    application { module() }
    val client = createClient { install(ContentNegotiation) { json() } }
    val sessionId = client.post("/api/sessions?name=fix01-test")
        .body<Map<String,String>>()["sessionId"]!!
    client.post("/api/scenarios/run/nested-coroutines?sessionId=$sessionId")
    val response = client.get("/api/sessions/$sessionId/events")
    assertEquals(HttpStatusCode.OK, response.status)
    val events = response.body<List<Map<String,*>>>()
    assertTrue(events.isNotEmpty())
}
```

### Anti-Patterns to Avoid

- **Using `Json.encodeToString(event)` (default instance) for VizEvent polymorphic serialization:** The default `Json` has no `SerializersModule` and cannot resolve the subtype at runtime. Always use the module-aware `Json` instance after FIX-01.
- **Casting `session.store.all()` to a typed list before passing to `call.respond()`:** Ktor's ContentNegotiation will serialize via the registered Json, but only if the type passed is `List<VizEvent>`. If cast to `List<CoroutineCreated>` the polymorphic type discriminator is lost.
- **Leaving the SseStreamTest's `Json.encodeToString(event as CoroutineCreated)` workaround:** The existing `SseStreamTest.kt:116` casts to concrete type to avoid the serialization error. After FIX-01 this cast should be removed so the test covers the real path.
- **Calling `SessionManager.configure()` after sessions are created:** The configure method sets `maxEventsPerSession` which is read only at `createSession()` time. Call it during `Application.module()` startup before any routes are registered.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Polymorphic JSON type discrimination | Custom `type`/`kind` field switch in SSE route | `kotlinx.serialization SerializersModule` polymorphic scope | Handles serialization exceptions, roundtrip safety, all 66 subtypes automatically |
| Prometheus exposition format | Manual metric string concatenation | `PrometheusMeterRegistry.scrape()` (already used in Monitoring.kt) | Correct text format, labels, help strings — exact format is complex |
| Logback JSON encoding | Custom log appender | `logstash-logback-encoder` LogstashEncoder | Correct JSON escaping, MDC fields, ECS compatibility |
| Event count eviction | Custom ArrayList + evict logic | Core `EventStore(maxEvents)` (already implemented in `coroutine-viz-core`) | Thread-safe `ArrayDeque` + `ReentrantReadWriteLock`, already tested in `EventStoreTest.kt:50-62` |

**Key insight:** All the hard problems in this phase are already solved in `coroutine-viz-core` — the work is wiring, not implementing. The fork is the reason the solutions are bypassed.

---

## FIX-01: VizEvent Serializer Inventory

### Complete @Serializable VizEvent subclass list (66 concrete classes) [VERIFIED: codebase grep]

Found via `grep -rn "@SerialName"` across all event files in `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/`:

**Coroutine package (8):** `CoroutineBodyCompleted`, `CoroutineCancelled`, `CoroutineCompleted`, `CoroutineCreated`, `CoroutineFailed`, `CoroutineResumed`, `CoroutineStarted`, `CoroutineSuspended`

**Job package (4):** `JobCancellationRequested`, `JobJoinCompleted`, `JobJoinRequested`, `JobStateChanged`

**Flow package (13 total; 6 have explicit `@SerialName`, 7 have `@Serializable` only):**
- With `@SerialName`: `FlowBufferOverflow`, `FlowCollectionCancelled`, `FlowCollectionCompleted`, `FlowCollectionStarted`, `FlowCreated`, `FlowValueEmitted`
- With `@Serializable` only (no `@SerialName`): `FlowBackpressure`, `FlowOperatorApplied`, `FlowValueFiltered`, `FlowValueTransformed`, `SharedFlowEmission`, `SharedFlowSubscription`, `StateFlowValueChanged`

  **Action required:** These 7 flow classes must get `@SerialName` annotations matching their `kind` property value before being registered. Their `kind` property values are: `FlowBackpressure`, `FlowOperatorApplied`, `FlowValueFiltered`, `FlowValueTransformed` (verify via `override val kind` grep), `SharedFlowEmission`, `SharedFlowSubscription`, `StateFlowValueChanged`.

**Dispatcher package (2):** `DispatcherSelected`, `ThreadAssigned`

**Deferred package (3):** `DeferredAwaitCompleted`, `DeferredAwaitStarted`, `DeferredValueAvailable`

**Channel package (9):** `ChannelBufferStateChanged`, `ChannelClosed`, `ChannelCreated`, `ChannelReceiveCompleted`, `ChannelReceiveStarted`, `ChannelReceiveSuspended`, `ChannelSendCompleted`, `ChannelSendStarted`, `ChannelSendSuspended`

**Mutex (6):** `MutexCreated`, `MutexLockAcquired`, `MutexLockRequested`, `MutexQueueChanged`, `MutexTryLockFailed`, `MutexUnlocked`

**Semaphore (6):** `SemaphoreAcquireRequested`, `SemaphoreCreated`, `SemaphorePermitAcquired`, `SemaphorePermitReleased`, `SemaphoreStateChanged`, `SemaphoreTryAcquireFailed`

**Actor (7):** `ActorClosed`, `ActorCreated`, `ActorMailboxChanged`, `ActorMessageProcessed`, `ActorMessageProcessing`, `ActorMessageSent`, `ActorStateChanged`

**Select (4):** `SelectClauseRegistered`, `SelectClauseWon`, `SelectCompleted`, `SelectStarted`

**Deadlock (2):** `DeadlockDetected`, `PotentialDeadlockWarning`

**Anti-pattern (1):** `AntiPatternDetected`

**Structured concurrency (1):** `WaitingForChildren`

**NOT a VizEvent subtype — exclude from registration:** `SuspensionPoint` (standalone helper data class, not a `VizEvent` implementor; embedded as a field inside `CoroutineSuspended`). Also exclude `AntiPatternSeverity` and `AntiPatternType` (enums).

### Two Json instances that need the module [VERIFIED: codebase read]

1. **ContentNegotiation in `Serialization.kt:17`:** Currently `json()` with default Json — passes no SerializersModule. This affects `GET /api/sessions/{id}/events` (returns `List<VizEvent>`).

2. **SSE route in `SessionRoutes.kt:199` and `:215`:** Uses bare `Json.encodeToString(event)` — the default `Json` companion object, no module. This is the direct cause of the `SerializationException` in logs.

**Fix approach:** Create a shared val (e.g. in `Serialization.kt` or a new `VizEventSerializersModule.kt`):
```kotlin
val appJson = Json {
    serializersModule = vizEventSerializersModule
    encodeDefaults = true
    ignoreUnknownKeys = true
}
```
Then:
- Pass `appJson` to `install(ContentNegotiation) { json(appJson) }`
- Import and use `appJson` in `SessionRoutes.kt` for both SSE `encodeToString` calls

---

## FIX-02: Validation Contract Mismatch [VERIFIED: codebase read]

### Backend shape (unchanged, locked by D-02)

`POST /api/validate/session/{id}` returns `ValidationResponse`:
```kotlin
// ValidationRoutes.kt:13-18
data class ValidationResponse(
    val sessionId: String,
    val results: List<ValidationResult>,  // sealed class: ValidationResult.Pass | ValidationResult.Fail
    val timing: TimingReport,
)
```

The backend `ValidationResult` (from `checksystem/ValidationResult.kt`) is a sealed class with:
- `Pass(ruleName: String, message: String)` — serialized with discriminator `"type": "Pass"`
- `Fail(ruleName: String, message: String, details: String)` — serialized with discriminator `"type": "Fail"`

The backend `TimingReport` (from `checksystem/TimingAnalyzer.kt`) has:
- `coroutineDurations: Map<String, Long>`, `suspensionDurations: Map<String, List<Long>>`, `totalDuration: Long`

**Important discrepancy:** The frontend `TimingReport` type in `frontend/src/types/api.ts:640-647` has fields `totalDurationNanos`, `eventCount`, `coroutineCount`, `avgEventIntervalNanos`, `maxGapNanos`, `suspendResumeLatencies` — this shape does NOT match the backend's `TimingReport`. The backend returns only `coroutineDurations`, `suspensionDurations`, `totalDuration`.

### Frontend files to change [VERIFIED: codebase read]

1. **`frontend/src/types/api.ts:616-621`** — `ValidationResult` interface currently has `valid: boolean`, `errors: ValidationError[]`, `warnings: ValidationWarning[]`, `timing: TimingReport`. Change to match actual backend shape: `{ sessionId: string, results: Array<{type: 'Pass'|'Fail', ruleName: string, message: string, details?: string}>, timing: BackendTimingReport }`.

2. **`frontend/src/components/validation/ValidationPanel.tsx:83-109`** — reads `data.valid`, `data.errors.length`, `data.warnings.length`, iterates `data.errors`, `data.warnings`. Must be changed to filter `data.results` on `type === 'Fail'` for errors and `type === 'Pass'` for passes (or adopt a different grouping that makes sense for the rendered output).

3. **`frontend/src/components/validation/TimingReportView.tsx`** — currently expects the frontend `TimingReport` shape with `totalDurationNanos`, etc. Must be updated to match backend's `{ coroutineDurations, suspensionDurations, totalDuration }` or the frontend type must be reconciled with the backend type.

### Tests that assert the wrong shape [VERIFIED: codebase read]

**`frontend/src/components/validation/ValidationPanel.test.tsx`** — the existing test at line 61 creates a `ValidationResult` with the old shape `{ valid: true, errors: [], warnings: [], timing: {...} }`. This test will need updating to use the real backend shape. The test structure (testid assertions, `createWrapper()` pattern) can be reused but the mock data shape must change.

---

## FIX-03: VizScope Completion Handler Fix [VERIFIED: codebase read]

### Exact location

`backend/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:182`

### Current broken when-branch

```kotlin
cause !is CancellationException && cause.message?.contains(ctx.label ?: "unknown") == true -> {
    // FAILED — but only fires if exception message contains coroutine label
```

No exception in any scenario has a message containing its coroutine label (e.g., `"Intentional failure for demo"` does not contain `"failing-child"`), so this branch never fires. All non-CancellationExceptions fall through to the `cause is CancellationException || job.isCancelled` branch (line 197) and emit `CoroutineCancelled`.

### Fix (4-line change)

Replace the `cause !is CancellationException && cause.message?.contains(...)` branch with:
```kotlin
cause !is CancellationException -> {
    // Any non-cancellation throwable = FAILED
    session.send(ctx.coroutineFailed(cause::class.simpleName, cause.message))
    session.send(ctx.jobStateChanged(isActive = false, isCompleted = false, isCancelled = false,
        childrenCount = coroutineContext[Job]?.children?.count() ?: 0))
}
```

The `else -> throw IllegalArgumentException(...)` branch becomes dead code and can be removed. Note: after fixing, `jobStateChanged` for FAILED coroutines should set `isCancelled = false` (currently both branches set it `true`).

---

## FIX-04: Cancellation Scenario Fix [VERIFIED: codebase read]

### Exact locations in `ScenarioRunner.kt`

```kotlin
// Line 117 — uncomment:
//            child1.cancel()

// Line 123-124 — remove the root cancel:
delay(1000)
job.cancel()  // REMOVE THIS
```

### Fix

Uncomment `child1.cancel()` at line 117 (add a short `vizDelay(500)` before it so it's visibly mid-flight). Remove `delay(1000); job.cancel()` at lines 123-124. The `normal-child` has a 3-second delay — after child1 is cancelled, `normal-child` runs to completion. The `parent` waits for `normal-child` via structured concurrency.

Also remove the `val child1 = ...` / `val child2 = ...` variable assignments note: `child1` is used for the cancel call; `child2` (normal-child) is not needed as a variable unless you want to `join()` it explicitly. Keep `child1` reference, remove unused `child2` variable or keep it if you want explicit join.

---

## FND-01: De-Fork Blast Radius [VERIFIED: codebase read]

### 10 files to delete

```
backend/src/main/kotlin/com/jh/proj/coroutineviz/session/
├── EventApplier.kt
├── EventBus.kt
├── EventContext.kt
├── EventStore.kt
├── FlowEventContext.kt
├── ChannelEventContext.kt
├── JobStatusMonitor.kt
├── ProjectionService.kt
├── SessionManager.kt
└── VizSession.kt
```

### Files importing from the fork (compilation risk list) [VERIFIED: codebase grep]

All of these import `com.jh.proj.coroutineviz.session.*` — after deletion they resolve to core (same package name). No import statement changes are needed:

| File | Import Style |
|------|-------------|
| `wrappers/InstrumentedFlow.kt` | wildcard `session.*` |
| `wrappers/InstrumentedChannel.kt` | wildcard `session.*` |
| `wrappers/InstrumentedSharedFlow.kt` | wildcard `session.*` |
| `wrappers/InstrumentedStateFlow.kt` | wildcard `session.*` |
| `wrappers/VizScope.kt` | specific: `EventContext`, `VizSession`, `coroutine*`, `jobStateChanged`, `waitingForChildren` |
| `routes/SessionRoutes.kt` | specific: `SessionManager` |
| `routes/HealthRoutes.kt` | specific: `SessionManager` |
| `routes/ComparisonRoutes.kt` | specific: `ComparisonService`, `SessionManager` |
| `routes/FlowScenarioRoutes.kt` | specific: `SessionManager`, `VizSession` |
| `routes/PatternRoutes.kt` | specific: `VizSession` |
| `routes/ScenarioRunnerRoutes.kt` | specific: `SessionManager`, `VizSession` |
| `routes/SyncScenarioRoutes.kt` | specific: `SessionManager` |
| `routes/ValidationRoutes.kt` | specific: `SessionManager` |
| `routes/VizScenarioRoutes.kt` | specific: `VizSession` |
| `scenarios/ScenarioRunner.kt` | specific: `VizSession` |
| `sync/DeadlockDetector.kt` | specific: `VizSession` |
| `wrappers/InstrumentedDeferred.kt` | specific: `VizSession` |
| `wrappers/InstrumentedDispatcher.kt` | specific: `VizSession` |
| `wrappers/VizDispatchers.kt` | specific: `VizSession` |
| `wrappers/VizMutex.kt` | specific: `VizSession` |
| `wrappers/VizSemaphore.kt` | specific: `VizSession` |
| `MetricsWiring.kt` | specific: `SessionManager` |
| `examples/DispatcherExample.kt` | specific: `VizSession` |

### Compilation risk: VizScope imports `EventContext` extension functions from fork [VERIFIED: codebase read]

`VizScope.kt:9-18` imports `coroutineBodyCompleted`, `coroutineCancelled`, `coroutineCompleted`, `coroutineCreated`, `coroutineFailed`, `coroutineResumed`, `coroutineStarted`, `coroutineSuspended`, `jobStateChanged`, `waitingForChildren` — all as top-level extension functions from `com.jh.proj.coroutineviz.session`. Both the fork (`backend/src/main/.../session/EventContext.kt`) and core (`backend/coroutine-viz-core/.../session/EventContext.kt`) declare these functions in the same package. After deleting the fork, these resolve to core — **no import changes needed**.

### Fork API differences [VERIFIED: codebase read]

The fork's `SessionManager` lacks `configure(maxEventsPerSession)`. The core `SessionManager` has it at line 35. After deletion, `configure()` is available — FND-02 calls it from `Application.kt`.

The fork's `EventStore` uses `CopyOnWriteArrayList` (unbounded). The core uses `ArrayDeque` + `ReentrantReadWriteLock` with `maxEvents = 10_000` default. After deletion, the bounded store is active automatically once `SessionManager.configure()` is called.

The core `SessionManager.closeSession()` exists as a delegating method (`fun closeSession(sessionId: String): Boolean = deleteSession(sessionId)`) — the routes calling `closeSession()` continue to compile.

---

## FND-02: Bounded Store Wiring

### application.yaml addition [VERIFIED: codebase read of existing yaml]

```yaml
# Add to backend/src/main/resources/application.yaml
session:
  maxEvents: ${SESSION_MAX_EVENTS:10000}
```

### Application.kt startup call

```kotlin
// Add to Application.module() in Application.kt before configureRouting()
val maxEvents = environment.config.propertyOrNull("session.maxEvents")
    ?.getString()?.toIntOrNull() ?: 10_000
SessionManager.configure(maxEventsPerSession = maxEvents)
```

### Core EventStore default [VERIFIED: codebase read]

`EventStore(private val maxEvents: Int = 10_000)` at `coroutine-viz-core/.../session/EventStore.kt:26`. The default matches the requirement. The `configure()` call sets `maxEventsPerSession` in `SessionManager` which passes it to `VizSession(sessionId, maxEvents = maxEventsPerSession)` at core `SessionManager.kt:52`.

---

## PROD-01: Health Endpoints

### Current state [VERIFIED: codebase read]

`HealthRoutes.kt` defines one route: `GET /health` (mounted in `Routing.kt`). Returns `HealthStatus(status, sessions, uptimeMs, memory)` — no `version` field, no component check labels, no `/live` or `/ready`.

### Target structure

Extend `HealthRoutes.kt`:
- Rename existing `/health` route body to a shared function `buildHealthStatus()` or inline it
- Add `GET /api/health` returning the full `HealthStatus` with `version` field
- Add `GET /api/live` — lightweight liveness: always 200 `{"status":"UP"}` unless JVM is deadlocked
- Add `GET /api/ready` — readiness: 200 `{"status":"UP"}` when `SessionManager` is reachable and memory < 95%; 503 otherwise
- Keep `GET /health` as an alias: redirect to `/api/health` or duplicate the route body

### `version` field (Claude's discretion) [ASSUMED]

Options: (1) Read from `build.gradle.kts`'s `version = "0.0.1"` by embedding it into `application.yaml` at build time via a Gradle `processResources` task. (2) Read from the JAR manifest (`Implementation-Version` attribute). (3) Hardcode `"0.0.1"` in code for now. Option 3 is the simplest for Phase 1 — the version is already defined in `build.gradle.kts`. Option 1 is more maintainable.

### Updated `HealthStatus` shape

```kotlin
@Serializable
data class HealthStatus(
    val status: String,       // "UP" | "DEGRADED"
    val version: String,      // e.g. "0.0.1"
    val sessions: Int,
    val uptimeMs: Long,
    val memory: MemoryInfo,
    val components: Map<String, String> = emptyMap()  // e.g. {"sessionManager": "UP", "memory": "UP"}
)
```

Docker healthcheck in `docker-compose.prod.yml` currently checks `http://localhost:8080/` — it should be updated to `/health` or `/api/health`.

---

## PROD-02: Logback Prod Profile

### Problem [VERIFIED: codebase read]

`logback-prod.xml` exists and is correct JSON config, but:
1. No reference to it anywhere in Dockerfile or compose files
2. It uses `LogstashEncoder` (`net.logstash.logback.encoder.LogstashEncoder`) but `logstash-logback-encoder` is **not in `build.gradle.kts`** — this is a blocking gap

### Fix

1. Add dependency to `build.gradle.kts`:
   ```kotlin
   implementation("net.logstash.logback:logstash-logback-encoder:8.1")
   ```

2. Update `backend/Dockerfile` ENTRYPOINT (D-06 — JVM flag):
   ```dockerfile
   ENTRYPOINT ["java", "-Dlogback.configurationFile=/app/logback-prod.xml", "-jar", "app.jar"]
   ```

3. Copy `logback-prod.xml` into the Docker image. Currently the Dockerfile copies `src` and builds from it, so `logback-prod.xml` ends up in the JAR's classpath resources. However, `-Dlogback.configurationFile` expects a **filesystem path**, not a classpath resource. Options: (a) copy the file separately to `/app/logback-prod.xml` in the Dockerfile; (b) use `logback.configurationFile` as a classpath lookup via `classpath:logback-prod.xml` (not standard). The simplest: `COPY --from=build /app/src/main/resources/logback-prod.xml /app/logback-prod.xml` in the runtime stage.

4. Update `docker-compose.prod.yml` service `backend.environment` or override `ENTRYPOINT`.

---

## PROD-05: ADR-020 Metrics

### Currently wired (2 of 7) [VERIFIED: codebase read — MetricsWiring.kt]

```kotlin
// MetricsWiring.kt:14-24
Gauge "viz.sessions.active"   — SessionManager.listSessions().size
Gauge "viz.sse.clients.active" — sseClientsGauge.toDouble()
```

Scrape endpoint: `GET /metrics-micrometer` (should be renamed to `GET /metrics` per ADR-020).

### Missing 5 metrics from ADR-020 [VERIFIED: docs/adr/020-performance-scaling.md]

| ADR-020 Metric | Type | Description |
|----------------|------|-------------|
| `events.emitted` | Counter | Total events emitted across all sessions |
| `events.dropped` | Counter | Events dropped due to bounds |
| `events.buffer.size` | Gauge | Current event count per session (tagged by session_id) |
| `scenario.duration` | Timer | Time to complete scenario execution |
| `event.processing.duration` | Timer | Time to process and broadcast a single event |

### Wiring points

- `events.emitted` / `events.dropped`: increment in `VizSession.send()` or in `EventStore.append()` — after de-fork, the core `EventStore.onEvict` callback can increment `events.dropped`; `VizSession.send()` increments `events.emitted`
- `events.buffer.size`: gauge reading `session.store.count()`, tagged with `sessionId`
- `scenario.duration`: record in `ScenarioRunnerRoutes.kt` wrapping the scenario run call with `Timer.Sample`
- `event.processing.duration`: record in `VizSession.send()` wrapping the store+bus+snapshot operations

**Note:** The `PrometheusMeterRegistry` is already instantiated in `Monitoring.kt:11` and passed to `wireMetrics()`. Extend `MetricsWiring.kt`'s `wireMetrics(registry)` function to register the 5 additional metrics. The registry needs to be accessible in `VizSession.send()` and `EventStore` — inject via `SessionManager.configure()` or via a global `Metrics` object.

---

## Common Pitfalls

### Pitfall 1: Stale `Json.encodeToString` in SseStreamTest

**What goes wrong:** After FIX-01, the existing `SseStreamTest.kt:116` still has `Json.encodeToString(event as CoroutineCreated)` — a cast to the concrete type to avoid the serialization error. This test passes even before FIX-01 because it bypasses the VizEvent interface.

**Why it happens:** The test was written as a workaround for the broken polymorphic path.

**How to avoid:** Update the test to encode as `VizEvent` (not as the concrete type) to verify the fix actually works end-to-end.

### Pitfall 2: VizScope.kt still imports from fork path after deletion

**What goes wrong:** Imports like `import com.jh.proj.coroutineviz.session.coroutineFailed` compile fine before deletion (resolving to the fork), and after deletion they resolve to core — but only if core's `EventContext.kt` exports the same function. **Verified:** core `EventContext.kt` has all the same extension functions (`coroutineFailed`, `coroutineCancelled`, etc.). No import change needed.

**How to avoid:** Run `./gradlew compileKotlin` immediately after deletion as step 1 of FND-01 verification before running the full test suite.

### Pitfall 3: ValidationPanel test uses old shape (test will fail after FIX-02)

**What goes wrong:** `ValidationPanel.test.tsx` at line 61-74 uses a mock `ValidationResult` with `{ valid: true, errors: [], warnings: [], timing: {...} }`. After FIX-02 changes the `ValidationResult` type, these tests fail with type errors.

**How to avoid:** Update the test fixtures alongside the production code change. The test assertions (checking for `validation-summary` testid, `Validation Passed` text) remain valid — only the mock data shape changes.

### Pitfall 4: `TimingReport` frontend/backend shape mismatch

**What goes wrong:** The frontend `TimingReport` type in `api.ts:640-647` has completely different fields (`totalDurationNanos`, `eventCount`, `avgEventIntervalNanos`, `suspendResumeLatencies`) than the backend `TimingReport` (`coroutineDurations`, `suspensionDurations`, `totalDuration`). This means `TimingReportView.tsx` also renders incorrect fields.

**Why it happens:** The frontend type was designed against an anticipated API shape that was never implemented.

**How to avoid:** As part of FIX-02, reconcile `frontend/src/types/api.ts:TimingReport` with the actual backend `TimingReport`, and update `TimingReportView.tsx` to display `coroutineDurations` and `suspensionDurations` maps. The `ValidationPanel.test.tsx` `TimingReportView` test block (lines 159-183) uses the old fixture shape and must be updated.

### Pitfall 5: Flow classes without `@SerialName` cause silent serialization errors

**What goes wrong:** 7 flow event classes (`FlowBackpressure`, `FlowOperatorApplied`, `FlowValueFiltered`, `FlowValueTransformed`, `SharedFlowEmission`, `SharedFlowSubscription`, `StateFlowValueChanged`) have `@Serializable` but no `@SerialName`. kotlinx.serialization uses the class name as the discriminator by default in polymorphic scope, but this may differ from the `kind` property value used by the SSE route.

**How to avoid:** Add `@SerialName("FlowBackpressure")` etc. to each class (matching their `kind` property value) before registering them in the `SerializersModule`.

### Pitfall 6: `logback-prod.xml` uses LogstashEncoder but dep is absent

**What goes wrong:** Building a Docker image with the ENTRYPOINT pointing to `logback-prod.xml` causes a `ClassNotFoundException: net.logstash.logback.encoder.LogstashEncoder` at startup, killing the container.

**How to avoid:** Add `net.logstash.logback:logstash-logback-encoder:8.1` to `build.gradle.kts` before shipping PROD-02.

---

## Code Examples

### Kotlinx.serialization polymorphic module — verified API [ASSUMED — standard API]

```kotlin
// SerializersModule with polymorphic registration
val vizEventSerializersModule = SerializersModule {
    polymorphic(VizEvent::class) {
        subclass(CoroutineCreated::class)
        subclass(CoroutineStarted::class)
        subclass(CoroutineBodyCompleted::class)
        subclass(CoroutineSuspended::class)
        subclass(CoroutineResumed::class)
        subclass(CoroutineCompleted::class)
        subclass(CoroutineCancelled::class)
        subclass(CoroutineFailed::class)
        // ... (all 66 — see §FIX-01 inventory)
    }
}
```

### Ktor ContentNegotiation with custom Json [ASSUMED — standard Ktor API]

```kotlin
// Serialization.kt
fun Application.configureSerialization() {
    val json = Json {
        serializersModule = vizEventSerializersModule
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    install(ContentNegotiation) {
        json(json)
    }
    // expose for other modules to reuse
    attributes.put(AppJsonKey, json)
}
val AppJsonKey = AttributeKey<Json>("AppJson")
```

### SessionManager.configure() call [VERIFIED: codebase read — core SessionManager.kt:35]

```kotlin
// Application.kt — add before configureRouting()
val maxEvents = environment.config.propertyOrNull("session.maxEvents")
    ?.getString()?.toIntOrNull() ?: 10_000
SessionManager.configure(maxEventsPerSession = maxEvents)
```

### Micrometer Counter/Timer [ASSUMED — standard Micrometer API]

```kotlin
// In MetricsWiring.kt wireMetrics() extension
val eventsEmittedCounter = Counter.builder("events.emitted")
    .description("Total events emitted")
    .register(registry)

val scenarioDurationTimer = Timer.builder("scenario.duration")
    .description("Scenario execution time")
    .register(registry)

// Usage at call site:
eventsEmittedCounter.increment()
val sample = Timer.start()
// ... work ...
sample.stop(scenarioDurationTimer)
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| All `VizEvent` serialization using concrete types | Polymorphic SerializersModule for interface dispatch | This phase | Required for SSE + /events endpoint to work |
| Unbounded CopyOnWriteArrayList EventStore | Bounded ArrayDeque with ReentrantReadWriteLock | Core already done; this phase wires it | Prevents OOM under load |
| `/health` only | `/api/health`, `/api/live`, `/api/ready` + alias | This phase | Kubernetes-compatible health probes |

**Deprecated/outdated:**
- `backend/src/main/.../session/*.kt` fork: will be deleted by FND-01
- `/metrics-micrometer` endpoint path: rename to `/metrics` per ADR-020

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `net.logstash.logback:logstash-logback-encoder:8.1` is the current version | PROD-02 / Package Legitimacy | Slightly wrong version; safe to use latest stable release |
| A2 | The 7 flow classes without `@SerialName` will use class-name-as-discriminator in polymorphic scope | FIX-01 | If kotlinx.serialization refuses to register them without `@SerialName`, adding the annotation is required before registration |
| A3 | `version = "0.0.1"` in build.gradle.kts is the version to expose in `/api/health` | PROD-01 | Cosmetic — version is developer-visible only |
| A4 | kotlinx.serialization's polymorphic discriminator field name defaults to `"type"` | FIX-01 | If the frontend SSE consumer reads a different field for routing, a mismatch occurs. Frontend `useEventStream` reads `event.kind` (the SSE `event:` field, not a JSON body field) so this is not a concern for SSE. For `/events` REST the response is `List<VizEvent>` decoded on frontend from JSON — frontend does not deserialize this as polymorphic Kotlin; it reads individual events by `kind` field. |

---

## Open Questions (RESOLVED)

1. **Metrics registry accessibility in VizSession.send()** — RESOLVED: Add `onEventEmitted: (() -> Unit)?` and `onEventDropped: (() -> Unit)?` callbacks to core `VizSession` (mirroring the existing `EventStore.onEvict` pattern), set from `MetricsWiring.wireMetrics()` via a `SessionManager` session-created hook, so counter increments flow through to VizSession WITHOUT adding Micrometer to `coroutine-viz-core`. Adopted by Plan 01-05 Task 3 (the callback hooks + `coroutine-viz-core` Micrometer-free assertion).
   - What we know: `PrometheusMeterRegistry` is created in `Monitoring.kt` and passed to `wireMetrics()` — but `VizSession.send()` is in core (no Ktor/Micrometer dep) and has no reference to the registry.
   - What was unclear: Best approach to thread the counter increments through to VizSession without adding Micrometer to `coroutine-viz-core`'s dependency set.
   - Recommendation (adopted): Add `onEventEmitted: (() -> Unit)?` and `onEventDropped: (() -> Unit)?` callbacks to `VizSession` (similar to the existing `onEvict` callback on `EventStore`), set them from `MetricsWiring.wireMetrics()` after session creation via `SessionManager.onSessionCreated` callback.

2. **`/health` alias implementation** — RESOLVED: Extract a shared `private suspend fun ApplicationCall.respondHealth()` helper and call it from both the `/health` alias and `/api/health` routes (no redirect, no duplicated body). Adopted by Plan 01-04 Task 1.
   - What we know: D-02 requires `/health` to keep working.
   - What was unclear: Best Ktor idiom for aliasing — duplicate route body, call.respondRedirect, or shared function.
   - Recommendation (adopted): Extract a shared `respondHealth(call)` suspend function and call it from both `/health` and `/api/health` routes.

3. **`ValidationPanel` error/warning rendering after FIX-02** — RESOLVED: Render `Fail` results in a Failures section and `Pass` results in a compact Passes list; drop the Warnings section entirely since the backend `ValidationResult` sealed class has no Warning variant. Adopted by Plan 01-02 Tasks 1–2 (results filtered by `type === 'Fail' | 'Pass'`, no Warnings section, `text-warning` forbidden in the panel).
   - What we know: Backend returns `results: [{type:'Pass'|'Fail', ruleName, message, details?}]`. The frontend currently shows separate Errors/Warnings sections. The backend's `ValidationResult` only has `Pass` and `Fail` — there is no "Warning" variant.
   - What was unclear: Should the frontend render `Pass` results at all? Should `Fail` results be labelled as "Errors"?
   - Recommendation (adopted): Render `Fail` → Failures section, `Pass` → compact Passes list. Drop the Warnings section entirely since the backend has no Warning type.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Kotlin 2.2 / JVM 21 | All backend changes | ✓ | kotlin 2.2.20, JVM 21 (eclipse-temurin) | — |
| `./gradlew` | Backend builds + tests | ✓ | Gradle wrapper present | — |
| `pnpm` | Frontend tests | ✓ (assumed from dev env) | — | — |
| `net.logstash.logback:logstash-logback-encoder` | PROD-02 | ✗ (not in build.gradle.kts) | 8.1 (Maven Central) | No fallback — required for logback-prod.xml's LogstashEncoder |

**Missing dependencies with no fallback:**
- `net.logstash.logback:logstash-logback-encoder` — required by the existing `logback-prod.xml`; add to `build.gradle.kts` as part of PROD-02.

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Backend framework | JUnit 5 (Jupiter) 5.10.1 + `kotlin-test-junit` + `kotlinx-coroutines-test` 1.7.3 |
| Config file | `backend/build.gradle.kts` — `tasks.named<Test>("test") { useJUnitPlatform() }` |
| Quick run command | `cd backend && ./gradlew test --tests "*.FIX01*"` (or specific class) |
| Full suite command | `cd backend && ./gradlew test` |
| Frontend framework | Vitest 4.1, jsdom, @testing-library/react 16 |
| Frontend config | `frontend/vitest.config.ts` |
| Frontend quick | `cd frontend && pnpm test` |
| Frontend full | `cd frontend && pnpm test:coverage` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| FIX-01 | `/events` returns 200 with event list after scenario run | integration (testApplication) | `./gradlew test --tests "*.SerializersModuleTest*"` | ❌ Wave 0 |
| FIX-01 | All VizEvent subclasses registered in SerializersModule | unit | `./gradlew test --tests "*.VizEventSerializersModuleTest*"` | ❌ Wave 0 |
| FIX-01 | SSE stream delivers ≥1 event without SerializationException | integration | `./gradlew test --tests "*.SseStreamTest*"` | ✅ (needs update to test real path) |
| FIX-02 | ValidationPanel renders results without error boundary crash | component | `cd frontend && pnpm test` | ✅ (needs fixture update) |
| FIX-03 | Exception scenario: failing-child == FAILED, normal-child == CANCELLED | integration | `./gradlew test --tests "*.ExceptionScenarioRegressionTest*"` | ❌ Wave 0 |
| FIX-04 | Cancellation scenario: child-to-be-cancelled == CANCELLED, normal-child == COMPLETED | integration | `./gradlew test --tests "*.CancellationScenarioRegressionTest*"` | ❌ Wave 0 |
| FND-01 | Zero session classes under backend/src/main/.../session/ | static (grep in test or CI) | `./gradlew test --tests "*.ForkDeletionTest*"` | ❌ Wave 0 |
| FND-02 | application.yaml has maxEvents key; startup log shows bounded store | smoke | `./gradlew test --tests "*.BoundedStoreWiringTest*"` | ❌ Wave 0 |
| FND-03 | store.all().size <= maxEvents after > maxEvents events | integration | `./gradlew test --tests "*.BoundedStoreRegressionTest*"` | ❌ Wave 0 |
| PROD-01 | /api/health, /api/live, /api/ready all return 200 with JSON | integration | `./gradlew test --tests "*.HealthRoutesTest*"` | ✅ (needs extension for new paths) |
| PROD-03 | CORS allowed origins come from config, not literals | unit | `./gradlew test --tests "*.CorsConfigTest*"` | ❌ Wave 0 |
| PROD-05 | All 7 ADR-020 metric names present after scenario run | integration | `./gradlew test --tests "*.MetricsWiringTest*"` | ✅ (needs extension for 5 new metrics) |

### Sampling Rate
- **Per task commit:** `cd backend && ./gradlew test` (full suite; ~30s)
- **Per wave merge:** `cd backend && ./gradlew test && cd ../frontend && pnpm test`
- **Phase gate:** Both suites green before `/gsd-verify-work`

### Wave 0 Gaps

- [ ] `backend/src/test/.../VizEventSerializersModuleTest.kt` — covers FIX-01 registration completeness
- [ ] `backend/src/test/.../SerializersModuleIntegrationTest.kt` — covers FIX-01 route integration
- [ ] `backend/src/test/.../ExceptionScenarioRegressionTest.kt` — covers FIX-03
- [ ] `backend/src/test/.../CancellationScenarioRegressionTest.kt` — covers FIX-04
- [ ] `backend/src/test/.../BoundedStoreWiringTest.kt` — covers FND-02
- [ ] `backend/src/test/.../BoundedStoreRegressionTest.kt` — covers FND-03
- [ ] `backend/src/test/.../CorsConfigTest.kt` — covers PROD-03
- [ ] `frontend/src/components/validation/ValidationPanel.test.tsx` — update fixture shape (FIX-02)

Existing tests needing updates (not new files):
- `backend/src/test/.../SseStreamTest.kt` — remove cast-to-concrete workaround; test real VizEvent serialization
- `backend/src/test/.../HealthRoutesTest.kt` — add test cases for `/api/health`, `/api/live`, `/api/ready`
- `backend/src/test/.../MetricsWiringTest.kt` — add assertions for 5 new metric names

---

## Security Domain

Phase 1 does not introduce new authentication or authorization routes. The following ASVS considerations apply to the changes in scope:

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | Not touched in this phase |
| V3 Session Management | no | Session IDs are already random timestamps; no change |
| V4 Access Control | no | Auth wiring is explicitly out of scope |
| V5 Input Validation | yes (minor) | `maxEvents` config value parsed with `toIntOrNull() ?: 10_000` — safe default on invalid input |
| V6 Cryptography | no | No crypto in this phase |

**Threat patterns specific to this phase:**

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| OOM via unbounded event store | Denial of Service | FND-02 bounded EventStore caps events at maxEvents |
| Prometheus metrics endpoint leaks internal state | Information Disclosure | `/metrics` endpoint exposes session counts; acceptable for local/dev deployment; Phase 3 auth will gate it |
| Logback JSON in prod logs structured data exposure | Information Disclosure | logstash encoder outputs structured JSON; ensure no PII in coroutine labels; acceptable risk for Phase 1 |

---

## Sources

### Primary (HIGH confidence)
- `backend/src/main/kotlin/com/jh/proj/coroutineviz/` — direct codebase read, all key files verified
- `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/` — complete event inventory via filesystem grep
- `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/SessionManager.kt` — `configure()` signature verified
- `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/EventStore.kt` — bounded impl verified
- `docs/adr/020-performance-scaling.md` — exact 7-metric names and types
- `.planning/VERIFICATION.md` — RT-01/RT-02/RT-03 root causes with file:line evidence
- `.planning/SCENARIO-AUDIT.md` — SC-01/SC-03 root causes with file:line evidence
- `.planning/codebase/TESTING.md` — test patterns and framework versions

### Secondary (MEDIUM confidence)
- Maven Central search API — `logstash-logback-encoder` version 8.1 confirmed

### Tertiary (LOW confidence — ASSUMED)
- kotlinx.serialization `SerializersModule` / `polymorphic { }` DSL — standard API from training knowledge; not verified against Context7 in this session
- Micrometer Counter/Timer API — standard API from training knowledge

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all dependencies verified in `build.gradle.kts`; logstash version via Maven Central
- Architecture: HIGH — all file paths, line numbers, and signatures verified by direct codebase read
- Pitfalls: HIGH — all pitfalls derived from verified audit evidence (file:line cited in VERIFICATION.md / SCENARIO-AUDIT.md)
- Test framework: HIGH — verified in TESTING.md and test file reads
- Event inventory: HIGH — grep-verified, 66 concrete subclasses enumerated

**Research date:** 2026-06-11
**Valid until:** 2026-09-11 (stable stack — Ktor 3.3, kotlinx-serialization, Micrometer)
