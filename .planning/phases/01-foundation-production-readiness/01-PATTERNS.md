# Phase 1: Foundation & Production Readiness - Pattern Map

**Mapped:** 2026-06-11
**Files analyzed:** 17 new/modified files
**Analogs found:** 15 / 17

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `backend/src/main/.../VizEventSerializersModule.kt` | config/module | transform | `backend/src/main/.../Serialization.kt` | role-match |
| `backend/src/main/.../Serialization.kt` | config | request-response | itself (change) | self |
| `backend/src/main/.../Monitoring.kt` | config | request-response | itself (change) | self |
| `backend/src/main/.../MetricsWiring.kt` | utility | event-driven | itself (change) | self |
| `backend/src/main/.../routes/HealthRoutes.kt` | route | request-response | itself (change) | self |
| `backend/src/main/.../Application.kt` | config | request-response | itself (change) | self |
| `backend/src/main/.../wrappers/VizScope.kt` | utility | event-driven | itself (change, 4-line fix) | self |
| `backend/src/main/.../scenarios/ScenarioRunner.kt` | service | event-driven | itself (change, 2-line fix) | self |
| `backend/src/main/resources/application.yaml` | config | — | `HTTP.kt` config read pattern | partial-match |
| `backend/Dockerfile` | config | — | no analog | none |
| `frontend/src/types/api.ts` | model | — | itself (change) | self |
| `frontend/src/components/validation/ValidationPanel.tsx` | component | request-response | itself (change) | self |
| `frontend/src/components/validation/TimingReportView.tsx` | component | request-response | `ValidationPanel.tsx` | role-match |
| `backend/src/test/.../VizEventSerializersModuleTest.kt` | test | — | `EventSerializationTest.kt` | exact |
| `backend/src/test/.../SerializersModuleIntegrationTest.kt` | test | request-response | `SessionRoutesTest.kt` / `HealthRoutesTest.kt` | exact |
| `backend/src/test/.../ExceptionScenarioRegressionTest.kt` | test | request-response | `SessionRoutesTest.kt` | exact |
| `backend/src/test/.../CancellationScenarioRegressionTest.kt` | test | request-response | `SessionRoutesTest.kt` | exact |
| `backend/src/test/.../BoundedStoreWiringTest.kt` | test | request-response | `MetricsWiringTest.kt` | role-match |
| `backend/src/test/.../BoundedStoreRegressionTest.kt` | test | request-response | `SessionRoutesTest.kt` | exact |
| `backend/src/test/.../CorsConfigTest.kt` | test | request-response | `HealthRoutesTest.kt` | role-match |
| `frontend/src/components/validation/ValidationPanel.test.tsx` | test | — | itself (fixture update) | self |

---

## Pattern Assignments

### `VizEventSerializersModule.kt` (new config/module, transform)

**Analog:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/Serialization.kt`

**Imports pattern to copy** (from `Serialization.kt` lines 1-13 and `EventSerializationTest.kt` lines 1-8):
```kotlin
package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.events.coroutine.*
import com.jh.proj.coroutineviz.events.job.*
import com.jh.proj.coroutineviz.events.flow.*
import com.jh.proj.coroutineviz.events.dispatcher.*
import com.jh.proj.coroutineviz.events.deferred.*
import com.jh.proj.coroutineviz.events.channel.*
// MutexEvents, SemaphoreEvents, DeadlockEvents, WaitingForChildren are in events.* directly
import com.jh.proj.coroutineviz.events.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
```

**Core pattern — SerializersModule declaration:**
```kotlin
val vizEventSerializersModule = SerializersModule {
    polymorphic(VizEvent::class) {
        // coroutine package (8)
        subclass(CoroutineBodyCompleted::class)
        subclass(CoroutineCancelled::class)
        subclass(CoroutineCompleted::class)
        subclass(CoroutineCreated::class)
        subclass(CoroutineFailed::class)
        subclass(CoroutineResumed::class)
        subclass(CoroutineStarted::class)
        subclass(CoroutineSuspended::class)
        // job package (4)
        subclass(JobCancellationRequested::class)
        subclass(JobJoinCompleted::class)
        subclass(JobJoinRequested::class)
        subclass(JobStateChanged::class)
        // flow package (13) — 7 need @SerialName added before registration
        subclass(FlowBackpressure::class)
        subclass(FlowBufferOverflow::class)
        subclass(FlowCollectionCancelled::class)
        subclass(FlowCollectionCompleted::class)
        subclass(FlowCollectionStarted::class)
        subclass(FlowCreated::class)
        subclass(FlowOperatorApplied::class)
        subclass(FlowValueEmitted::class)
        subclass(FlowValueFiltered::class)
        subclass(FlowValueTransformed::class)
        subclass(SharedFlowEmission::class)
        subclass(SharedFlowSubscription::class)
        subclass(StateFlowValueChanged::class)
        // dispatcher package (2)
        subclass(DispatcherSelected::class)
        subclass(ThreadAssigned::class)
        // deferred package (3)
        subclass(DeferredAwaitCompleted::class)
        subclass(DeferredAwaitStarted::class)
        subclass(DeferredValueAvailable::class)
        // channel package (9)
        subclass(ChannelBufferStateChanged::class)
        subclass(ChannelClosed::class)
        subclass(ChannelCreated::class)
        subclass(ChannelReceiveCompleted::class)
        subclass(ChannelReceiveStarted::class)
        subclass(ChannelReceiveSuspended::class)
        subclass(ChannelSendCompleted::class)
        subclass(ChannelSendStarted::class)
        subclass(ChannelSendSuspended::class)
        // mutex (6) — in MutexEvents.kt
        subclass(MutexCreated::class)
        subclass(MutexLockAcquired::class)
        subclass(MutexLockRequested::class)
        subclass(MutexQueueChanged::class)
        subclass(MutexTryLockFailed::class)
        subclass(MutexUnlocked::class)
        // semaphore (6) — in SemaphoreEvents.kt
        subclass(SemaphoreAcquireRequested::class)
        subclass(SemaphoreCreated::class)
        subclass(SemaphorePermitAcquired::class)
        subclass(SemaphorePermitReleased::class)
        subclass(SemaphoreStateChanged::class)
        subclass(SemaphoreTryAcquireFailed::class)
        // actor (7) — verify package location
        subclass(ActorClosed::class)
        subclass(ActorCreated::class)
        subclass(ActorMailboxChanged::class)
        subclass(ActorMessageProcessed::class)
        subclass(ActorMessageProcessing::class)
        subclass(ActorMessageSent::class)
        subclass(ActorStateChanged::class)
        // select (4)
        subclass(SelectClauseRegistered::class)
        subclass(SelectClauseWon::class)
        subclass(SelectCompleted::class)
        subclass(SelectStarted::class)
        // deadlock (2) — in DeadlockEvents.kt
        subclass(DeadlockDetected::class)
        subclass(PotentialDeadlockWarning::class)
        // anti-pattern (1)
        subclass(AntiPatternDetected::class)
        // structured concurrency (1) — in WaitingForChildren.kt
        subclass(WaitingForChildren::class)
    }
}

/** Shared Json instance — the one source of truth for all serialization in the app. */
val appJson = Json {
    serializersModule = vizEventSerializersModule
    encodeDefaults = true
    ignoreUnknownKeys = true
}
```

**Note on 7 flow classes without `@SerialName`:** `FlowBackpressure`, `FlowOperatorApplied`, `FlowValueFiltered`, `FlowValueTransformed`, `SharedFlowEmission`, `SharedFlowSubscription`, `StateFlowValueChanged` need `@SerialName("FlowBackpressure")` etc. added to each class file before they can be registered. The annotation value must match the class's `override val kind` property value.

---

### `Serialization.kt` (change — install `appJson`, expose for SSE)

**Analog:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/Serialization.kt` (self)

**Current core pattern** (lines 15-24) — replace `json()` with `json(appJson)`:
```kotlin
// CURRENT (lines 15-24):
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json()   // <-- default Json, no SerializersModule
    }
    routing {
        get("/json/kotlinx-serialization") {
            call.respond(mapOf("hello" to "world"))
        }
    }
}

// AFTER FIX-01:
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(appJson)  // appJson defined in VizEventSerializersModule.kt
    }
    // optional: expose via Application attribute for other modules
    // attributes.put(AppJsonKey, appJson)
    routing {
        get("/json/kotlinx-serialization") {
            call.respond(mapOf("hello" to "world"))
        }
    }
}
```

**SSE route fix** — `SessionRoutes.kt` lines 199 and 215 use bare `Json.encodeToString(event)`. Replace with:
```kotlin
// Before (line 199 / 215):
data = Json.encodeToString(event)

// After:
data = appJson.encodeToString(VizEvent.serializer(), event)
// or with reified generics if called from suspend context:
// data = appJson.encodeToString<VizEvent>(event)
```

---

### `Monitoring.kt` (change — rename endpoint, add Prometheus alongside existing)

**Analog:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/Monitoring.kt` (self, lines 1-24)

**Current pattern** (lines 19-23) to extend:
```kotlin
// Rename /metrics-micrometer -> /metrics per ADR-020
routing {
    get("/metrics") {   // was: /metrics-micrometer
        call.respond(appMicrometerRegistry.scrape())
    }
}
```

The `PrometheusMeterRegistry` is already instantiated at line 11 — no change needed there. Existing `wireMetrics(appMicrometerRegistry)` call at line 17 stays; the `wireMetrics` function itself gains 5 new registrations.

---

### `MetricsWiring.kt` (change — add 5 ADR-020 metrics)

**Analog:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt` (self, lines 1-24)

**Current pattern to copy and extend** (lines 4-7, 14-23):
```kotlin
// Existing import pattern (lines 1-7):
import io.micrometer.core.instrument.Gauge
import io.micrometer.prometheus.PrometheusMeterRegistry
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

// Existing Gauge pattern (lines 15-22):
Gauge.builder("viz.sessions.active") { SessionManager.listSessions().size.toDouble() }
    .description("Number of active visualization sessions")
    .register(registry)
```

**New imports to add** to `MetricsWiring.kt`:
```kotlin
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
```

**New metrics to add** inside `wireMetrics()` following the same pattern:
```kotlin
// events.emitted counter — increment in VizSession.send() via callback
val eventsEmittedCounter = Counter.builder("events.emitted")
    .description("Total events emitted across all sessions")
    .register(registry)

// events.dropped counter — increment via EventStore.onEvict callback
val eventsDroppedCounter = Counter.builder("events.dropped")
    .description("Events dropped due to bounded EventStore capacity")
    .register(registry)

// events.buffer.size gauge — per session, tagged; wire via session creation callback
// (see Open Questions in RESEARCH.md — inject via onSessionCreated callback)

// scenario.duration timer — record in ScenarioRunnerRoutes wrapping the run call
val scenarioDurationTimer = Timer.builder("scenario.duration")
    .description("Time to complete scenario execution")
    .register(registry)

// event.processing.duration timer — record in VizSession.send()
val eventProcessingTimer = Timer.builder("event.processing.duration")
    .description("Time to process and broadcast a single event")
    .register(registry)
```

---

### `routes/HealthRoutes.kt` (change — add /api/health, /api/live, /api/ready)

**Analog:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutes.kt` (self, lines 1-56)

**Current `HealthStatus` data class** (lines 17-23) — extend with `version` and `components`:
```kotlin
// CURRENT (lines 17-23):
@Serializable
data class HealthStatus(
    val status: String,
    val sessions: Int,
    val uptimeMs: Long,
    val memory: MemoryInfo,
)

// AFTER PROD-01:
@Serializable
data class HealthStatus(
    val status: String,
    val version: String,
    val sessions: Int,
    val uptimeMs: Long,
    val memory: MemoryInfo,
    val components: Map<String, String> = emptyMap(),
)
```

**Shared helper pattern** — extract the existing route body (lines 29-55) into a private suspend function to avoid duplication across `/health`, `/api/health`:
```kotlin
private suspend fun ApplicationCall.respondHealth() {
    val runtime = Runtime.getRuntime()
    val maxMb = runtime.maxMemory() / (1024 * 1024)
    val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val usagePercent = if (maxMb > 0) (usedMb.toDouble() / maxMb * 100) else 0.0
    val memory = MemoryInfo(usedMb = usedMb, maxMb = maxMb, usagePercent = usagePercent)
    val sessions = SessionManager.listSessions().size
    val uptimeMs = System.currentTimeMillis() - startTime
    val healthy = usagePercent < 90.0
    val status = HealthStatus(
        status = if (healthy) "UP" else "DEGRADED",
        version = "0.0.1",
        sessions = sessions,
        uptimeMs = uptimeMs,
        memory = memory,
        components = mapOf("sessionManager" to "UP", "memory" to if (healthy) "UP" else "DEGRADED"),
    )
    val httpStatus = if (healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
    respond(httpStatus, status)
}
```

**New routes** (call the shared helper):
```kotlin
fun Route.registerHealthRoutes() {
    get("/health") { call.respondHealth() }  // alias kept

    route("/api") {
        get("/health") { call.respondHealth() }

        get("/live") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
        }

        get("/ready") {
            val sessions = SessionManager.listSessions()  // if reachable -> ready
            val runtime = Runtime.getRuntime()
            val usagePercent = (runtime.totalMemory() - runtime.freeMemory()).toDouble() /
                runtime.maxMemory() * 100
            if (usagePercent < 95.0) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "DOWN", "reason" to "high memory"))
            }
        }
    }
}
```

---

### `Application.kt` (change — add SessionManager.configure() before configureRouting())

**Analog:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/Application.kt` (self, lines 1-16)

**Current pattern** (lines 9-16) — add `SessionManager.configure()` call:
```kotlin
// CURRENT:
fun Application.module() {
    configureCompression()
    configureHTTP()
    configureAuth()
    configureMonitoring()
    configureSerialization()
    configureRouting()
}

// AFTER FND-02 (add before configureRouting()):
fun Application.module() {
    configureCompression()
    configureHTTP()
    configureAuth()
    configureMonitoring()
    configureSerialization()
    // FND-02: wire bounded EventStore from yaml config
    val maxEvents = environment.config.propertyOrNull("session.maxEvents")
        ?.getString()?.toIntOrNull() ?: 10_000
    SessionManager.configure(maxEventsPerSession = maxEvents)
    configureRouting()
}
```

**Config read pattern** — copy from `HTTP.kt` lines 23-29:
```kotlin
val config = environment.config
config.propertyOrNull("session.maxEvents")?.getString()?.toIntOrNull() ?: 10_000
```

---

### `wrappers/VizScope.kt` (change — FIX-03, 4-line when-branch fix)

**Analog:** self (lines 182-209)

**Current broken branch** (lines 182-195) — replace with:
```kotlin
// REMOVE lines 182-195 (the message-contains-label branch):
// cause !is CancellationException && cause.message?.contains(ctx.label ?: "unknown") == true -> {

// REPLACE WITH (cause-type classification, not message matching):
cause !is CancellationException -> {
    session.send(ctx.coroutineFailed(cause::class.simpleName, cause.message))
    session.send(
        ctx.jobStateChanged(
            isActive = false,
            isCompleted = false,
            isCancelled = false,   // FAILED, not cancelled
            childrenCount = coroutineContext[Job]?.children?.count() ?: 0,
        ),
    )
}
```

The `else -> throw IllegalArgumentException(...)` that follows becomes unreachable and can be removed.

---

### `scenarios/ScenarioRunner.kt` (change — FIX-04, 2-line cancellation fix)

**Analog:** self (lines 100-126)

**Current broken lines** (117, 123-124):
```kotlin
// Line 117 — UNCOMMENT this:
//            child1.cancel()

// Lines 123-124 — REMOVE these (root job cancel is wrong):
delay(1000)
job.cancel()
```

**After fix**, the cancellation scenario block (around lines 95-126) should read:
```kotlin
// Cancel the long-running child (add a small delay so it's visibly mid-flight)
vizDelay(500)
child1.cancel()
logger.debug("Parent completed")
// No delay(1000); job.cancel() — normal-child runs to completion naturally
```

---

### `application.yaml` (change — add session.maxEvents key)

**Analog:** `HTTP.kt` config read pattern (lines 28-29 shows `config.propertyOrNull("cors.allowedOrigins")`)

**YAML addition** — add to `backend/src/main/resources/application.yaml`:
```yaml
session:
  maxEvents: ${SESSION_MAX_EVENTS:10000}
```

---

### `backend/Dockerfile` (change — PROD-02 JVM flag + logstash dep)

**No analog in codebase.** Two changes:
1. Add to `backend/build.gradle.kts` dependencies: `implementation("net.logstash.logback:logstash-logback-encoder:8.1")`
2. In the runtime stage ENTRYPOINT: `ENTRYPOINT ["java", "-Dlogback.configurationFile=/app/logback-prod.xml", "-jar", "app.jar"]`
3. Copy logback-prod.xml to `/app/logback-prod.xml` in the runtime stage: `COPY --from=build /app/src/main/resources/logback-prod.xml /app/logback-prod.xml`

---

### `frontend/src/types/api.ts` (change — FIX-02 ValidationResult + TimingReport)

**Analog:** self (lines 615-654)

**Current wrong types** (lines 615-654) — replace with:
```typescript
// Replace ValidationResult (lines 615-622) with:
export interface ValidationResult {
  sessionId: string
  results: Array<BackendValidationResult>
  timing: BackendTimingReport
}

export interface BackendValidationResult {
  type: 'Pass' | 'Fail'
  ruleName: string
  message: string
  details?: string   // only on Fail
}

// Replace TimingReport (lines 639-647) with backend's actual shape:
export interface BackendTimingReport {
  coroutineDurations: Record<string, number>
  suspensionDurations: Record<string, number[]>
  totalDuration: number
}
```

Remove (now unused): `ValidationError`, `ValidationWarning`, `TimingReport`, `LatencyBucket` interfaces at lines 623-654.

---

### `frontend/src/components/validation/ValidationPanel.tsx` (change — FIX-02)

**Analog:** self (lines 82-118)

**Current broken reads** (lines 82-110) — `data.valid`, `data.errors`, `data.warnings` — replace with:
```tsx
// Replace the results block (lines 82-118) with:
{data && (
  <motion.div ...>
    {/* Summary: derive pass/fail from results */}
    {(() => {
      const failures = data.results.filter(r => r.type === 'Fail')
      const passes = data.results.filter(r => r.type === 'Pass')
      return (
        <>
          <ValidationPassCard
            valid={failures.length === 0}
            errorCount={failures.length}
            warningCount={0}   // backend has no Warning type
          />

          {failures.length > 0 && (
            <div className="space-y-2">
              <h3 className="text-sm font-semibold text-danger">
                Errors ({failures.length})
              </h3>
              {failures.map((r, idx) => (
                <ValidationErrorCard key={`${r.ruleName}-${idx}`} error={r} index={idx} />
              ))}
            </div>
          )}

          {passes.length > 0 && (
            <div className="space-y-2">
              <h3 className="text-sm font-semibold text-success">
                Passes ({passes.length})
              </h3>
              {/* render pass cards */}
            </div>
          )}

          <Divider />
          <div className="space-y-2">
            <h3 className="text-sm font-semibold text-default-600">Timing Report</h3>
            <TimingReportView timing={data.timing} />
          </div>
        </>
      )
    })()}
  </motion.div>
)}
```

---

### `frontend/src/components/validation/TimingReportView.tsx` (change — FIX-02)

**Analog:** `ValidationPanel.tsx` component pattern (same directory, same import style)

The component currently renders `totalDurationNanos`, `eventCount`, etc. Replace all field reads with the backend shape: `timing.totalDuration`, `timing.coroutineDurations` (Record), `timing.suspensionDurations` (Record). The import changes from `TimingReport` to `BackendTimingReport`.

---

## New Test Files

### `VizEventSerializersModuleTest.kt` (new unit test)

**Analog:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/events/EventSerializationTest.kt` (exact match — same test structure, same Json configuration pattern)

**Copy test setup** from `EventSerializationTest.kt` lines 11-16:
```kotlin
package com.jh.proj.coroutineviz.events

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class VizEventSerializersModuleTest {
    // D-04: completeness guard — every subclass must be registered
    @Test
    fun `all VizEvent subclasses are registered in SerializersModule`() {
        val module = vizEventSerializersModule
        val knownSubclasses = listOf(
            CoroutineCreated::class,
            CoroutineStarted::class,
            // ... all 66 ...
        )
        for (klass in knownSubclasses) {
            // Verify the class is resolvable via polymorphic scope
            assertNotNull(
                module.getPolymorphic(VizEvent::class, klass.simpleName!!),
                "Missing registration for ${klass.simpleName}"
            )
        }
    }

    @Test
    fun `VizEvent polymorphic round-trip via appJson`() {
        val event: VizEvent = CoroutineCreated(
            sessionId = "s", seq = 1, tsNanos = 0,
            coroutineId = "c", jobId = "j",
            parentCoroutineId = null, scopeId = "sc", label = null,
        )
        val serialized = appJson.encodeToString(VizEvent.serializer(), event)
        val deserialized = appJson.decodeFromString(VizEvent.serializer(), serialized)
        assertEquals(event, deserialized)
    }
}
```

---

### Integration/Regression Test Files (new — `testApplication` pattern)

**Analog:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutesTest.kt` and `SessionRoutesTest.kt` — **exact match**.

**Copy this boilerplate for all new integration tests** (`SerializersModuleIntegrationTest.kt`, `ExceptionScenarioRegressionTest.kt`, `CancellationScenarioRegressionTest.kt`, `BoundedStoreRegressionTest.kt`):

```kotlin
package com.jh.proj.coroutineviz.routes  // or appropriate package

import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExampleRegressionTest {
    @BeforeEach fun setUp() { SessionManager.clearAll() }
    @AfterEach fun tearDown() { SessionManager.clearAll() }

    private fun ApplicationTestBuilder.jsonClient() =
        createClient { install(ContentNegotiation) { json() } }

    @Test
    fun `scenario name regression`() = testApplication {
        application { module() }
        val client = jsonClient()
        // ... test body ...
    }
}
```

**`BoundedStoreWiringTest.kt`** — copy `MetricsWiringTest.kt` boilerplate (lines 1-38) as closest match since it also uses `application { module() }` and checks application-level config.

**`CorsConfigTest.kt`** — copy `HealthRoutesTest.kt` boilerplate (lines 26-42, the `jsonClient()` helper + `testApplication` setup). CORS test makes a request with an `Origin` header and asserts the response has `Access-Control-Allow-Origin`.

---

### `frontend/src/components/validation/ValidationPanel.test.tsx` (fixture update)

**Analog:** self (lines 1-80)

**Keep:** `createWrapper()` helper (lines 24-43), `vi.mock('@/lib/api-client')` pattern (lines 15-20), `describe`/`it`/`beforeEach` structure.

**Change only the mock data shape** (lines 61-74 — old `ValidationResult` fixture):
```typescript
// CURRENT (lines 61-74) — wrong shape:
const result: ValidationResult = {
  sessionId: 'session-1',
  valid: true,
  errors: [],
  warnings: [],
  timing: { totalDurationNanos: 1_000_000_000, ... }
}

// AFTER FIX-02 — real backend shape:
const result: ValidationResult = {
  sessionId: 'session-1',
  results: [
    { type: 'Pass', ruleName: 'LifecycleOrder', message: 'All coroutine lifecycle events are ordered correctly' },
  ],
  timing: {
    coroutineDurations: { 'coroutine-1': 1000 },
    suspensionDurations: { 'coroutine-1': [500] },
    totalDuration: 1000,
  },
}
```

---

## Shared Patterns

### `testApplication` integration test boilerplate
**Source:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutesTest.kt` lines 26-42 and `SessionRoutesTest.kt` lines 20-36
**Apply to:** All new backend regression tests
```kotlin
@BeforeEach fun setUp() { SessionManager.clearAll() }
@AfterEach fun tearDown() { SessionManager.clearAll() }

private fun ApplicationTestBuilder.jsonClient() =
    createClient { install(ContentNegotiation) { json() } }

// Test structure:
fun `test name`() = testApplication {
    application { module() }
    val client = jsonClient()
    // ... assertions ...
}
```

### `environment.config.propertyOrNull` config read pattern
**Source:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/HTTP.kt` lines 23-29
**Apply to:** `Application.kt` FND-02 `session.maxEvents` read
```kotlin
val config = environment.config
config.propertyOrNull("key")?.getString() ?: "default"
```

### Micrometer `Gauge.builder` registration pattern
**Source:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt` lines 15-22
**Apply to:** All 5 new ADR-020 metrics in `MetricsWiring.kt`
```kotlin
Gauge.builder("metric.name") { value.toDouble() }
    .description("Human readable description")
    .register(registry)
```

### Ktor `call.respond(httpStatus, dataClass)` pattern
**Source:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutes.kt` lines 53-54
**Apply to:** New `/api/live` and `/api/ready` endpoints
```kotlin
call.respond(httpStatus, status)   // status is a @Serializable data class
// or for simple map responses:
call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
```

### `@Serializable data class` for route DTOs
**Source:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutes.kt` lines 10-23
**Apply to:** Updated `HealthStatus` with `version` and `components` fields
```kotlin
@Serializable
data class HealthStatus(
    val status: String,
    val sessions: Int,
    val uptimeMs: Long,
    val memory: MemoryInfo,
)
```

### `Route.register*Routes()` extension function pattern
**Source:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutes.kt` line 27
**Apply to:** All route registration functions. Routes are mounted via `fun Route.registerXxxRoutes()` called from `Routing.kt`.

---

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `backend/Dockerfile` | config | — | No Dockerfile pattern analog in the codebase to copy from; changes are purely additive (ENTRYPOINT flag + COPY instruction) |

---

## Metadata

**Analog search scope:** `backend/src/main/`, `backend/src/test/`, `frontend/src/`
**Files read:** 18 source files + test files
**Pattern extraction date:** 2026-06-11
