# Codebase Concerns

**Analysis Date:** 2026-06-11

---

## Accepted-But-Unimplemented ADRs

### ADR-015: Persistence Strategy — Entirely Unimplemented

**Severity: Critical (blocks production deployment)**

- Issue: ADR-015 (status: Accepted) specifies PostgreSQL/H2 persistence via Exposed ORM, HikariCP connection pool, Flyway migrations, and a retention background coroutine. None of these exist anywhere in the codebase.
- Files:
  - ADR: `docs/adr/015-persistence-strategy.md`
  - Main backend EventStore (unbounded, in-memory): `backend/src/main/kotlin/com/jh/proj/coroutineviz/session/EventStore.kt`
  - Main backend SessionManager (ConcurrentHashMap): `backend/src/main/kotlin/com/jh/proj/coroutineviz/session/SessionManager.kt`
- Impact: Every session and every event is lost on backend restart. Zero-config for development only. Blocks session sharing (ADR-019) and any long-lived workflow.
- Fix approach: Implement `ExposedSessionStore` and `ExposedEventStore` as specified in ADR-015, add HikariCP, add Flyway migrations, add PostgreSQL service to `docker-compose.prod.yml`, wire via environment flag `storage.type=database`.

### ADR-016: Authentication Architecture — Partially Wired, All Routes Unprotected

**Severity: High (security gap for any non-local deployment)**

- Issue: `Auth.kt` installs the `api-key` Ktor authentication provider correctly and `authenticatedApi()` helper is defined, but **not a single route calls `authenticatedApi()`**. `Routing.kt` registers all routes directly without wrapping them. The `auth.apiKey` config defaults to empty (`${API_KEY:}`), so auth is disabled by default, but even when set, no route enforces it.
- Files:
  - Auth plugin: `backend/src/main/kotlin/com/jh/proj/coroutineviz/Auth.kt`
  - Route registration (no auth wrappers): `backend/src/main/kotlin/com/jh/proj/coroutineviz/Routing.kt`
  - Example unprotected route: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt`
  - Config: `backend/src/main/resources/application.yaml`
- Impact: Any caller with network access can create sessions, run scenarios, delete sessions, and read all event data regardless of `API_KEY` being set.
- Fix approach: Wrap all non-health routes in `authenticatedApi { ... }` inside `Routing.kt`. Phase B (JWT) is lower priority.

### ADR-020: Performance Scaling — Partially Implemented in Core, Not Wired into Running Server

**Severity: High (OOM risk under load)**

- Issue: ADR-020 (Accepted) specifies a bounded `EventStore`, `EventSampler`, `RetentionPolicy`, Micrometer metrics, SSE compression, and batching. The `coroutine-viz-core` module has implemented `EventStore(maxEvents)`, `EventSampler`, and `RetentionPolicy`. **None of these are used by the main backend's running server.** The running server uses its own forked, unbounded `CopyOnWriteArrayList`-based `EventStore`.
  - `EventSampler` — exists in `coroutine-viz-core`, not referenced anywhere in `backend/src/main/`
  - `RetentionPolicy` — exists in `coroutine-viz-core`, not referenced anywhere in `backend/src/main/`
  - Metrics wiring — only two gauges (`viz.sessions.active`, `viz.sse.clients.active`) are registered; ADR-020 counters `events.emitted`, `events.dropped`, `scenario.duration`, `event.processing.duration` are absent
- Files:
  - Core bounded EventStore: `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/EventStore.kt`
  - Core EventSampler: `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/EventSampler.kt`
  - Core RetentionPolicy: `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/RetentionPolicy.kt`
  - Main backend MetricsWiring (partial only): `backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt`
- Impact: Running server has no event count ceiling. A busy scenario with hundreds of coroutines will grow the in-memory `CopyOnWriteArrayList` without bound until OOM.
- Fix approach: Delete the forked `session/` subtree in `backend/src/main/` and rely exclusively on `coroutine-viz-core` implementations. Wire `RetentionPolicy` into `SessionManager`. Hook `EventSampler` into `EventBus.send()`. Register missing Micrometer counters.

### ADR-019: Session Sharing — Core Service Exists, No HTTP Endpoint

**Severity: Medium**

- Issue: `ShareTokenService` is implemented in core and has tests, but no HTTP route exposes it. The feature is fully dark.
- Files:
  - Core service: `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/ShareTokenService.kt`
  - ADR: `docs/adr/019-session-sharing.md`
- Impact: No session sharing capability available despite Accepted ADR.
- Fix approach: Add `POST /api/sessions/{id}/share` and `GET /api/sessions/shared/{token}` routes wired to `ShareTokenService`.

---

## Critical Tech Debt: Duplicated and Diverged Session Package

**Severity: High**

- Issue: The `backend/src/main/` module defines its own `session/` package (`EventStore`, `EventBus`, `SessionManager`, `VizSession`, `EventApplier`, `ProjectionService`, `JobStatusMonitor`, `EventContext`, `FlowEventContext`, `ChannelEventContext`) using the **same package name** `com.jh.proj.coroutineviz.session` as `coroutine-viz-core`. Since the backend also has `implementation(project(":coroutine-viz-core"))`, the classpath contains two sets of classes with identical fully-qualified names. In practice, the main backend's local copies shadow the core module's improved versions.
- Files:
  - Forked (unbounded) EventStore: `backend/src/main/kotlin/com/jh/proj/coroutineviz/session/EventStore.kt` (uses `CopyOnWriteArrayList`, no `maxEvents`)
  - Core (bounded) EventStore: `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/EventStore.kt` (uses `ArrayDeque` + `ReentrantReadWriteLock`, `maxEvents = 10_000`)
  - Both `VizSession.kt` files differ (~260 vs ~264 lines) with the core version being more complete
  - Gradle: `backend/build.gradle.kts` (line `implementation(project(":coroutine-viz-core"))`)
- Impact: Improvements added to core (bounded eviction, `RetentionPolicy`, `EventSampler`) are silently bypassed at runtime by the forked copies. Any future change to core session classes must also be applied to the forked copies or the fork must be removed.
- Fix approach: Delete `backend/src/main/kotlin/com/jh/proj/coroutineviz/session/` entirely and resolve any compilation gaps against the core module.

---

## Security Considerations

### No Rate Limiting on Session Creation or Scenario Execution

**Severity: Medium**

- Risk: Any caller can POST `/api/sessions` unlimited times and trigger expensive coroutine scenarios (e.g., `POST /api/scenarios/run`), consuming JVM heap and CPU.
- Files:
  - Session routes: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt`
  - Scenario routes: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ScenarioRunnerRoutes.kt`
- Current mitigation: None.
- Recommendations: Add Ktor rate-limiting middleware or enforce a configurable max active session count. Gate scenario execution behind auth once ADR-016 routes are wired.

### CORS Default Allows Credentials from localhost

**Severity: Low**

- Risk: `allowCredentials = true` is set globally in `HTTP.kt`. If `cors.allowedOrigins` is misconfigured in a deployment to include a broad wildcard, this becomes a CSRF vector.
- Files: `backend/src/main/kotlin/com/jh/proj/coroutineviz/HTTP.kt` (lines 60-62)
- Current mitigation: Default origins are `localhost:3000` only.
- Recommendations: Restrict `allowCredentials = true` to SSE endpoints only, or document that `CORS_ALLOWED_ORIGINS` must never contain wildcards.

### API Key Stored as Plaintext in Config (Phase A Gap)

**Severity: Low**

- Risk: ADR-016 specifies keys stored as SHA-256 hashes. The current `Auth.kt` implementation compares the raw `X-API-Key` header directly to the raw value of `auth.apiKey` from `application.yaml`. No hashing is performed.
- Files:
  - `backend/src/main/kotlin/com/jh/proj/coroutineviz/Auth.kt` (line 38: `if (requestKey == apiKey)`)
  - `backend/src/main/resources/application.yaml`
- Recommendations: Hash stored key with SHA-256 and compare `sha256(requestKey)` against the stored hash as the ADR specifies.

---

## Performance Bottlenecks

### Unbounded CopyOnWriteArrayList in Production EventStore

**Severity: High**

- Problem: The running server's `EventStore` (the forked copy in `backend/src/main/`) uses `CopyOnWriteArrayList` with no size limit. Every `store.all()` call — including for timeline queries and snapshot reads — performs an O(n) iteration over this list.
- Files: `backend/src/main/kotlin/com/jh/proj/coroutineviz/session/EventStore.kt`
- Cause: `CopyOnWriteArrayList.all()` returns the live list reference; `since(seq)` performs a full linear filter on every call. Under a session with 10,000+ events, this degrades noticeably.
- Improvement path: Remove forked `EventStore`; adopt core's bounded `ArrayDeque` + `ReentrantReadWriteLock` variant with `maxEvents` eviction.

### SSE Compression Excludes `X-Accel-Buffering: no` Header

**Severity: Low**

- Problem: `Compression.kt` correctly enables gzip for `ContentType.Text.EventStream`, but ADR-020 also requires `X-Accel-Buffering: no` to prevent nginx/proxy buffering of SSE streams. This header is absent.
- Files: `backend/src/main/kotlin/com/jh/proj/coroutineviz/Compression.kt`
- Improvement path: Add `X-Accel-Buffering: no` response header in SSE route handlers or via a Ktor response interceptor.

---

## Fragile Areas

### IntelliJ Plugin: `RunWithVisualizerAction` is a Stub

**Severity: Medium**

- Files: `intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/actions/RunWithVisualizerAction.kt` (line 32)
- Why fragile: The action's core responsibility — launching user code with the `coroutine-viz-core` agent — contains a `TODO: Execute the current run configuration with -javaagent or classpath modifications`. Clicking "Run with Visualizer" in the IDE produces no effect. The plugin UI, HTTP receiver, and session management are implemented, but the entry point that connects them to user code is missing.
- Safe modification: The HTTP receiver (`PluginEventReceiver`) and `PluginSessionManager` are fully functional and can receive events. The fix is scoped to `RunWithVisualizerAction` alone.
- Test coverage: No test directory exists under `intellij-plugin/src/test/`. The plugin has zero automated tests.

### Main Backend Wrappers Import from Ambiguous Package

**Severity: Medium**

- Files:
  - `backend/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/InstrumentedChannel.kt` (`import com.jh.proj.coroutineviz.session.*`)
  - `backend/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/InstrumentedFlow.kt`
  - `backend/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/InstrumentedSharedFlow.kt`
- Why fragile: Wildcard imports from `com.jh.proj.coroutineviz.session` resolve against the forked main-backend versions of `VizSession`, `EventStore`, etc., rather than the core module versions. If the fork diverges further, these wrappers silently pick up different behaviour depending on which source set is on the classpath first.

### `correlatedFlow()` in VizSession Has Silent Data Loss

**Severity: Low**

- Files: `backend/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt` (lines 153-169)
- Why fragile: `correlatedFlow()` uses `combine(coroutineFlow, jobFlow)` which requires both flows to emit. If one side is faster than the other, `combine` will pair the latest coroutine event with each new job event, silently discarding unpaired events. The comment says "correlate by jobId" but `combine` does not correlate by jobId — it combines by emission timing. Genuine job/coroutine correlation should use a join key lookup, not `combine`.
- Impact: The timeline view can display misleading paired data.

---

## Missing Critical Features

### No Session Persistence Across Restarts

- Problem: All sessions and events live only in JVM heap. Restarting the backend (e.g., container reschedule, crash) wipes all visualization history.
- Blocks: Production deployment, collaborative debugging, session sharing.

### No HTTP Route-Level Authentication Enforcement

- Problem: `authenticatedApi()` helper exists but is called nowhere. All 10+ route registration functions bypass it.
- Blocks: Safe multi-user or multi-tenant deployment.

### IntelliJ Plugin Cannot Launch Instrumented Code

- Problem: `RunWithVisualizerAction.actionPerformed` is a stub.
- Blocks: The primary "zero-friction" use case for the plugin.

---

## Test Coverage Gaps

### IntelliJ Plugin: Zero Tests

- What's not tested: All plugin UI panels (`TreePanel`, `TimelinePanel`, `EventLogPanel`), `PluginSessionManager`, `RunWithVisualizerAction`, `PluginEventReceiver` HTTP server behavior.
- Files: Entire `intellij-plugin/src/` subtree.
- Risk: Any refactor of the plugin breaks silently.
- Priority: Medium

### Main Backend Session Divergence Not Caught by Tests

- What's not tested: The interaction between the forked `backend/src/main/.../session/` classes and the core module classes. No test asserts that `EventStore` in use is the bounded variant.
- Files: `backend/src/main/kotlin/com/jh/proj/coroutineviz/session/`
- Risk: Unbounded memory growth regression is invisible to CI.
- Priority: High

### Auth Enforcement Not Tested End-to-End

- What's not tested: That routes actually reject requests without a valid `X-API-Key` when the key is configured.
- Files: `backend/src/test/kotlin/com/jh/proj/coroutineviz/AuthTest.kt` (tests the auth plugin in isolation, not route-level enforcement)
- Risk: Introducing `authenticatedApi()` wrappers without regression tests could lock out legitimate callers unexpectedly.
- Priority: High (test before adding auth wrappers to routes)

---

*Concerns audit: 2026-06-11*
