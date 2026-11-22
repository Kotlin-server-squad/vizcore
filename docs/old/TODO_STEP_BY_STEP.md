# Coroutine Visualizer - Step-by-Step TODO Checklist

**Companion to:** `NOT_STARTED_DEEP_DIVE.md`
**Last Updated:** February 2026

Every checkbox below is a single actionable step. Complete them in order within each sprint. Check off as you go.

---

## Sprint 1: Foundation Blitz (Week 1-2)

All items have zero dependencies. Can be done in any order or in parallel.

---

### 1.1 Health Endpoint

- [ ] Read `backend/src/main/kotlin/.../Routing.kt` to understand route registration pattern
- [ ] Read `backend/src/main/kotlin/.../session/SessionManager.kt` to identify health-checkable components
- [ ] Create `backend/src/main/kotlin/.../models/HealthStatus.kt` with `HealthStatus` and `ComponentHealth` data classes
- [ ] Create `backend/src/main/kotlin/.../routes/HealthRoutes.kt`
- [ ] Add `GET /health` route returning `{"status": "UP", "sessions": <count>, "uptime": <ms>}`
- [ ] Add component checks: SessionManager reachable, memory usage below threshold
- [ ] Return `200 OK` when healthy, `503 Service Unavailable` when not
- [ ] Register health routes in main `Routing.kt`
- [ ] Add test: `GET /health` returns 200 with expected JSON shape
- [ ] Add `http-requests/health.http` with example request
- [ ] Verify: `curl http://localhost:8080/health` returns valid JSON

---

### 1.2 Logging Profiles

- [ ] Read `backend/src/main/resources/logback.xml` for current configuration
- [ ] Search codebase for `println` calls: `grep -r "println" backend/src/main/kotlin/`
- [ ] Replace each `println` with proper `LoggerFactory.getLogger(...)` calls
- [ ] Create `backend/src/main/resources/logback-dev.xml` with DEBUG level for `com.jh.proj` package
- [ ] Create `backend/src/main/resources/logback-prod.xml` with INFO level, structured JSON output
- [ ] Update `application.yaml` or `build.gradle.kts` to support profile switching via `-Dlogback.configurationFile`
- [ ] Document in backend README how to switch profiles
- [ ] Create `backend/http-requests/sessions.http` with: create session, list sessions, delete session
- [ ] Create `backend/http-requests/scenarios.http` with: list scenarios, run scenario
- [ ] Create `backend/http-requests/streaming.http` with: SSE connection example
- [ ] Verify: start server in prod profile, confirm INFO logs are clean and readable

---

### 1.3 Config Hygiene for Frontend

- [ ] Read `backend/src/main/kotlin/.../HTTP.kt` for current CORS setup
- [ ] Read `backend/src/main/resources/application.yaml` for existing config keys
- [ ] Add config keys to `application.yaml`:
  ```yaml
  app:
    cors:
      allowed-origins: ["http://localhost:3000"]
    server:
      port: 8080
  ```
- [ ] Update `HTTP.kt` to read origins from config instead of hardcoded values
- [ ] Support `CORS_ORIGIN` environment variable override
- [ ] Add fallback to `http://localhost:3000` when no config provided
- [ ] Test: set `CORS_ORIGIN=http://localhost:5173` env var, verify CORS headers change
- [ ] Test: no env var set, verify `localhost:3000` still works
- [ ] Document config keys in backend README under "Configuration" section

---

### 1.4 Sample Session Payload

- [ ] Review existing DTOs: `SessionSnapshotResponse`, `CoroutineInfo`, `VizEvent` types in models
- [ ] Design sample data: 6 coroutines (root, 3 children, 2 grandchildren)
- [ ] Include all coroutine states: active, completed, cancelled, suspended, waiting-for-children
- [ ] Include 3 dispatchers: Default, IO, Main
- [ ] Include 20 events covering: Created, Started, Suspended, Resumed, Completed, Cancelled, JobStateChanged
- [ ] Include thread activity for 4 threads
- [ ] Create `backend/src/main/kotlin/.../routes/SampleDataProvider.kt` with hardcoded data
- [ ] Add `GET /api/sessions/sample` route returning the static payload
- [ ] Add test: endpoint returns valid JSON matching expected schema
- [ ] Add `http-requests/sample.http` with example request
- [ ] Verify: copy response JSON, paste into FE dev tools, confirm UI renders correctly

---

### 1.5 FE Integration Documentation

- [ ] Read `backend/src/main/kotlin/.../routes/SessionRoutes.kt` - trace SSE implementation
- [ ] Read `frontend/src/hooks/use-event-stream.ts` - understand client-side SSE handling
- [ ] Read `frontend/src/lib/api-client.ts` - catalog all API calls
- [ ] Create `backend/docs/FE-INTEGRATION.md`
- [ ] Write section: **SSE Contract**
  - [ ] Document connection URL format: `GET /api/sessions/:id/stream`
  - [ ] Document history replay behavior (all past events first)
  - [ ] Document live event format after history
  - [ ] Document event type normalization (PascalCase vs kebab-case)
  - [ ] Document reconnection behavior and `Last-Event-ID` header
- [ ] Write section: **REST Endpoints**
  - [ ] List each endpoint with method, URL, request body, response body
  - [ ] Include example JSON for each response
- [ ] Write section: **Common Integration Flows**
  - [ ] Flow 1: Create session -> Run scenario -> Stream events
  - [ ] Flow 2: Browse sessions -> Open detail -> Toggle live stream
  - [ ] Flow 3: Custom scenario -> POST -> Navigate to results
- [ ] Write section: **Error Handling**
  - [ ] Document error response format
  - [ ] Document SSE disconnect/reconnect strategy
- [ ] Add link to `FE-INTEGRATION.md` from backend README
- [ ] Add link from frontend README

---

### 1.6 OpenAPI Polish

- [ ] Start backend server: `./gradlew run`
- [ ] Visit `http://localhost:8080/openapi` and save current spec
- [ ] Visit `http://localhost:8080/swagger` and audit UI
- [ ] Identify all endpoints missing descriptions
- [ ] Identify all DTOs with undocumented fields
- [ ] Review Ktor OpenAPI plugin docs for annotation pattern
- [ ] Add `@Description` annotations to all route parameters
- [ ] Add field descriptions to session DTOs
- [ ] Add field descriptions to event DTOs
- [ ] Add field descriptions to scenario DTOs
- [ ] Add example payloads for key endpoints (create session, run scenario)
- [ ] Validate generated spec with Swagger Editor (paste at editor.swagger.io)
- [ ] Test: generate TypeScript client from OpenAPI spec, verify it compiles

---

### 1.7 API Quickstart Documentation

- [ ] Start backend server: `./gradlew run`
- [ ] Create `backend/docs/API-QUICKSTART.md`
- [ ] Write section: **Prerequisites** (server running on :8080)
- [ ] Write section: **Create a Session** with curl + expected response
- [ ] Write section: **List Sessions** with curl + expected response
- [ ] Write section: **Get Session Detail** with curl + expected response
- [ ] Write section: **Run a Scenario** with curl + expected response
- [ ] Write section: **Stream Events (SSE)** with `curl -N` + example output
- [ ] Write section: **Get Coroutine Hierarchy** with curl + expected response
- [ ] Write section: **Get Thread Activity** with curl + expected response
- [ ] Write section: **Delete a Session** with curl + expected response
- [ ] Write section: **Health Check** with curl
- [ ] Verify: every curl command copy-pastes and works against running server
- [ ] Add link to quickstart from backend README
- [ ] Add link to quickstart from root SETUP.md

---

## Sprint 2: Backend Core (Week 3-4)

---

### 2.1 InstrumentedChannel

**Step 1: Define event types**
- [ ] Review existing event types in `backend/src/main/kotlin/.../events/` package
- [ ] Check if `channel/` subdirectory exists; if not, create it
- [ ] Define `ChannelCreated` event: channelId, capacity, creatorCoroutineId, timestamp
- [ ] Define `ChannelSendRequested` event: channelId, senderId, valuePreview, bufferSize, timestamp
- [ ] Define `ChannelSendCompleted` event: channelId, senderId, suspended (boolean), timestamp
- [ ] Define `ChannelReceiveRequested` event: channelId, receiverId, bufferSize, timestamp
- [ ] Define `ChannelReceiveCompleted` event: channelId, receiverId, valuePreview, suspended, timestamp
- [ ] Define `ChannelClosed` event: channelId, cause, timestamp
- [ ] Add `@Serializable` annotations to all new event types
- [ ] Register new event types in the polymorphic serializer (if sealed class hierarchy used)

**Step 2: Implement wrapper**
- [ ] Read `VizScope.kt` and `VizDispatchers.kt` to understand wrapper pattern
- [ ] Create `backend/src/main/kotlin/.../wrappers/VizChannel.kt`
- [ ] Implement `InstrumentedChannel<E>` class delegating to real `Channel<E>`
- [ ] Override `send()` - emit ChannelSendRequested before, ChannelSendCompleted after
- [ ] Override `receive()` - emit ChannelReceiveRequested before, ChannelReceiveCompleted after
- [ ] Override `trySend()` - emit events for non-suspending send attempts
- [ ] Override `tryReceive()` - emit events for non-suspending receive attempts
- [ ] Override `close()` - emit ChannelClosed event
- [ ] Add factory function: `fun <E> Channel<E>.instrumented(channelId: String, session: VizSession): Channel<E>`
- [ ] Add buffer depth estimation logic

**Step 3: Add scenarios**
- [ ] Create producer-consumer scenario: 1 producer, 1 consumer, bounded buffer of 3
- [ ] Create fan-out scenario: 1 producer, 3 consumers
- [ ] Create rendezvous scenario: capacity=0, demonstrating handoff
- [ ] Register new scenarios in scenario catalog

**Step 4: Test**
- [ ] Write unit test: send/receive emit correct events
- [ ] Write unit test: suspension detected correctly
- [ ] Write integration test: run producer-consumer scenario, verify event stream
- [ ] Verify: events appear in frontend event list for channel scenarios

---

### 2.5 Timeline Enrichment

**Step 1: Design enriched model**
- [ ] Read `ProjectionService.kt` for current timeline computation
- [ ] Read existing timeline endpoint response format
- [ ] Create `EnrichedTimeline` data class: coroutineId, totalDurationMs, activeMs, suspendedMs, threadSwitches
- [ ] Create `TimelineSegment` data class: startMs, endMs, state, threadId, dispatcherId

**Step 2: Implement computation**
- [ ] Add method to `ProjectionService`: `computeEnrichedTimeline(sessionId: String): List<EnrichedTimeline>`
- [ ] Compute total duration: last event timestamp - first event timestamp per coroutine
- [ ] Compute active time: sum of (resumed -> suspended/completed) intervals
- [ ] Compute suspended time: sum of (suspended -> resumed) intervals
- [ ] Compute thread switches: count of ThreadAssigned events with different threadId
- [ ] Build timeline segments from event pairs

**Step 3: Update API**
- [ ] Update timeline endpoint to return enriched data
- [ ] Add query parameter `?enriched=true` to opt into enriched response (backward compatible)
- [ ] Add test: enriched timeline computes correct durations for known event sequence
- [ ] Add test: thread switch count is accurate
- [ ] Update `FE-INTEGRATION.md` with new response shape

---

### 2.6 Scenario Catalog UX Hook

- [ ] Read current `/api/scenarios` endpoint and response format
- [ ] Read all scenario registration code to catalog existing scenarios
- [ ] Create `ScenarioMetadata` data class: id, name, description, category, difficulty, expectedCoroutines, expectedEvents, concepts, estimatedDurationMs
- [ ] Add metadata to each existing scenario definition
- [ ] Update `GET /api/scenarios` to return `List<ScenarioMetadata>`
- [ ] Add `category` field: "basic", "realistic", "advanced"
- [ ] Add `concepts` field: list of tags like "structured-concurrency", "cancellation", "dispatchers"
- [ ] Add test: endpoint returns metadata for all scenarios
- [ ] Verify: frontend scenario page shows richer information

---

## Sprint 3: Production Readiness (Week 5-6)

---

### 2.2 Persistence & Database Storage

**Step 1: Define storage interfaces**
- [ ] Read `EventStore.kt` - list all read/write operations
- [ ] Read `SessionManager.kt` - list all read/write operations
- [ ] Create `backend/src/main/kotlin/.../storage/SessionStore.kt` interface
  - [ ] `create(session)`, `get(id)`, `list()`, `delete(id)`, `update(id, ...)`
- [ ] Create `backend/src/main/kotlin/.../storage/EventStoreInterface.kt` interface
  - [ ] `append(sessionId, event)`, `getEvents(sessionId, sinceSeq, limit)`, `getCount(sessionId)`
- [ ] Create `backend/src/main/kotlin/.../storage/StorageFactory.kt` to create storage based on config

**Step 2: Refactor existing in-memory to implement interfaces**
- [ ] Create `InMemorySessionStore` implementing `SessionStore`
- [ ] Create `InMemoryEventStore` implementing `EventStoreInterface`
- [ ] Refactor `SessionManager` to depend on `SessionStore` interface
- [ ] Refactor `EventStore` to implement `EventStoreInterface`
- [ ] Verify: all existing tests still pass with in-memory implementation

**Step 3: Add Gradle dependencies**
- [ ] Add Exposed ORM (or chosen query library) to `build.gradle.kts`
- [ ] Add H2 database driver for development
- [ ] Add PostgreSQL driver for production
- [ ] Add HikariCP connection pool

**Step 4: Implement JDBC storage**
- [ ] Create SQL migration: `sessions` table (id, name, created_at, updated_at, status)
- [ ] Create SQL migration: `events` table (id, session_id, sequence_num, event_type, payload JSONB, created_at)
- [ ] Create SQL migration: indexes on (session_id, sequence_num)
- [ ] Create `JdbcSessionStore` implementing `SessionStore`
- [ ] Create `JdbcEventStore` implementing `EventStoreInterface`
- [ ] Handle JSON serialization of events to/from JSONB column

**Step 5: Configuration**
- [ ] Add config to `application.yaml`:
  ```yaml
  app:
    storage:
      type: "memory"  # or "jdbc"
      jdbc:
        url: "jdbc:h2:./data/coroutine-viz"
        driver: "org.h2.Driver"
  ```
- [ ] Create `StorageFactory` that reads config and creates appropriate implementation
- [ ] Wire `StorageFactory` into application module

**Step 6: Retention & cleanup**
- [ ] Add retention config: `max-age: "7d"`, `max-events-per-session: 100000`
- [ ] Create background cleanup coroutine that runs every hour
- [ ] Delete sessions older than max-age
- [ ] Trim events exceeding max-events-per-session

**Step 7: Test**
- [ ] Write integration test: create session, restart (simulate), session persists
- [ ] Write integration test: events survive restart
- [ ] Write integration test: retention deletes old sessions
- [ ] Write test: in-memory mode still works when config says "memory"
- [ ] Verify: `./gradlew run` with JDBC config, create sessions, restart, sessions still there

---

### 2.3 Load Profiling & Backpressure

**Step 1: Benchmark harness**
- [ ] Create `backend/src/main/kotlin/.../testing/LoadTestScenario.kt`
- [ ] Implement synthetic event producer: N coroutines emitting M events/second
- [ ] Add configurable parameters: coroutine count, events per second, duration
- [ ] Add route: `POST /api/testing/load-test` (only enabled in dev profile)

**Step 2: Add Micrometer metrics**
- [ ] Review existing Micrometer setup
- [ ] Add counter: `viz.events.emitted.total`
- [ ] Add counter: `viz.events.dropped.total`
- [ ] Add gauge: `viz.events.buffer.size`
- [ ] Add gauge: `viz.sse.clients.active`
- [ ] Add counter: `viz.sse.events.sent.total`
- [ ] Wire counters into EventBus publish method
- [ ] Wire dropped counter into `onBufferOverflow` callback

**Step 3: Tune & document**
- [ ] Run load test at 100 events/sec - record metrics
- [ ] Run load test at 1,000 events/sec - record metrics
- [ ] Run load test at 10,000 events/sec - record metrics
- [ ] Identify breaking point (where drops start)
- [ ] Tune `MutableSharedFlow` buffer parameters based on results
- [ ] Document throughput limits in backend README
- [ ] Document tuning parameters in `application.yaml` comments
- [ ] Verify: Prometheus scrape at `/metrics-micrometer` includes new counters

---

## Sprint 4: Advanced Features + Pilot Prep (Week 7-8)

---

### 2.4 Sampling & Retention Controls

- [ ] Design sampling config schema (per-event-type rate: 0.0 to 1.0)
- [ ] Add sampling config to `application.yaml`
- [ ] Create `SamplingEventFilter` class that accepts config and filters events
- [ ] Integrate filter into EventBus publish pipeline
- [ ] Always pass through lifecycle events (Created, Completed, Cancelled) regardless of rate
- [ ] Apply sampling to high-frequency events (ThreadAssigned, Suspended, Resumed)
- [ ] Add Micrometer counter for sampled-out events
- [ ] Add `X-Sampled: true/false` metadata to SSE events so FE knows
- [ ] Write test: sampling at 0.5 drops ~50% of target event type
- [ ] Write test: lifecycle events always pass through
- [ ] Create retention cleanup (if not done in 2.2): TTL cleaner for old sessions
- [ ] Document sampling config in README

---

### 4.1 Export Capabilities (PNG/SVG/Video)

**Step 1: Research & setup**
- [ ] Install `html2canvas` in frontend: `pnpm add html2canvas`
- [ ] Install `@types/html2canvas` for TypeScript
- [ ] Research SVG export approach for graph view (uses react-zoom-pan-pinch)
- [ ] Research `MediaRecorder` API for video recording

**Step 2: Static export (PNG)**
- [ ] Create `frontend/src/lib/export-utils.ts`
- [ ] Implement `exportAsPng(elementId: string, filename: string)` function
- [ ] Add "Export PNG" button to `CoroutineTreeGraph.tsx` toolbar
- [ ] Add "Export PNG" button to `CoroutineTree.tsx` toolbar
- [ ] Add "Export PNG" button to `ThreadLanesView.tsx` toolbar
- [ ] Test: click export, PNG file downloads with correct content

**Step 3: SVG export**
- [ ] Implement `exportAsSvg(elementId: string, filename: string)` function
- [ ] Serialize SVG from graph view
- [ ] Include embedded styles in SVG for standalone rendering
- [ ] Add "Export SVG" button alongside PNG button
- [ ] Test: exported SVG opens correctly in browser/Figma

**Step 4: Video/GIF export**
- [ ] Implement `startRecording(canvasElement)` using MediaRecorder
- [ ] Add record button (red dot) to session detail toolbar
- [ ] Show recording indicator while active
- [ ] On stop: generate WebM blob and trigger download
- [ ] Test: record a scenario replay, video plays back correctly

**Step 5: Export UI**
- [ ] Create `ExportMenu` dropdown component with format options
- [ ] Place export menu in `SessionDetails.tsx` toolbar
- [ ] Show toast notification on successful export
- [ ] Handle errors gracefully (e.g., canvas tainted by CORS)

---

### 4.2 Scenario Authoring UI Enhancement

**Step 1: Backend validation endpoint**
- [ ] Create `POST /api/scenarios/validate` endpoint
- [ ] Accept same DSL body as `/api/scenarios/custom`
- [ ] Validate structure without executing: check required fields, valid action types, hierarchy consistency
- [ ] Return `{ valid: true }` or `{ valid: false, errors: [...] }`
- [ ] Add test: valid DSL returns valid=true; invalid returns errors

**Step 2: Preset templates**
- [ ] Define 5 preset templates as JSON:
  - [ ] "Simple Parent-Child" (1 parent, 2 children)
  - [ ] "Producer-Consumer" (channel-based)
  - [ ] "Race Condition" (shared state, no mutex)
  - [ ] "Cancellation Cascade" (parent cancel propagates)
  - [ ] "Async/Await Parallel" (3 async + awaitAll)
- [ ] Store templates in frontend as constants or fetch from backend
- [ ] Add template selection buttons in `ScenarioBuilder.tsx`
- [ ] On template click: pre-fill form fields

**Step 3: UX improvements**
- [ ] Add inline validation feedback using the validation endpoint
- [ ] Show validation errors next to the relevant form fields
- [ ] Add "Preview DSL" panel showing the JSON that will be sent
- [ ] Add "Reset" button to clear the form
- [ ] Improve coroutine hierarchy composer with visual nesting indicators
- [ ] Test: create scenario from template, validate, run, see results

---

### 6.1 GTM Pilot - Preparation

- [ ] Define pilot success metrics document:
  - [ ] MTTR for concurrency bugs (before/after)
  - [ ] Sessions created per developer per week
  - [ ] Scenarios run per session
  - [ ] NPS score survey (1-10)
- [ ] Create pilot onboarding guide (1-2 pages):
  - [ ] How to install/run the visualizer
  - [ ] Quick tutorial: create session, run scenario, explore views
  - [ ] How to instrument their own code
  - [ ] Feedback channels (survey link, Slack channel)
- [ ] Create feedback survey (Google Form or Typeform):
  - [ ] "What did you use the tool for?"
  - [ ] "Did it help you find a bug?"
  - [ ] "What's missing?"
  - [ ] NPS: "How likely to recommend?"
- [ ] Identify 3-5 candidate pilot teams:
  - [ ] Check Kotlin Slack for active coroutine discussions
  - [ ] Reach out to colleagues using Kotlin
  - [ ] Post in r/Kotlin offering free pilot access
- [ ] Schedule initial outreach (emails/DMs)

---

## Sprint 5: Auth & SDK (Week 9-10)

---

### 3.1 Authentication / RBAC

**Phase A: API Key Authentication**
- [ ] Add `ktor-server-auth` dependency to `build.gradle.kts`
- [ ] Create `backend/src/main/kotlin/.../auth/ApiKeyStore.kt` - in-memory key store (move to DB later)
- [ ] Create `backend/src/main/kotlin/.../auth/AuthConfig.kt` - configure bearer token authentication
- [ ] Install Authentication plugin in application module
- [ ] Create `authenticate("api-key")` wrapper for session routes
- [ ] Add `X-API-Key` header support
- [ ] Allow unauthenticated access to `/health` and `/openapi`
- [ ] Add config for initial API keys (dev: hardcoded, prod: from env)
- [ ] Write test: request without API key returns 401
- [ ] Write test: request with valid key returns 200
- [ ] Write test: health endpoint works without auth

**Phase B: JWT Support**
- [ ] Add `ktor-server-auth-jwt` dependency
- [ ] Create JWT verification configuration (issuer, audience, secret/JWKS)
- [ ] Add JWT validation to authentication pipeline
- [ ] Extract user ID and roles from JWT claims
- [ ] Create `UserPrincipal` with userId, email, roles
- [ ] Write test: valid JWT grants access; expired JWT returns 401

**Phase C: Tenant Isolation**
- [ ] Add `user_id` / `tenant_id` column to sessions table (if JDBC)
- [ ] Filter session queries by authenticated user's tenant
- [ ] Prevent cross-tenant session access (404, not 403)
- [ ] Admin role bypasses tenant filter
- [ ] Write test: user A cannot see user B's sessions
- [ ] Write test: admin can see all sessions

---

### 3.4 SDK / Library Packaging

**Step 1: Multi-module setup**
- [ ] Create `backend/sdk/` directory
- [ ] Create `backend/sdk/build.gradle.kts` with library plugin
- [ ] Move `VizScope.kt` → `sdk/src/main/kotlin/`
- [ ] Move `VizDispatchers.kt` → `sdk/src/main/kotlin/`
- [ ] Move `VizDeferred.kt` → `sdk/src/main/kotlin/`
- [ ] Move `VizFlow.kt` → `sdk/src/main/kotlin/` (if exists)
- [ ] Move `VizChannel.kt` → `sdk/src/main/kotlin/` (from 2.1)
- [ ] Move related event types needed by SDK
- [ ] Update `backend/server/build.gradle.kts` (existing app) to depend on SDK module
- [ ] Verify: `./gradlew build` still passes

**Step 2: Connection config**
- [ ] Create `sdk/src/main/kotlin/VizConfig.kt`:
  - [ ] `serverUrl: String`
  - [ ] `sessionName: String`
  - [ ] `enabled: Boolean = true`
  - [ ] `autoCreateSession: Boolean = true`
- [ ] Create `VizSession.connect(config: VizConfig)` factory function
- [ ] Implement HTTP client to create session on backend
- [ ] Implement event sending: POST events to backend (or use EventBus directly if same JVM)

**Step 3: Sample application**
- [ ] Create `backend/samples/` directory
- [ ] Create `backend/samples/simple-app/build.gradle.kts`
- [ ] Create sample app that imports SDK and runs 3 coroutines
- [ ] Add README to samples explaining how to run
- [ ] Verify: run sample app, open visualizer, see events

**Step 4: Publishing**
- [ ] Configure `maven-publish` plugin in SDK build.gradle.kts
- [ ] Set group ID: `com.jh.coroutine-viz`
- [ ] Set artifact ID: `coroutine-viz-sdk`
- [ ] Choose publishing target: GitHub Packages (easiest) or Maven Local for now
- [ ] Publish: `./gradlew :sdk:publishToMavenLocal`
- [ ] Verify: sample app uses published artifact instead of project dependency

---

## Sprint 6: Enterprise & Monetization (Week 11-14)

---

### 3.2 OpenTelemetry Export

**Step 1: Dependencies**
- [ ] Add `opentelemetry-api` to `build.gradle.kts`
- [ ] Add `opentelemetry-sdk` to `build.gradle.kts`
- [ ] Add `opentelemetry-exporter-otlp` to `build.gradle.kts`
- [ ] Add config section in `application.yaml` under `app.exporters.otel`

**Step 2: Event-to-Span mapper**
- [ ] Create `backend/src/main/kotlin/.../exporters/OTelExporter.kt`
- [ ] Map `CoroutineCreated` → start new Span with attributes (coroutineId, parentId, dispatcher)
- [ ] Map `CoroutineCompleted` → end Span
- [ ] Map `CoroutineCancelled` → end Span with error status
- [ ] Map `CoroutineSuspended` → add Span event "suspended" with reason
- [ ] Map `CoroutineResumed` → add Span event "resumed" with thread
- [ ] Map `ThreadAssigned` → add Span attribute for thread name
- [ ] Maintain `spanRegistry: ConcurrentHashMap<String, Span>` for correlation

**Step 3: Exporter lifecycle**
- [ ] Subscribe to EventBus when enabled
- [ ] Batch events using OpenTelemetry SDK batch processor
- [ ] Configure flush interval from config
- [ ] Graceful shutdown: flush remaining spans on application stop
- [ ] Zero overhead when `enabled: false`

**Step 4: Test**
- [ ] Start Jaeger locally: `docker run -p 16686:16686 -p 4317:4317 jaegertracing/all-in-one`
- [ ] Enable OTel exporter in config
- [ ] Run a scenario
- [ ] Verify: coroutine spans visible in Jaeger UI at localhost:16686
- [ ] Verify: parent-child relationships preserved in trace view
- [ ] Write test: exporter disabled, no spans emitted (verify no side effects)

---

### 3.3 Comparative Runs / Session Diffs

**Step 1: Comparison service**
- [ ] Create `backend/src/main/kotlin/.../session/ComparisonService.kt`
- [ ] Implement `compare(sessionIdA: String, sessionIdB: String): SessionComparison`
- [ ] Compute: event count by type for each session, then delta
- [ ] Compute: total coroutine count delta
- [ ] Compute: total duration delta (using enriched timeline from 2.5)
- [ ] Compute: average coroutine duration delta
- [ ] Compute: thread utilization delta per dispatcher

**Step 2: API endpoint**
- [ ] Add `GET /api/sessions/compare?a={sessionId}&b={sessionId}` route
- [ ] Validate both sessions exist; return 404 if not
- [ ] Return `SessionComparison` JSON

**Step 3: Test**
- [ ] Create two sessions by running the same scenario twice
- [ ] Call compare endpoint
- [ ] Verify: deltas are near-zero for identical scenarios
- [ ] Create two sessions with different scenarios
- [ ] Verify: deltas show meaningful differences
- [ ] Add `.http` example for comparison endpoint

---

### 6.2 Premium Tier & Billing (Stripe)

**Step 1: Stripe setup**
- [ ] Create Stripe account at stripe.com
- [ ] Create Product: "Coroutine Visualizer Premium"
- [ ] Create Price: $29/month recurring
- [ ] Create Price: $249/year recurring
- [ ] Get Stripe API keys (test mode)
- [ ] Add Stripe SDK dependency to backend: `com.stripe:stripe-java`

**Step 2: User accounts**
- [ ] Create `users` table: id, email, password_hash, stripe_customer_id, plan, created_at
- [ ] Create registration endpoint: `POST /api/auth/register` (email + password)
- [ ] Create login endpoint: `POST /api/auth/login` returns JWT
- [ ] Hash passwords with bcrypt
- [ ] Create user on Stripe (`Stripe.customers.create`)
- [ ] Write test: register + login flow works

**Step 3: Stripe Checkout integration**
- [ ] Create `POST /api/billing/checkout` endpoint
- [ ] Generate Stripe Checkout Session with success/cancel URLs
- [ ] Return checkout URL to frontend
- [ ] Create `POST /api/webhooks/stripe` for event processing
- [ ] Handle `checkout.session.completed` → activate premium
- [ ] Handle `customer.subscription.deleted` → deactivate premium
- [ ] Handle `invoice.payment_failed` → notify user
- [ ] Verify webhook signature for security

**Step 4: Feature gating**
- [ ] Create `premiumOnly()` route wrapper middleware
- [ ] Check user's plan in JWT claims or database
- [ ] Return 402 Payment Required for non-premium users
- [ ] Gate export endpoint behind premium
- [ ] Gate shareable links behind premium
- [ ] Gate advanced scenario builder behind premium

**Step 5: Frontend billing UI**
- [ ] Create `frontend/src/components/UpgradePrompt.tsx` component
- [ ] Create `frontend/src/components/PricingTable.tsx` component
- [ ] Create `frontend/src/hooks/use-auth.ts` hook with `isPremium` flag
- [ ] Wrap premium features with `<PremiumFeature>` gate component
- [ ] Add "Upgrade" button in navbar for free users
- [ ] On click: call `/api/billing/checkout`, redirect to Stripe
- [ ] On return: refresh user profile, show premium badge
- [ ] Add billing portal link for existing subscribers

---

### 6.3 Marketing Website

- [ ] Choose tech stack (Astro recommended: static, fast, simple)
- [ ] Initialize project: `npm create astro@latest marketing-site`
- [ ] Design homepage sections:
  - [ ] Hero: tagline + demo GIF + CTA button
  - [ ] Features: 4-6 feature cards with icons
  - [ ] How it works: 3-step flow diagram
  - [ ] Pricing: free/premium/enterprise cards
  - [ ] Testimonials: placeholder (fill after pilot)
  - [ ] Footer: links to GitHub, docs, contact
- [ ] Create screenshots/GIFs from the actual tool
- [ ] Write copy for each section
- [ ] Build and preview locally
- [ ] Register domain (e.g., coroutine-viz.dev)
- [ ] Deploy to Vercel/Netlify/Cloudflare Pages
- [ ] Verify: site loads, CTA links to app/GitHub

---

## Sprint 7+: Scale (Week 15+)

---

### 5.1 IntelliJ Plugin

**Phase A: Skeleton (Week 1-2)**
- [ ] Install IntelliJ Platform Plugin SDK
- [ ] Read `INTELLIJ_PLUGIN_GUIDE.md` thoroughly
- [ ] Create new Gradle project with `org.jetbrains.intellij` plugin
- [ ] Create `plugin.xml` with tool window extension
- [ ] Create empty tool window panel
- [ ] Verify: plugin loads in IntelliJ sandbox

**Phase B: WebView Integration (Week 2-3)**
- [ ] Add JCEF (Chromium Embedded) panel to tool window
- [ ] Load frontend React app URL in JCEF
- [ ] Handle JCEF lifecycle (init, dispose)
- [ ] Test: React frontend renders inside IntelliJ tool window

**Phase C: Backend Communication (Week 3-4)**
- [ ] Auto-detect running backend on localhost:8080
- [ ] Show connection status indicator in tool window
- [ ] Forward session/scenario data between IDE and embedded frontend
- [ ] Add "Start Visualizer Backend" action if not running

**Phase D: Run Configuration (Week 4-6)**
- [ ] Create custom Run Configuration type: "Coroutine Visualizer"
- [ ] Wrap user's main class with VizSession instrumentation
- [ ] Add VM options to inject SDK
- [ ] Auto-connect plugin to the session

**Phase E: DebugProbes Hybrid (Week 6-8)**
- [ ] Read `INTELLIJ_PLUGIN_INTEGRATION.md` for hybrid approach
- [ ] Activate DebugProbes when Debug mode is used
- [ ] Merge DebugProbes snapshot with VizSession events
- [ ] Display combined view in tool window

**Phase F: Publish**
- [ ] Write plugin description for JetBrains Marketplace
- [ ] Create plugin icon
- [ ] Submit to JetBrains Marketplace for review
- [ ] Verify: installable from marketplace search

---

### 5.2 CI/CD Integration

- [ ] Create `backend/ci-plugin/` Gradle module
- [ ] Implement Gradle task: `coroutineVizCheck`
- [ ] Accept config: scenario to run, baseline session ID, threshold rules
- [ ] Run scenario against local/remote backend
- [ ] Compare results against baseline using comparison service (3.3)
- [ ] Fail task if thresholds exceeded
- [ ] Create CLI alternative: `java -jar coroutine-viz-ci.jar check --config ci-config.yaml`
- [ ] Write documentation: how to add to CI pipeline
- [ ] Create example GitHub Actions workflow
- [ ] Test: pipeline fails when regression introduced; passes when clean

---

### 4.3 Shareable Visualizations

- [ ] Design share token model: UUID token, session ID, permissions, expiry
- [ ] Create `shares` table: token, session_id, created_by, expires_at, access_level
- [ ] Create `POST /api/sessions/:id/share` endpoint - generate share token
- [ ] Create `GET /api/shared/:token` endpoint - return session data (read-only)
- [ ] Frontend: add "Share" button to session detail page
- [ ] Show share link in modal with copy-to-clipboard
- [ ] Create read-only view component for shared sessions (no delete/create buttons)
- [ ] Add expiry option (1 day, 7 days, 30 days, never)
- [ ] Test: share link works in incognito browser
- [ ] Test: expired link returns 410 Gone

---

### 5.3 Bytecode Instrumentation

- [ ] Study Kotlin compiler plugin architecture (IR backend)
- [ ] Study `kotlinx-coroutines-debug` implementation for reference
- [ ] Create `backend/compiler-plugin/` Gradle module
- [ ] Implement minimal IR transform: intercept `launch` builder calls
- [ ] Wrap `launch` block with event emission (Created, Started, Completed)
- [ ] Add Gradle plugin that applies the compiler plugin
- [ ] Configure include/exclude package patterns
- [ ] Test: user code with plain `launch` emits events automatically
- [ ] Expand to `async`, `withContext`, `withTimeout`
- [ ] Add annotation `@NoVisualize` to opt out specific blocks
- [ ] Performance test: measure overhead vs baseline
- [ ] Document usage and limitations

---

### 6.4 Content Marketing (Ongoing)

**Month 1**
- [ ] Record 3-5 minute demo video showing tool in action
- [ ] Upload to YouTube with SEO-optimized title/description
- [ ] Write blog post: "Visualizing Kotlin Coroutines: See Your Code Think"
- [ ] Publish on Medium and Dev.to
- [ ] Post demo on Reddit r/Kotlin and r/androiddev
- [ ] Share in Kotlin Slack #coroutines channel
- [ ] Tweet/post from project account

**Month 2**
- [ ] Draft conference talk abstract
- [ ] Submit to KotlinConf CFP (check deadline)
- [ ] Submit to Droidcon CFP
- [ ] Submit to local Kotlin/JVM meetup
- [ ] Create YouTube tutorial: "Debugging Coroutine Race Conditions Visually"
- [ ] Write blog post: "5 Coroutine Anti-Patterns You Can See"

**Month 3+**
- [ ] Write deep-dive blog posts for each scenario type
- [ ] Create comparison blog: "DebugProbes vs Coroutine Visualizer"
- [ ] Start newsletter (optional)
- [ ] Engage in Stack Overflow answers linking to tool
- [ ] Weekly social media posts with visualization screenshots

---

### 6.5 Partnership Outreach (Ongoing)

**Preparation**
- [ ] Create partnership pitch deck (10 slides):
  - [ ] Problem, Solution, Demo, Market, Traction, Partnership Model, Ask
- [ ] Compile traction metrics: GitHub stars, user count, pilot results

**JetBrains**
- [ ] Identify JetBrains Developer Advocacy contact
- [ ] Send introductory email with demo link
- [ ] Propose: Plugin marketplace listing + co-marketing blog post
- [ ] Follow up within 2 weeks

**Learning Platforms**
- [ ] Identify Kotlin course creators on Udemy (top 5 by enrollment)
- [ ] Email offering free premium access in exchange for integration/mention
- [ ] Propose affiliate program (20% revenue share)

**Kotlin Foundation**
- [ ] Apply for Kotlin Foundation grant (if available)
- [ ] Offer to present at Kotlin User Group events
- [ ] Request listing on Kotlin ecosystem page

---

## Progress Tracker

| Sprint | Items | Target Date | Status |
|--------|-------|-------------|--------|
| Sprint 1 | 1.1 - 1.7 (Foundation) | Week 2 | Not started |
| Sprint 2 | 2.1, 2.5, 2.6 (Backend Core) | Week 4 | Not started |
| Sprint 3 | 2.2, 2.3 (Production) | Week 6 | Not started |
| Sprint 4 | 2.4, 4.1, 4.2, 6.1 (Features + Pilot) | Week 8 | Not started |
| Sprint 5 | 3.1, 3.4 (Auth + SDK) | Week 10 | Not started |
| Sprint 6 | 3.2, 3.3, 6.2, 6.3 (Enterprise) | Week 14 | Not started |
| Sprint 7+ | 5.1, 5.2, 4.3, 5.3, 6.4, 6.5 (Scale) | Week 15+ | Not started |

**Total checkboxes: ~300+**
**Critical path to MVP premium launch: Sprint 1-6 (~14 weeks)**
