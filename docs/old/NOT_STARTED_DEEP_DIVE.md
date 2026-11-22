# Coroutine Visualizer - Deep Dive: All Not-Started Features

**Status:** Planning & Preparation Guide
**Last Updated:** February 2026
**Source Documents:** `docs/BUSINESS_ANALYSIS_V2.md`, `docs/COROUTINE-VISUALIZER-BUSINESS-ANALYSIS.md`, `docs/old/BUSINESS_ANALYSIS.md`, `backend/docs/TASKS-DEEPDIVE.md`

---

## Overview

This document consolidates **every feature, task, and initiative that has NOT yet been started** across all business analysis and planning documents. Each item includes a deep dive on what it is, why it matters, dependencies, implementation guidance, estimated effort, and preparation steps.

Items are organized into tiers by priority and grouped by domain.

---

## Status Legend

| Status | Meaning |
|--------|---------|
| NOT STARTED | No code or work has begun |
| DESIGNED | Architecture/spec exists in docs but no implementation |
| PARTIALLY DONE | Related work exists but the specific item is not started |

---

## Table of Contents

1. [Tier 1 - Foundation & Documentation (Do First)](#tier-1---foundation--documentation)
2. [Tier 2 - Backend Core Features](#tier-2---backend-core-features)
3. [Tier 3 - Advanced Backend Capabilities](#tier-3---advanced-backend-capabilities)
4. [Tier 4 - Frontend Advanced Features](#tier-4---frontend-advanced-features)
5. [Tier 5 - Tooling & IDE Integration](#tier-5---tooling--ide-integration)
6. [Tier 6 - Business & Go-to-Market](#tier-6---business--go-to-market)
7. [Dependency Graph](#dependency-graph)
8. [Recommended Execution Order](#recommended-execution-order)

---

## Tier 1 - Foundation & Documentation

These items unblock other work and have zero external dependencies.

---

### 1.1 Health Endpoint (`/health`)

**Source:** TASKS-DEEPDIVE #5
**Status:** NOT STARTED
**Effort:** 2-4 hours
**Priority:** Critical (blocks deployment readiness)

**What:** Add `/health` (readiness/liveness) endpoint returning 200/UP with optional component checks (EventBus alive, session count, memory).

**Why:** Required for any container orchestration (k8s, Docker Compose). Without it, no automated deployment or monitoring.

**Dependencies:** None

**How to prepare:**
1. Review existing Ktor routes in `backend/src/main/kotlin/.../Routing.kt`
2. Decide on health check format (simple JSON `{"status": "UP"}` or Spring Boot Actuator-style with components)
3. Identify components to check: SessionManager availability, EventBus responsiveness, memory thresholds

**Implementation outline:**
```kotlin
// In routes
get("/health") {
    val status = healthService.check()
    call.respond(if (status.healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, status)
}

data class HealthStatus(
    val healthy: Boolean,
    val components: Map<String, ComponentHealth>
)
```

**Done when:** k8s probes can hit `/health`; returns 200 when healthy, 503 when not.

---

### 1.2 Logging Profiles (dev/prod)

**Source:** TASKS-DEEPDIVE #4
**Status:** NOT STARTED
**Effort:** 2-3 hours
**Priority:** High

**What:** Add logback profiles for development (verbose DEBUG) and production (clean INFO). Remove stray `println` calls in scenario code. Add HTTP request samples for IDE testing.

**Why:** Current logging is noisy, mixing debug output with useful info. Production deployments need clean logs.

**Dependencies:** None

**How to prepare:**
1. Audit `backend/src/main/resources/logback.xml` for current config
2. Grep for `println` in scenario/test code - replace with proper logger calls
3. Review `backend/http-requests/` for existing `.http` files

**Implementation outline:**
- Create `logback-dev.xml` and `logback-prod.xml` (or use profiles within single file)
- Set via environment variable: `-Dlogback.configurationFile=logback-prod.xml` or Spring-style profile
- Replace all `println` with `private val logger = LoggerFactory.getLogger(...)`
- Add `.http` files for: create session, run scenario, connect SSE, check health

**Done when:** `INFO` logs are clean in prod profile; HTTP samples run in IntelliJ HTTP client.

---

### 1.3 Config Hygiene for Frontend

**Source:** TASKS-DEEPDIVE #2
**Status:** NOT STARTED
**Effort:** 2-3 hours
**Priority:** High

**What:** Make CORS origins and ports configurable via environment variables or Gradle properties. Keep `localhost:3000` as default.

**Why:** Currently CORS is hardcoded. Any deployment beyond localhost (Docker, cloud, different port) requires code changes.

**Dependencies:** None

**How to prepare:**
1. Read `backend/src/main/kotlin/.../HTTP.kt` for current CORS setup
2. Review `backend/src/main/resources/application.yaml` for existing config structure
3. Decide config hierarchy: application.yaml < environment variables < system properties

**Implementation outline:**
```yaml
# application.yaml
app:
  cors:
    allowed-origins:
      - "http://localhost:3000"
      - "${CORS_ORIGIN:}"
  server:
    port: ${SERVER_PORT:8080}
```

```kotlin
// HTTP.kt
val origins = environment.config.propertyOrNull("app.cors.allowed-origins")
    ?.getList() ?: listOf("http://localhost:3000")
install(CORS) {
    origins.forEach { allowHost(it) }
}
```

**Done when:** Switching FE origin requires only env var change; localhost still works out of the box.

---

### 1.4 Sample Session Payload Endpoint

**Source:** TASKS-DEEPDIVE #3
**Status:** NOT STARTED
**Effort:** 3-5 hours
**Priority:** High

**What:** Add `GET /api/sessions/sample` returning a deterministic session snapshot with hardcoded events, coroutines, thread activity, and hierarchy data.

**Why:** Frontend developers can build and test UI without running scenarios. Useful for demos, screenshots, and offline development.

**Dependencies:** None (uses existing DTO types)

**How to prepare:**
1. Review `SessionSnapshotResponse` and related DTOs in backend models
2. Design a representative sample: 5-8 coroutines, 3 dispatchers, 15-20 events covering all event types
3. Include all states: active, completed, cancelled, suspended, waiting-for-children

**Implementation outline:**
- Create `SampleDataProvider.kt` with hardcoded data matching real scenario output
- Add route `GET /api/sessions/sample` returning the static payload
- Add corresponding `.http` example file

**Done when:** FE can render full UI (tree, timeline, threads, dispatchers) against this static endpoint.

---

### 1.5 FE Integration Documentation

**Source:** TASKS-DEEPDIVE #6
**Status:** NOT STARTED
**Effort:** 4-6 hours
**Priority:** High (referenced but doesn't exist: `docs/FE-INTEGRATION.md`)

**What:** Write `docs/FE-INTEGRATION.md` describing the SSE replay+live behavior, REST payload shapes, common integration flows, error handling, and event format normalization.

**Why:** The frontend README references integration patterns but there's no dedicated contract document. Frontend developers need this to build against the API confidently.

**Dependencies:** None (documents existing behavior)

**How to prepare:**
1. Read `backend/src/main/kotlin/.../routes/SessionRoutes.kt` for SSE implementation
2. Trace the SSE flow: connection -> history replay -> live events -> disconnect
3. Document event format: both PascalCase and kebab-case normalization
4. List all REST endpoints with request/response examples

**Content outline:**
```markdown
# Frontend Integration Guide

## SSE Contract
- Connection: GET /api/sessions/:id/stream
- History replay: All past events sent first (ordered by sequence)
- Live events: New events streamed as they occur
- Event format: { type: "CoroutineCreated", data: {...} }
- Normalization: PascalCase <-> kebab-case mapping

## REST Endpoints (with examples)
## Common Flows
1. Create session -> Run scenario -> Stream events
2. Browse sessions -> Open detail -> Toggle live stream

## Error Handling
## Reconnection Strategy
```

**Done when:** A frontend developer can integrate without asking backend team any questions.

---

### 1.6 OpenAPI Polish

**Source:** TASKS-DEEPDIVE #7
**Status:** NOT STARTED
**Effort:** 4-6 hours
**Priority:** Medium

**What:** Enrich OpenAPI/Swagger annotations with meaningful descriptions, field-level examples, and response schemas so API clients can be auto-generated.

**Why:** Current OpenAPI at `/openapi` has minimal descriptions. Client generation (TypeScript, Kotlin) produces unusable stubs without proper schemas.

**Dependencies:** None

**How to prepare:**
1. Visit `http://localhost:8080/openapi` and audit current schema quality
2. Identify DTOs that need `@Description` or `@Schema` annotations
3. Review Ktor OpenAPI plugin documentation for annotation patterns

**Implementation outline:**
- Add descriptions to all route parameters
- Add `@Serializable` DTO field descriptions
- Include example payloads in OpenAPI spec
- Verify generated spec is valid with Swagger Editor

**Done when:** Auto-generated TypeScript client from OpenAPI spec compiles and has meaningful types.

---

### 1.7 API Quickstart Documentation

**Source:** TASKS-DEEPDIVE #1
**Status:** NOT STARTED
**Effort:** 3-4 hours
**Priority:** High

**What:** Create `docs/API-QUICKSTART.md` with key endpoints, example requests/responses, and curl snippets. Update backend README to link to it.

**Why:** New developers currently need to read source code to understand the API. A quickstart dramatically reduces onboarding time.

**Dependencies:** None

**How to prepare:**
1. List all public endpoints from route files
2. For each endpoint, capture actual response by running the server
3. Write curl examples that work copy-paste

**Content outline:**
```markdown
# API Quickstart

## Create a Session
curl -X POST http://localhost:8080/api/sessions?name=my-session

## Run a Scenario
curl -X POST http://localhost:8080/api/scenarios/nested-launch/run?sessionId=...

## Stream Events (SSE)
curl -N http://localhost:8080/api/sessions/{id}/stream

## Get Hierarchy
curl http://localhost:8080/api/sessions/{id}/hierarchy
```

**Done when:** New dev can call SSE and REST endpoints without reading source code.

---

## Tier 2 - Backend Core Features

Features that extend the existing backend with essential capabilities.

---

### 2.1 InstrumentedChannel

**Source:** COROUTINE-VISUALIZER-BUSINESS-ANALYSIS Section 4.5, old/BUSINESS_ANALYSIS Section 4.5
**Status:** DESIGNED (full code spec exists, zero implementation)
**Effort:** 2-3 days
**Priority:** High

**What:** Create `InstrumentedChannel<E>` wrapper that delegates to a real `Channel<E>` and emits events for `send`, `receive`, `trySend`, `tryReceive`, `close`, and `cancel` operations. Track buffer depth, suspension on send/receive, and sender/receiver coroutine correlation.

**Why:** Channels are a core coroutine primitive. Without channel visualization, we can't demonstrate producer-consumer, fan-out, fan-in, or backpressure patterns.

**Dependencies:**
- Existing `VizSession` and `EventBus` infrastructure (DONE)
- Channel event types need to be defined (partially done - check `events/` package)

**How to prepare:**
1. Review existing wrapper pattern in `VizScope.kt` and `VizDispatchers.kt`
2. Check if `ChannelEvent` types exist in `events/` package; if not, define them
3. Study the design spec in COROUTINE-VISUALIZER-BUSINESS-ANALYSIS Section 4.5

**Implementation outline:**
```kotlin
class InstrumentedChannel<E>(
    private val delegate: Channel<E>,
    private val channelId: String,
    private val session: VizSession
) : Channel<E> by delegate {

    override suspend fun send(element: E) {
        val coroutineId = currentCoroutineContext()[VizContext]?.coroutineId
        session.emit(ChannelSendRequested(channelId, coroutineId, element.preview()))

        val start = Clock.System.now()
        delegate.send(element)
        val suspended = (Clock.System.now() - start).inWholeMilliseconds > 0

        session.emit(ChannelSendCompleted(channelId, coroutineId, suspended))
    }

    override suspend fun receive(): E {
        // Similar pattern
    }
}
```

**Event types needed:**
- `ChannelCreated(channelId, capacity, creatorCoroutineId)`
- `ChannelSendRequested(channelId, senderId, valuePreview, bufferSize)`
- `ChannelSendCompleted(channelId, senderId, suspended)`
- `ChannelReceiveRequested(channelId, receiverId, bufferSize)`
- `ChannelReceiveCompleted(channelId, receiverId, valuePreview, suspended)`
- `ChannelClosed(channelId, cause)`

**Testing scenarios to add:**
- Producer-consumer with bounded buffer (backpressure)
- Fan-out: one producer, multiple consumers
- Rendezvous channel (capacity=0) showing perfect handoff

**Done when:** Channel operations appear in event stream; new scenarios demonstrate channel patterns visually.

---

### 2.2 Persistence & Database Storage

**Source:** TASKS-DEEPDIVE #11, BUSINESS_ANALYSIS Phase 4, BUSINESS_ANALYSIS_V2 Phase 3
**Status:** NOT STARTED
**Effort:** 1-2 weeks
**Priority:** High (blocks production use)

**What:** Move session and event storage from in-memory `ConcurrentHashMap` / `ConcurrentLinkedQueue` to a pluggable storage backend. Support at minimum JDBC (H2/PostgreSQL) with fallback to in-memory for development.

**Why:** Currently all data is lost on server restart. No production deployment is viable without persistence. Session history, event replay, and comparative analysis all require durable storage.

**Dependencies:**
- None for the interface; JDBC driver for implementation

**How to prepare:**
1. Review current storage in `EventStore.kt` and `SessionManager.kt` - identify all read/write operations
2. Design storage interface that abstracts current in-memory implementation
3. Choose ORM/query approach: Exposed (Kotlin-native), jOOQ, or raw JDBC
4. Design schema for sessions, events, coroutines, and projections

**Implementation outline:**

**Step 1: Define storage interfaces**
```kotlin
interface SessionStore {
    suspend fun create(session: SessionRecord): String
    suspend fun get(id: String): SessionRecord?
    suspend fun list(): List<SessionSummary>
    suspend fun delete(id: String)
}

interface EventStore {
    suspend fun append(sessionId: String, event: VizEvent)
    suspend fun getEvents(sessionId: String, sinceSequence: Long, limit: Int): List<VizEvent>
    suspend fun getEventCount(sessionId: String): Long
}
```

**Step 2: SQL Schema**
```sql
CREATE TABLE sessions (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    status VARCHAR(20) DEFAULT 'active'
);

CREATE TABLE events (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    sequence_num BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE(session_id, sequence_num)
);

CREATE INDEX idx_events_session_seq ON events(session_id, sequence_num);
```

**Step 3: Configuration**
```yaml
app:
  storage:
    type: "memory"  # or "jdbc"
    jdbc:
      url: "jdbc:postgresql://localhost:5432/coroutine_viz"
      driver: "org.postgresql.Driver"
    retention:
      max-age: "7d"
      max-events-per-session: 100000
```

**Step 4: Retention/cleanup job**
- Background coroutine that runs every hour
- Deletes sessions older than `max-age`
- Trims events exceeding `max-events-per-session`

**Done when:** Server restart preserves sessions; retention enforced; toggle via config between memory and JDBC.

---

### 2.3 Load Profiling & Backpressure

**Source:** TASKS-DEEPDIVE #10
**Status:** NOT STARTED
**Effort:** 3-5 days
**Priority:** Medium-High

**What:** Define target event throughput, benchmark the EventBus and SSE under load, tune `MutableSharedFlow` buffers, and add Micrometer counters for dropped/backpressured events.

**Why:** Unknown throughput limits are a production risk. Without backpressure metrics, silent data loss can occur under load.

**Dependencies:**
- Micrometer already integrated (DONE)
- EventBus exists (DONE)

**How to prepare:**
1. Review `MutableSharedFlow` configuration in `EventBus.kt` (replay, extraBufferCapacity, onBufferOverflow)
2. Review Micrometer setup for adding custom counters
3. Design a synthetic load generator (coroutine that emits N events/second)

**Implementation outline:**
1. **Benchmark harness:** Create `LoadTestScenario.kt` that launches N coroutines emitting events at configurable rates
2. **Metrics to add:**
   - `viz.events.emitted.total` (Counter)
   - `viz.events.dropped.total` (Counter)
   - `viz.events.buffer.size` (Gauge)
   - `viz.sse.clients.active` (Gauge)
   - `viz.sse.events.sent.total` (Counter per client)
3. **Tune buffers:** Test with 100, 1K, 10K events/sec; find breaking point
4. **Document limits:** Publish throughput ceiling in README/docs

**Done when:** Known throughput limits documented; Micrometer counters fire on drops; buffer sizes tuned.

---

### 2.4 Sampling & Retention Controls

**Source:** TASKS-DEEPDIVE #18
**Status:** NOT STARTED
**Effort:** 2-3 days
**Priority:** Medium

**What:** Configurable sampling on event types (e.g., emit only every Nth suspension event) and TTL-based retention that cleans up old events/sessions.

**Why:** High-frequency events (thread assignments, suspensions) can overwhelm storage and SSE. Sampling keeps data volume manageable while preserving important lifecycle events.

**Dependencies:**
- Persistence (#2.2) for TTL cleanup
- Load profiling (#2.3) to understand which event types are highest volume

**How to prepare:**
1. Categorize events by frequency: lifecycle (low) vs thread/suspension (high) vs channel/flow (variable)
2. Design sampling config: per-event-type rate (1.0 = all, 0.1 = 10%, 0.0 = drop)
3. Design retention config: TTL per session, max events per session

**Implementation outline:**
```yaml
app:
  sampling:
    default-rate: 1.0
    rates:
      ThreadAssigned: 0.5
      CoroutineSuspended: 0.5
      CoroutineResumed: 0.5
      FlowValueEmitted: 0.1
  retention:
    session-ttl: "24h"
    max-events-per-session: 50000
    cleanup-interval: "1h"
```

```kotlin
class SamplingEventFilter(private val config: SamplingConfig) {
    private val random = Random

    fun shouldEmit(event: VizEvent): Boolean {
        val rate = config.rates[event.kind] ?: config.defaultRate
        return random.nextDouble() < rate
    }
}
```

**Done when:** System stays stable under noisy flows; users informed of sampling via metadata; retention enforced.

---

### 2.5 Timeline Enrichment

**Source:** TASKS-DEEPDIVE #9
**Status:** NOT STARTED
**Effort:** 3-5 days
**Priority:** Medium

**What:** Compute derived durations (total, active time, suspended time) and thread/dispatcher spans per coroutine. Extend timeline API response with these aggregations.

**Why:** Raw events require frontend computation. Pre-computed durations enable richer timeline rendering and performance analysis.

**Dependencies:**
- Existing `ProjectionService` (DONE)
- Existing timeline endpoint (DONE)

**How to prepare:**
1. Review `ProjectionService.kt` for current projection logic
2. Define enriched timeline model:
   - Per-coroutine: total duration, active %, suspended %, thread switches count
   - Per-thread: occupancy segments (which coroutine when)
   - Per-dispatcher: utilization percentage

**Implementation outline:**
```kotlin
data class EnrichedTimeline(
    val coroutineId: String,
    val totalDurationMs: Long,
    val activeMs: Long,
    val suspendedMs: Long,
    val threadSwitches: Int,
    val segments: List<TimelineSegment>
)

data class TimelineSegment(
    val startMs: Long,
    val endMs: Long,
    val state: CoroutineState,
    val threadId: String?,
    val dispatcherId: String?
)
```

**Done when:** `/api/sessions/:id/timeline` returns enriched data; FE can render duration bars without computation.

---

### 2.6 Scenario Catalog UX Hook

**Source:** TASKS-DEEPDIVE #8
**Status:** PARTIALLY DONE (endpoint exists but metadata is limited)
**Effort:** 1-2 days
**Priority:** Medium

**What:** Extend `GET /api/scenarios` response with rich metadata: id, name, description, category (basic/realistic/advanced), expected coroutine count, expected event count, and difficulty level.

**Why:** Frontend currently hardcodes scenario information. A rich catalog endpoint enables dynamic scenario browsing, filtering, and better UX.

**Dependencies:** None

**How to prepare:**
1. Review current `/api/scenarios` response format
2. Define metadata schema for each scenario
3. Audit all existing scenarios for accurate metadata

**Implementation outline:**
```kotlin
data class ScenarioMetadata(
    val id: String,
    val name: String,
    val description: String,
    val category: String,  // "basic", "realistic", "advanced"
    val difficulty: Int,   // 1-5
    val expectedCoroutines: Int,
    val expectedEvents: Int,
    val concepts: List<String>,  // ["structured-concurrency", "cancellation"]
    val estimatedDurationMs: Long
)
```

**Done when:** FE can build a filterable scenario list from API alone without hardcoding.

---

## Tier 3 - Advanced Backend Capabilities

Features that enable production use, enterprise readiness, and advanced analysis.

---

### 3.1 Authentication, RBAC & Multi-Tenancy

**Source:** TASKS-DEEPDIVE #12, BUSINESS_ANALYSIS_V2 Premium/Enterprise tiers
**Status:** NOT STARTED
**Effort:** 1-2 weeks
**Priority:** Medium (blocks premium tier & enterprise)

**What:** Add authentication layer (API keys for basic, JWT for premium), role-based access control (viewer/editor/admin), and session namespacing per user/tenant.

**Why:** Required for any multi-user deployment, premium tier billing, and enterprise use. Currently anyone can access/delete any session.

**Dependencies:**
- Persistence (#2.2) - user/tenant data needs storage
- Premium tier billing (#6.2) - auth is prerequisite

**How to prepare:**
1. Choose auth strategy: API key (simplest), JWT (standard), OAuth2 (enterprise)
2. Design role model: `viewer` (read sessions), `editor` (create/run), `admin` (delete, configure)
3. Review Ktor authentication plugins: `ktor-server-auth`, `ktor-server-auth-jwt`
4. Design tenant isolation model for sessions

**Implementation outline:**

**Phase A: API Key Authentication (MVP)**
```kotlin
install(Authentication) {
    bearer("api-key") {
        authenticate { tokenCredential ->
            apiKeyStore.validate(tokenCredential.token)?.let { user ->
                UserIdPrincipal(user.id)
            }
        }
    }
}

authenticate("api-key") {
    route("/api/sessions") {
        // All session routes protected
    }
}
```

**Phase B: JWT + Roles**
```kotlin
install(Authentication) {
    jwt("jwt") {
        verifier(jwkProvider, issuer)
        validate { credential ->
            val roles = credential.payload.getClaim("roles").asList(String::class.java)
            JWTPrincipal(credential.payload, roles)
        }
    }
}
```

**Phase C: Tenant Isolation**
- Add `tenant_id` column to sessions table
- Filter all queries by authenticated tenant
- Admin role bypasses tenant filter

**Done when:** Unauthenticated requests return 401; sessions isolated per user; roles enforced on sensitive routes.

---

### 3.2 OpenTelemetry Export

**Source:** TASKS-DEEPDIVE #14, COROUTINE-VISUALIZER-BUSINESS-ANALYSIS Section 7.2
**Status:** DESIGNED (code spec exists)
**Effort:** 1-2 weeks
**Priority:** Medium

**What:** Optional exporter that maps coroutine visualization events to OpenTelemetry spans and logs. Events flow to any OTLP-compatible backend (Jaeger, Datadog, Grafana Tempo).

**Why:** Bridges the gap between educational visualization and production observability. Enterprise teams want coroutine insights alongside their existing tracing.

**Dependencies:**
- OpenTelemetry Java SDK dependency
- Configuration system for enabling/disabling

**How to prepare:**
1. Review COROUTINE-VISUALIZER-BUSINESS-ANALYSIS Section 7.2 for the `OTelCoroutineTracer` design
2. Add `opentelemetry-api` and `opentelemetry-sdk` to Gradle dependencies
3. Design mapping: VizEvent -> OTel Span attributes
4. Decide on batching strategy to minimize overhead

**Implementation outline:**
```kotlin
class OTelExporter(
    private val tracer: Tracer,
    private val enabled: Boolean
) : EventSubscriber {

    override fun onEvent(event: VizEvent) {
        if (!enabled) return

        when (event) {
            is CoroutineCreated -> {
                val span = tracer.spanBuilder("coroutine:${event.name}")
                    .setAttribute("coroutine.id", event.coroutineId)
                    .setAttribute("coroutine.parent", event.parentId ?: "root")
                    .setAttribute("dispatcher", event.dispatcherId)
                    .startSpan()
                spanRegistry[event.coroutineId] = span
            }
            is CoroutineCompleted -> {
                spanRegistry[event.coroutineId]?.end()
            }
            is CoroutineSuspended -> {
                spanRegistry[event.coroutineId]?.addEvent("suspended",
                    Attributes.of(AttributeKey.stringKey("reason"), event.reason))
            }
        }
    }
}
```

**Configuration:**
```yaml
app:
  exporters:
    otel:
      enabled: false
      endpoint: "http://localhost:4317"  # OTLP gRPC
      batch-size: 100
      flush-interval: "5s"
```

**Done when:** Enable flag sends spans to OTLP endpoint; coroutine hierarchy visible in Jaeger/Tempo; zero overhead when disabled.

---

### 3.3 Comparative Runs / Session Diffs

**Source:** TASKS-DEEPDIVE #17
**Status:** NOT STARTED
**Effort:** 3-5 days
**Priority:** Medium-Low

**What:** Endpoint that accepts two session IDs and returns a comparison: event count deltas, timing differences, coroutine count changes, and structural differences.

**Why:** Enables regression detection ("did my optimization actually help?"), A/B testing of coroutine patterns, and before/after visualizations.

**Dependencies:**
- Persistence (#2.2) - need both sessions available
- Timeline enrichment (#2.5) - for duration comparisons

**How to prepare:**
1. Define comparison dimensions: event counts by type, total durations, coroutine counts, thread utilization
2. Design response format that FE can render as a diff view
3. Start minimal - counts and durations only, expand later

**Implementation outline:**
```kotlin
data class SessionComparison(
    val sessionA: String,
    val sessionB: String,
    val eventCountDelta: Map<String, Int>,  // event_type -> (countB - countA)
    val coroutineCountDelta: Int,
    val totalDurationDeltaMs: Long,
    val avgCoroutineDurationDeltaMs: Long,
    val threadUtilizationDelta: Map<String, Double>
)

// Route
get("/api/sessions/compare") {
    val sessionA = call.parameters["a"]!!
    val sessionB = call.parameters["b"]!!
    val comparison = comparisonService.compare(sessionA, sessionB)
    call.respond(comparison)
}
```

**Done when:** FE can display a basic diff table highlighting differences between two session runs.

---

### 3.4 SDK / Library Packaging

**Source:** TASKS-DEEPDIVE #13, BUSINESS_ANALYSIS_V2 Phase 3
**Status:** NOT STARTED
**Effort:** 1-2 weeks
**Priority:** Medium

**What:** Extract instrumentation wrappers (VizScope, VizDispatchers, VizDeferred, VizFlow) into a standalone publishable library artifact that external applications can import to emit events.

**Why:** Currently instrumentation only works within the backend project. For the tool to be useful to real applications, developers need a drop-in library.

**Dependencies:**
- InstrumentedChannel (#2.1) - should be included
- API stability - wrappers need stable interfaces before publishing

**How to prepare:**
1. Identify all public API surface of instrumentation wrappers
2. Design minimal setup API (target: <10 lines to wire into any app)
3. Choose publishing strategy: Maven Central, GitHub Packages, or JitPack
4. Create a sample application that uses the SDK

**Implementation outline:**

**Multi-module Gradle setup:**
```
visualizer-for-coroutines/
  backend/
    sdk/                    # NEW: publishable library
      build.gradle.kts
      src/main/kotlin/
        VizScope.kt         # Moved from backend
        VizDispatchers.kt
        VizDeferred.kt
        VizFlow.kt
        VizChannel.kt       # New
        VizConfig.kt         # Connection config
    server/                 # Existing Ktor app (depends on sdk)
```

**User-facing API:**
```kotlin
// In user's application
val vizConfig = VizConfig(
    serverUrl = "http://localhost:8080",
    sessionName = "my-debug-session",
    enabled = true  // Disable in production
)

val session = VizSession.connect(vizConfig)
val scope = VizScope(session)

scope.vizLaunch("my-coroutine") {
    // User's code - automatically tracked
}
```

**Done when:** External app can `implementation("com.jh:coroutine-viz-sdk:1.0")`, emit events with <10 LOC, and see them in the visualizer.

---

## Tier 4 - Frontend Advanced Features

Features that enhance the frontend user experience and enable premium capabilities.

---

### 4.1 Export Capabilities (PNG/SVG/Video)

**Source:** BUSINESS_ANALYSIS_V2 Premium Tier
**Status:** NOT STARTED
**Effort:** 1-2 weeks
**Priority:** Medium (premium feature)

**What:** Allow users to export visualizations as static images (PNG/SVG) or animated recordings (GIF/MP4/WebM) for embedding in documentation, blog posts, and courses.

**Why:** Key differentiator for educator persona ("Morgan the Educator"). Export is the #1 requested premium feature in business plan. Content creators need shareable assets.

**Dependencies:**
- Frontend visualization components (DONE)
- Premium tier billing (#6.2) - this is a gated feature

**How to prepare:**
1. Research frontend screenshot libraries: `html2canvas`, `dom-to-image`, or canvas-based approaches
2. For SVG export: investigate D3.js SVG serialization or React-to-SVG libraries
3. For video/GIF: research `MediaRecorder` API, `gif.js`, or `RecordRTC`
4. Design export UI: button placement, format selection, quality options

**Implementation outline:**

**Static export (PNG/SVG):**
```typescript
// Using html2canvas
import html2canvas from 'html2canvas';

async function exportAsPng(elementId: string) {
    const element = document.getElementById(elementId);
    const canvas = await html2canvas(element);
    const link = document.createElement('a');
    link.download = 'coroutine-visualization.png';
    link.href = canvas.toDataURL('image/png');
    link.click();
}

// SVG export
function exportAsSvg(elementId: string) {
    const svgElement = document.querySelector(`#${elementId} svg`);
    const serializer = new XMLSerializer();
    const svgString = serializer.serializeToString(svgElement);
    // Create download blob
}
```

**Animated export (GIF/Video):**
```typescript
// Using MediaRecorder for video
function startRecording(canvasElement: HTMLCanvasElement) {
    const stream = canvasElement.captureStream(30); // 30 FPS
    const recorder = new MediaRecorder(stream, { mimeType: 'video/webm' });
    const chunks: Blob[] = [];

    recorder.ondataavailable = (e) => chunks.push(e.data);
    recorder.onstop = () => {
        const blob = new Blob(chunks, { type: 'video/webm' });
        // Download or convert to GIF
    };

    recorder.start();
    return recorder;
}
```

**Done when:** Users can export tree view, graph view, and timeline as PNG/SVG; animated recording captures live event replay as video.

---

### 4.2 Scenario Authoring UI

**Source:** TASKS-DEEPDIVE #16, BUSINESS_ANALYSIS_V2 Custom Scenario Builder
**Status:** PARTIALLY DONE (basic builder exists at `/scenarios/builder`)
**Effort:** 1 week
**Priority:** Medium

**What:** Enhance the existing scenario builder with server-side validation, preset templates, DSL preview, and better UX for composing coroutine hierarchies and actions.

**Why:** Currently the builder is basic. A polished authoring UI is essential for the "self-service" value proposition and reduces reliance on backend-defined scenarios.

**Dependencies:**
- Backend custom scenario endpoint (DONE - `POST /api/scenarios/custom`)
- Validation endpoint (NOT STARTED - backend should validate DSL before execution)

**How to prepare:**
1. Review existing `ScenarioBuilder.tsx` and `ScenarioForm.tsx` components
2. Identify UX gaps: error feedback, preview, templates, undo/redo
3. Design validation API: `POST /api/scenarios/validate` returns errors before execution
4. Create 3-5 preset templates (producer-consumer, parent-child cancellation, race condition)

**Implementation outline:**
- Add validation endpoint on backend that checks DSL structure without executing
- Add preset template buttons that pre-fill the form
- Add DSL preview panel showing the equivalent Kotlin code
- Add error boundary and inline validation feedback
- Add drag-and-drop for coroutine hierarchy composition

**Done when:** Users can author, validate, and run custom scenarios entirely from UI; preset templates work; validation errors shown inline.

---

### 4.3 Shareable Visualizations (Hosted Links)

**Source:** BUSINESS_ANALYSIS_V2 Premium Tier
**Status:** NOT STARTED
**Effort:** 1-2 weeks
**Priority:** Low (requires persistence + auth)

**What:** Generate shareable URLs for visualization sessions that others can view without running the backend locally.

**Why:** Educators want to share demos with students. Blog writers want embedded links. Teams want to share debugging sessions.

**Dependencies:**
- Persistence (#2.2) - sessions must survive server restart
- Authentication (#3.1) - sharing permissions

**How to prepare:**
1. Design URL schema: `https://app.coroutine-viz.dev/shared/{shareToken}`
2. Design sharing model: public link, password-protected, expiring
3. Plan hosting: static session replay (no live backend needed) vs proxied access

**Done when:** User can generate a share link; recipient can view the visualization read-only.

---

## Tier 5 - Tooling & IDE Integration

---

### 5.1 IntelliJ Plugin

**Source:** BUSINESS_ANALYSIS_V2 Phase 3, INTELLIJ_PLUGIN_GUIDE.md, INTELLIJ_PLUGIN_INTEGRATION.md
**Status:** DESIGNED (extensive guides exist at project root, zero implementation)
**Effort:** 4-8 weeks
**Priority:** Medium-Low (Phase 2-3 feature)

**What:** IntelliJ IDEA / Android Studio plugin that provides native coroutine visualization within the IDE. Combines VizSession wrappers with DebugProbes for a hybrid approach.

**Why:** IDE integration is the highest-value distribution channel. JetBrains marketplace reaches millions. Removes the "open browser" friction.

**Dependencies:**
- SDK packaging (#3.4) - plugin needs the instrumentation library
- Stable API surface - plugin development is costly to maintain

**How to prepare:**
1. Read `INTELLIJ_PLUGIN_GUIDE.md` for the full creation guide
2. Read `INTELLIJ_PLUGIN_INTEGRATION.md` for the hybrid VizSession + DebugProbes approach
3. Set up IntelliJ Platform Plugin SDK development environment
4. Create minimal plugin skeleton with tool window

**Implementation outline (from existing design docs):**

**Phase A: Tool Window**
- Register tool window via `plugin.xml`
- Embed WebView (JCEF) rendering the React frontend
- Communicate via `JavaFxBridge` or REST to local backend

**Phase B: Run Configuration Integration**
- Custom run configuration that wraps user's app with VizSession
- Automatically starts backend + connects plugin

**Phase C: DebugProbes Hybrid**
- When user runs in Debug mode, also activate DebugProbes
- Merge DebugProbes snapshots with VizSession event stream
- Show combined view: wrapper events (detailed) + DebugProbes (coverage)

**Done when:** Plugin installable from JetBrains marketplace; tool window shows coroutine visualization during debug session.

---

### 5.2 CI/CD Integration

**Source:** BUSINESS_ANALYSIS_V2 Phase 3
**Status:** NOT STARTED
**Effort:** 1-2 weeks
**Priority:** Low

**What:** Gradle plugin or CLI tool that runs coroutine scenarios as part of CI/CD pipeline and fails the build if concurrency issues are detected (e.g., thread starvation, excessive cancellations, unhandled exceptions).

**Why:** Enterprise value: automated concurrency regression detection. "Shift left" on concurrency bugs.

**Dependencies:**
- SDK packaging (#3.4) - CI needs the library
- Comparative runs (#3.3) - compare against baseline
- Defined thresholds for pass/fail

**How to prepare:**
1. Define what "fail" means: threshold violations (>N cancellations, >Xms starvation, unhandled exceptions)
2. Design CLI interface: `coroutine-viz check --baseline session-A --current session-B --threshold cancellations=5`
3. Design Gradle plugin: `coroutineViz { baseline = "baseline.json"; threshold { ... } }`

**Done when:** CI pipeline can run scenarios and fail build on regression; baseline comparison automated.

---

### 5.3 Bytecode Instrumentation (Compiler Plugin)

**Source:** old/BUSINESS_ANALYSIS Section 7.2, COROUTINE-VISUALIZER-BUSINESS-ANALYSIS Section 12.2
**Status:** NOT STARTED
**Effort:** 8-12 weeks (advanced)
**Priority:** Low (v2.0+ feature)

**What:** Kotlin compiler plugin that automatically instruments `launch`, `async`, `withContext`, and other coroutine builders without requiring users to change their code.

**Why:** The current decoration pattern requires users to use `vizLaunch` instead of `launch`. Bytecode instrumentation is transparent and enables production use.

**Dependencies:**
- Stable event model
- SDK packaging (#3.4)
- Deep Kotlin compiler plugin knowledge

**How to prepare:**
1. Study Kotlin compiler plugin API (KSP or legacy compiler plugin)
2. Review similar projects: `kotlinx-coroutines-debug`, Arrow Meta
3. Start with a minimal transform: intercept `launch` calls only
4. Design configuration: annotation-based opt-in, package-level includes/excludes

**Done when:** User adds Gradle plugin, and all `launch`/`async` calls in specified packages are automatically instrumented. Zero code changes required.

---

## Tier 6 - Business & Go-to-Market

---

### 6.1 GTM Pilot Program

**Source:** TASKS-DEEPDIVE #15, BUSINESS_ANALYSIS_V2 Phase 1
**Status:** NOT STARTED
**Effort:** 4-8 weeks (elapsed time)
**Priority:** High (validates product-market fit)

**What:** Recruit 1-3 Kotlin-heavy teams for a structured pilot. Provide the tool, measure outcomes (MTTR for concurrency bugs, learning velocity, usage patterns), collect feedback, and produce a case study.

**Why:** All business projections are theoretical without real-world validation. A pilot proves (or disproves) the core value proposition.

**Dependencies:**
- Core product must be stable and documented
- At minimum: health endpoint, logging, sample data, FE integration doc

**How to prepare:**
1. Define success metrics:
   - Mean Time To Resolution (MTTR) for concurrency bugs - before vs after
   - Sessions per week per developer
   - Scenario library growth (team-contributed)
   - NPS score
2. Create onboarding guide specific to pilot teams
3. Build feedback collection mechanism (survey, interview schedule)
4. Identify candidate teams (internal, open-source community, Kotlin Slack contacts)

**Execution plan:**
- **Week 1:** Recruit teams, provide access, onboarding call
- **Week 2-4:** Active use period, weekly check-ins
- **Week 5-6:** Collect metrics, run interviews
- **Week 7-8:** Write pilot report, adjust product based on findings

**Done when:** Pilot report shows measurable value (MTTR down, usage up); report informs pricing and next release priorities.

---

### 6.2 Premium Tier & Billing (Stripe)

**Source:** BUSINESS_ANALYSIS_V2 Section 4.1
**Status:** NOT STARTED
**Effort:** 2-4 weeks
**Priority:** Medium (blocks monetization)

**What:** Implement user accounts, Stripe billing integration, and feature gating for the premium tier ($29/month or $249/year).

**Premium features to gate:**
- Export capabilities (PNG/SVG/video)
- Custom scenario builder (advanced)
- Shareable visualizations (hosted links)
- Priority support channel
- Commercial use license

**Dependencies:**
- Authentication (#3.1) - users need accounts
- Persistence (#2.2) - billing records need storage
- Export capabilities (#4.1) - key premium feature

**How to prepare:**
1. Create Stripe account and configure products/prices
2. Design user registration flow (email/password or OAuth)
3. Design feature flag system for premium gating
4. Review Stripe Checkout and Customer Portal integration
5. Choose billing approach: Stripe Checkout (hosted) vs custom UI

**Implementation outline:**

**Backend:**
```kotlin
// Feature gating middleware
fun Route.premiumOnly(block: Route.() -> Unit) {
    authenticate("jwt") {
        intercept(ApplicationCallPipeline.Call) {
            val user = call.principal<JWTPrincipal>()
            if (!billingService.isPremium(user.userId)) {
                call.respond(HttpStatusCode.PaymentRequired, "Premium feature")
                return@intercept finish()
            }
        }
        block()
    }
}

// Stripe webhook
post("/api/webhooks/stripe") {
    val event = stripeService.constructEvent(call.receiveText(), signature)
    when (event.type) {
        "checkout.session.completed" -> billingService.activatePremium(customerId)
        "customer.subscription.deleted" -> billingService.deactivatePremium(customerId)
    }
}
```

**Frontend:**
```typescript
// Feature gate component
function PremiumFeature({ children }: { children: React.ReactNode }) {
    const { isPremium } = useAuth();
    if (!isPremium) return <UpgradePrompt />;
    return <>{children}</>;
}
```

**Done when:** Users can sign up, subscribe via Stripe Checkout, access premium features; billing portal for cancellation/upgrade.

---

### 6.3 Marketing Website

**Source:** BUSINESS_ANALYSIS_V2 Phase 2
**Status:** NOT STARTED
**Effort:** 1-2 weeks
**Priority:** Medium-Low

**What:** Public-facing marketing website with: hero section, feature showcase, live demo, pricing table, testimonials, and documentation links.

**Why:** GitHub README is not sufficient for commercial positioning. Enterprise buyers expect a professional web presence.

**How to prepare:**
1. Choose approach: static site (Next.js, Astro) vs landing page builder (Framer, Webflow)
2. Create copy for value propositions by segment (learner, educator, enterprise)
3. Design or source screenshots/GIFs of the visualizer in action
4. Set up domain and hosting

**Done when:** Professional website live with clear value prop, pricing, and call-to-action.

---

### 6.4 Conference Talks & Content Marketing

**Source:** BUSINESS_ANALYSIS_V2 Phase 1 GTM
**Status:** NOT STARTED
**Effort:** Ongoing
**Priority:** Medium

**What:** Submit talk proposals to KotlinConf, Droidcon, and local meetups. Create blog post series, YouTube demos, and social content.

**How to prepare:**
1. Draft talk abstract: "Seeing Your Coroutines Think: A Visual Approach to Understanding Structured Concurrency"
2. Create 3-5 minute demo video showing the tool in action
3. Write blog post: "Visualizing Kotlin Coroutines" for Medium/Dev.to
4. Identify target conferences and submission deadlines

**Content calendar:**
- **Month 1:** Demo video + blog post + Reddit/Kotlin Slack post
- **Month 2:** Conference talk submission + YouTube tutorial
- **Month 3:** Follow-up blog posts (deep dives on specific patterns)
- **Ongoing:** Weekly social posts, community engagement

**Done when:** First conference talk submitted; demo video published; blog post live.

---

### 6.5 Partnership Outreach

**Source:** BUSINESS_ANALYSIS_V2 Section 9
**Status:** NOT STARTED
**Effort:** Ongoing
**Priority:** Low (after traction is demonstrated)

**Target partners:**
1. **JetBrains:** Plugin ecosystem, JetBrains Academy, co-marketing
2. **Online Learning Platforms:** Udemy, Pluralsight, Coursera - featured tool
3. **Kotlin Foundation:** Official endorsement, conference presence
4. **Enterprise Training:** O'Reilly, corporate training programs

**How to prepare:**
1. Build traction first (GitHub stars, user count, pilot results)
2. Prepare partnership pitch deck
3. Identify contacts at target organizations
4. Start with low-commitment collaborations (guest blog post, shared webinar)

**Done when:** Active conversation with at least one strategic partner; partnership agreement signed.

---

## Dependency Graph

```
Tier 1 (Foundation) ──────────────────────────────────
  1.1 Health Endpoint          ─── no deps ───→ standalone
  1.2 Logging Profiles         ─── no deps ───→ standalone
  1.3 Config Hygiene           ─── no deps ───→ standalone
  1.4 Sample Session Payload   ─── no deps ───→ standalone
  1.5 FE Integration Doc       ─── no deps ───→ standalone
  1.6 OpenAPI Polish           ─── no deps ───→ standalone
  1.7 API Quickstart Doc       ─── no deps ───→ standalone

Tier 2 (Backend Core) ────────────────────────────────
  2.1 InstrumentedChannel      ─── no deps ───→ enables Channel scenarios
  2.2 Persistence              ─── no deps ───→ enables 3.1, 3.3, 4.3, 6.2
  2.3 Load Profiling           ─── no deps ───→ enables 2.4
  2.4 Sampling/Retention       ─── 2.2 + 2.3 ─→ production readiness
  2.5 Timeline Enrichment      ─── no deps ───→ enables 3.3
  2.6 Scenario Catalog UX      ─── no deps ───→ better FE UX

Tier 3 (Advanced Backend) ────────────────────────────
  3.1 Auth/RBAC                ─── 2.2 ───────→ enables 6.2, 4.3
  3.2 OpenTelemetry Export     ─── no deps ───→ enterprise feature
  3.3 Comparative Runs         ─── 2.2 + 2.5 ─→ enables 5.2
  3.4 SDK Packaging            ─── 2.1 ───────→ enables 5.1, 5.2, 5.3

Tier 4 (Frontend Advanced) ───────────────────────────
  4.1 Export (PNG/SVG/Video)   ─── no deps ───→ premium feature
  4.2 Scenario Authoring UI    ─── no deps ───→ user-facing feature
  4.3 Shareable Visualizations ─── 2.2 + 3.1 ─→ premium feature

Tier 5 (Tooling) ─────────────────────────────────────
  5.1 IntelliJ Plugin          ─── 3.4 ───────→ distribution channel
  5.2 CI/CD Integration        ─── 3.3 + 3.4 ─→ enterprise feature
  5.3 Bytecode Instrumentation ─── 3.4 ───────→ v2.0+ feature

Tier 6 (Business) ────────────────────────────────────
  6.1 GTM Pilot                ─── Tier 1 ────→ validates product
  6.2 Premium/Billing          ─── 2.2 + 3.1 ─→ monetization
  6.3 Marketing Website        ─── no deps ───→ commercial presence
  6.4 Content Marketing        ─── no deps ───→ awareness
  6.5 Partnership Outreach     ─── 6.1 ───────→ growth
```

---

## Recommended Execution Order

### Sprint 1 (Week 1-2): Foundation Blitz
All Tier 1 items can be done in parallel. No dependencies.
- [ ] 1.1 Health Endpoint (2h)
- [ ] 1.2 Logging Profiles (3h)
- [ ] 1.3 Config Hygiene (3h)
- [ ] 1.4 Sample Session Payload (4h)
- [ ] 1.5 FE Integration Doc (5h)
- [ ] 1.6 OpenAPI Polish (5h)
- [ ] 1.7 API Quickstart Doc (4h)

### Sprint 2 (Week 3-4): Backend Core
- [ ] 2.1 InstrumentedChannel (3d)
- [ ] 2.5 Timeline Enrichment (3d)
- [ ] 2.6 Scenario Catalog UX (2d)

### Sprint 3 (Week 5-6): Production Readiness
- [ ] 2.2 Persistence & DB Storage (5d)
- [ ] 2.3 Load Profiling (3d)

### Sprint 4 (Week 7-8): Advanced Features + Pilot Prep
- [ ] 2.4 Sampling/Retention (2d)
- [ ] 4.1 Export Capabilities (5d)
- [ ] 4.2 Scenario Authoring UI Enhancement (5d)
- [ ] 6.1 GTM Pilot - Recruit & Start (ongoing)

### Sprint 5 (Week 9-10): Auth & SDK
- [ ] 3.1 Authentication/RBAC (5d)
- [ ] 3.4 SDK Packaging (5d)

### Sprint 6 (Week 11-14): Enterprise & Monetization
- [ ] 3.2 OpenTelemetry Export (5d)
- [ ] 3.3 Comparative Runs (3d)
- [ ] 6.2 Premium Tier + Billing (10d)
- [ ] 6.3 Marketing Website (5d)

### Sprint 7+ (Week 15+): Scale
- [ ] 5.1 IntelliJ Plugin (4-8w)
- [ ] 5.2 CI/CD Integration (1-2w)
- [ ] 4.3 Shareable Visualizations (1-2w)
- [ ] 5.3 Bytecode Instrumentation (8-12w)
- [ ] 6.4 Content Marketing (ongoing)
- [ ] 6.5 Partnership Outreach (ongoing)

---

## Total Effort Estimate

| Tier | Items | Estimated Effort |
|------|-------|-----------------|
| Tier 1 - Foundation | 7 | ~3-4 days |
| Tier 2 - Backend Core | 6 | ~3-4 weeks |
| Tier 3 - Advanced Backend | 4 | ~4-6 weeks |
| Tier 4 - Frontend Advanced | 3 | ~3-4 weeks |
| Tier 5 - Tooling | 3 | ~14-22 weeks |
| Tier 6 - Business | 5 | ~6-10 weeks |
| **Total** | **28 items** | **~33-50 weeks** |

*Note: Many items can be parallelized. With 2 developers, the critical path to premium launch (Tiers 1-4 + Auth + Billing) is approximately 14-16 weeks.*

---

**This document should be reviewed and updated after each sprint to reflect progress and re-prioritize based on user feedback and pilot results.**
