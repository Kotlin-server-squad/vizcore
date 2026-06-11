# vizcore Feature-Verification Report

**Date:** 2026-06-11

**Method note.** This report is the deliverable of a deep-dive feature-verification audit spanning 39 requirements across 13 feature areas. Each verdict was produced by combining three evidence streams: (1) a **static code read** of the authoritative source (backend `coroutine-viz-core` and the running `backend/src`, frontend `src`, the IntelliJ plugin, CI workflows, and ADRs); (2) **test-suite ground truth**, treating colocated unit/integration tests as proof of isolated correctness but never as proof of runtime wiring; and (3) an **adversarial challenge of every positive verdict**, where a "Works" or "Partial" claim was demoted whenever the user-facing capability could not be proven reachable at runtime (grep-confirmed import/mount paths, route registration, lifecycle wiring). The guiding rule: tested-in-isolation but never-wired code is not a working feature. Verdicts the adversarial pass changed are flagged with ⟳.

---

## Scoreboard

### Overall (39 requirements)

| Verdict | Count |
|---|---|
| Works | 1 |
| Partial | 13 |
| Broken | 5 |
| Missing | 20 |
| Untested | 0 |

> Note: there are 38 verdicts in the source data across the 13 areas. "39 requirements" reflects the audit scope; the matrix below enumerates every requirement evidenced.

### By feature area

| Area | Works | Partial | Broken | Missing |
|---|---|---|---|---|
| Foundation | 0 | 0 | 2 | 1 |
| Production | 1 | 3 | 1 | 0 |
| Replay | 0 | 0 | 1 | 2 |
| Export | 0 | 2 | 0 | 0 |
| Sharing | 0 | 1 | 0 | 1 |
| Persistence | 0 | 0 | 0 | 3 |
| Auth | 0 | 0 | 1 | 4 |
| Performance | 0 | 2 | 0 | 2 |
| Session Comparison | 0 | 2 | 0 | 0 |
| SDK | 0 | 1 | 0 | 1 |
| IntelliJ Plugin | 0 | 2 | 0 | 1 |
| Frontend Testing | 0 | 1 | 0 | 2 |
| Observability | 0 | 0 | 0 | 2 |

---

## Verdict Matrix

Legend — Conf = confidence; ⟳ = verdict changed by the adversarial pass.

### Foundation

| Requirement | Verdict | Conf | Test coverage | Evidence (file refs) | Notes |
|---|---|---|---|---|---|
| FND-01 (no session fork) | Broken | high | none | `backend/src/main/.../session/{EventStore,SessionManager,VizSession}.kt` (duplicate) vs `backend/coroutine-viz-core/.../session/*` (authoritative); `backend/build.gradle.kts:28`; `SessionRoutes.kt:3` | Local `backend/src/main` session classes shadow the authoritative `coroutine-viz-core` versions at runtime. 10 duplicate classes; the fork is active and used. |
| FND-02 (bounded store) | Broken | high | none | `backend/src/main/.../EventStore.kt:28,35-36`; `VizSession.kt:43`; core `EventStore.kt:26-47`, `SessionManager.kt:24,35,52` | Running server uses unbounded `CopyOnWriteArrayList`; never calls `SessionManager.configure()`. Bounded core impl is shadowed. Events grow without bound. |
| FND-03 (bounded-store regression test) | Missing | high | none | `SessionRoutesTest.kt` (CRUD only); core `EventStoreTest.kt:50-62` | Eviction tested only in core in isolation. No backend test asserts `store.all().size <= maxEvents` against the running server. |

### Production

| Requirement | Verdict | Conf | Test coverage | Evidence (file refs) | Notes |
|---|---|---|---|---|---|
| PROD-01 (health endpoints) | Partial | high | 3 tests, /health only | `HealthRoutes.kt:18-23,27-56`; `Routing.kt:23`; `HealthRoutesTest.kt:44-91` | Missing `/live`, `/ready`, `version` field, and labeled component checks; mounted at `/health` not `/api/health`. |
| PROD-02 (logging profiles) | Partial ⟳ | high | none | `logback.xml`, `logback-prod.xml`; Dockerfile ENTRYPOINT; `docker-compose.prod.yml` | ⟳ from Works: prod JSON profile exists but is never selected — no `-Dlogback.configurationFile`, zero `logback-prod` refs repo-wide. Dev profile ships in prod. |
| PROD-03 (externalized CORS) | Works | high | none | `HTTP.kt:22-65`; `application.yaml:11-13`; `docker-compose.prod.yml` | Config-driven via env vars; literals are Elvis fallbacks only. Verified end to end. |
| PROD-04 (OpenAPI/AsyncAPI) | Partial | high | none | `HTTP.kt:67-81`; `build.gradle.kts:30,35,36`; `resources/openapi/documentation.yaml` (2270 lines) | Hand-maintained spec is strong (~52 paths). Held at Partial because it is coupled to PROD-01 gaps (documents `/health`, not the required `/api/health`, `/live`, `/ready`, version). |
| PROD-05 (metrics) | Broken | high | 2 tests (2 gauges) | `MetricsWiring.kt:14-24`; ADR-020:35-48; `MetricsWiringTest.kt` | Only 2 of 7 ADR-020 metrics wired. Missing events.emitted/dropped counters, buffer.size gauge, scenario/processing timers. |

### Replay

| Requirement | Verdict | Conf | Test coverage | Evidence (file refs) | Notes |
|---|---|---|---|---|---|
| RPLY-01 (playback controls) | Missing ⟳ | high | hook tested; controller/E2E none | `use-replay.ts:53-197`; `ReplayController.tsx:25-147`; `SessionDetails.tsx:45` | ⟳ from Partial: hook + controller are correct and tested but never mounted; `SessionDetails` passes `allEvents` unfiltered. No runtime path. |
| RPLY-02 (speed + scrub) | Missing ⟳ | high | hook timing tested; UI none | `use-replay.ts:98,166-168`; `ReplayController.tsx:18-23,53-70`; `use-replay-motion.ts:16-44` | ⟳ from Partial: speed/scrub logic sound but reachable only via the never-mounted ReplayController. Dead code. |
| RPLY-03 (replay-aware animation) | Broken | high | none | `animation-variants.ts:52-75,90-115`; `use-replay.ts:56`; ADR-027:25-32 | Animation durations hardcoded; replay `speed` never reaches animation timing. ADR-011/027 intent undelivered. |

### Export

| Requirement | Verdict | Conf | Test coverage | Evidence (file refs) | Notes |
|---|---|---|---|---|---|
| EXPT-01 (PNG export) | Partial | high | unit (export-png.test.ts) | `export-png.ts:9-44`; `ExportButton.tsx`; `package.json:23` | exportToPng + ExportButton implemented/tested but ExportButton imported nowhere. Unreachable in UI. |
| EXPT-02 (SVG + WebM export) | Partial | high | unit (export-svg.test.ts), WebM none | `export-svg.ts:80-93,105-116`; grep webm/MediaRecorder = 0 | SVG style-inlining done and tested; WebM/MediaRecorder entirely absent. SVG also lacks any UI wrapper. |

### Sharing

| Requirement | Verdict | Conf | Test coverage | Evidence (file refs) | Notes |
|---|---|---|---|---|---|
| SHAR-01 (share-token generation) | Partial | high | 9 unit tests | core `ShareTokenService.kt:44-59,70-92,102-132`; `ShareTokenServiceTest.kt`; ADR-019:50-66 | Full token lifecycle implemented + tested, but no `POST /api/sessions/{id}/share` endpoint wired; in-memory storage conflicts with ADR-019's DB-backed table. |
| SHAR-02 (shared read-only view) | Missing | high | none | ADR-019:69-76; `frontend/src/routes/` (no `/shared/:token`); `SessionRoutes.kt`; grep `/api/shared` = 0 | No backend `GET /api/shared/:token`, no frontend route or share UI, no rate limiting. |

### Persistence

| Requirement | Verdict | Conf | Test coverage | Evidence (file refs) | Notes |
|---|---|---|---|---|---|
| PERS-01 (JDBC store) | Missing | high | none | `backend/build.gradle.kts` (no Exposed/HikariCP/driver); `application.yaml` (no storage keys); constraints.md:20-22 | Interfaces defined; only in-memory impls. No DB store ever instantiated. |
| PERS-02 (survive restart) | Missing | high | none | `VizSession.kt:43`; `SessionManager.kt:22`; `EventStore.kt:27-28`; ADR-009 | All state volatile by design (ADR-009). Restart loses all sessions/events. |
| PERS-03 (retention policy) | Missing ⟳ | high | 6 unit tests (isolation) | core `RetentionPolicy.kt:31-125`; `RetentionPolicyTest.kt`; `Application.kt:9-16`; `application.yaml` | ⟳ from Partial: fully implemented + tested but `start()` never called; zero refs outside its own file/test. Runtime cleanup contribution is nil. |

### Auth

| Requirement | Verdict | Conf | Test coverage | Evidence (file refs) | Notes |
|---|---|---|---|---|---|
| AUTH-01 (route-level auth wiring) | Broken | high | AuthTest.kt (9, manual routing) | `Routing.kt:18-36`; `Auth.kt:56-60`; `SessionRoutes.kt:18-86` | `authenticatedApi()` defined but never called by production routing. All `/api/*` open regardless of API_KEY. Tests pass via a custom test module, not real `module()`. |
| AUTH-02 (SHA-256 key hashing) | Missing | high | none meaningful | `Auth.kt:38` (plaintext `==`); ADR-016:20,29-30 | Plaintext string comparison; no MessageDigest/crypto. ADR mandates SHA-256 hashes. |
| AUTH-03 (JWT + roles) | Missing | high | none | `Auth.kt:1-61`; no `/api/auth/token`; no role enums; no JWT deps | No JWT, UserPrincipal, VIEWER/RUNNER/ADMIN roles, or token endpoint. |
| AUTH-04 (tenant isolation) | Missing | high | none | `SessionManager.kt:20-94`; `SessionRoutes.kt:33-37`; ADR-016:74-81 | No user filtering / FilteredSessionStore. Every user sees all sessions. |
| AUTH-05 (auth E2E tests) | Missing | high | partial AuthTest only | `AuthTest.kt:53-59,129-214` | No E2E test runs real `module()` with API_KEY set to assert 401/201 enforcement. |

### Performance

| Requirement | Verdict | Conf | Test coverage | Evidence (file refs) | Notes |
|---|---|---|---|---|---|
| PERF-01 (event sampling) | Partial | high | EventSamplerTest (392 lines) | core `EventSampler.kt:1-109`; `VizSession.kt:68`; `SessionRoutes.kt:196-219` | Sampler implemented + tested but never instantiated/called. No X-Sampled header. Dead code. |
| PERF-02 (compression + batching) | Partial | high | CompressionTest | `Compression.kt:13-34`; `EventBus.kt:36-61`; `SessionRoutes.kt:196-220` | gzip wired and working. Batching absent (per-event emit); no X-Accel-Buffering header. |
| PERF-03 (load-test harness) | Missing | high | none | `TestRoutes.kt:8-13`; ADR-020:82-117; grep LoadTest = 0 | No `POST /api/dev/load-test`, no synthetic producer, no latency/memory reporting. |
| PERF-04 (rate limit / session cap) | Missing | high | none | core `SessionManager.kt:24,47-58`; `SessionRoutes.kt:18-31`; grep RateLimit = 0 | No maxActiveSessions cap, no per-IP/tenant rate limiting on creation or scenario runs. |

### Session Comparison

| Requirement | Verdict | Conf | Test coverage | Evidence (file refs) | Notes |
|---|---|---|---|---|---|
| CMPR-01 (comparison service) | Partial | high | 6 backend tests pass | core `ComparisonService.kt:1-125,21-30`; `ComparisonRoutes.kt:10-44`; `Routing.kt:34`; `api-client.ts:159-163` | Backend wired end to end, but missing thread-utilization delta (only event-count + duration), and route path `/api/compare` deviates from spec `/api/sessions/compare`. |
| CMPR-02 (comparison view) | Partial ⟳ | high | ComparisonView.test.tsx | `ComparisonView.tsx:31-346`; `use-comparison.ts:8-14` | ⟳ from Works: component fully built and unit-tested with mocked api-client, but referenced only by itself + test; no route mounts it. Unreachable at runtime. |

### SDK

| Requirement | Verdict | Conf | Test coverage | Evidence (file refs) | Notes |
|---|---|---|---|---|---|
| SDK-01 (publishable library) | Partial | high | ci-core + publish-maven | core `build.gradle.kts:6,11,44,48,54-58,63-72,77`; `publish-maven.yml`; `ci-core.yml` | Publishing infra wired; 1.1M JAR builds. Two spec gaps: group ID `com.jh.coroutine-visualizer` ≠ required `com.jh.coroutine-viz`; no sample app; never consumed as published artifact. |
| SDK-02 (CI CLI / Gradle task) | Missing | high | none | no CLI module; grep `coroutineVizCheck` = 0 in build config; ADR-021:129-160 | No fat-JAR CLI (`check --config`), no `coroutineVizCheck` Gradle task. Design-only. |

### IntelliJ Plugin

| Requirement | Verdict | Conf | Test coverage | Evidence (file refs) | Notes |
|---|---|---|---|---|---|
| IDE-01 (run with visualizer) | Partial | high | none | `RunWithVisualizerAction.kt:15-34` | Scaffolding (start receiver, reset session, show tool window) works; core javaagent/classpath instrumentation is an explicit TODO stub. User code never instrumented. |
| IDE-02 (embedded tool window) | Partial | high | none | `plugin.xml:31-35`; `CoroutineVisualizerToolWindowFactory.kt:23-36`; `PluginEventReceiver.kt:50-142` | Working Swing tool window (Tree/Timeline/Event Log) + receiver on :8090. Required JCEF-embedded React UI and :8080 backend auto-detection absent. |
| IDE-03 (plugin tests) | Missing | high | none | `intellij-plugin/src` (no test/); `build.gradle.kts:42-44,74-76` | Test framework configured; zero test files. |

### Frontend Testing

| Requirement | Verdict | Conf | Test coverage | Evidence (file refs) | Notes |
|---|---|---|---|---|---|
| FETEST-01 (unit/coverage) | Partial | high | hooks tested; gaps below | `use-{actor-events,select-events,anti-patterns}.test.ts`; `vitest.config.ts:12-15`; `ci-frontend.yml:40`; ADR-022:26-30,138 | Hook tests substantive. Missing: component tests for actor/select/anti-pattern; coverage 41.33% vs 80% target; no CI threshold gate. |
| FETEST-02 (Playwright E2E) | Missing | high | none | no `frontend/e2e/`, no `playwright.config.ts`, no `@playwright/test`; ADR-022:49-92 | Entirely absent. |
| FETEST-03 (Storybook/Chromatic) | Missing | high | none | no `*.stories.*`, no `.storybook/`, no storybook/chromatic deps; ADR-022:94-131 | Entirely absent. |

### Observability

| Requirement | Verdict | Conf | Test coverage | Evidence (file refs) | Notes |
|---|---|---|---|---|---|
| OTEL-01 (OTLP exporter) | Missing | high | none | no OTel deps in either `build.gradle.kts`; grep opentelemetry/OTLP = 0; TODO §3.2 unstarted | No exporter, span mapping, or batch processor. Unstarted. |
| OTEL-02 (Jaeger/Zipkin spans) | Missing | high | none | `Monitoring.kt:1-25` (Micrometer only); `VizEvent.kt` lacks span/trace fields | Blocked on OTEL-01; no span correlation or parent-child mapping. |

---

## Confirmed audit findings

All five prior findings **held** under independent re-verification:

1. **Session fork (FND-01) — CONFIRMED, Broken.** The running backend uses 10 duplicate `com.jh.proj.coroutineviz.session` classes from `backend/src/main`, which shadow the 16 authoritative classes in `coroutine-viz-core` (identical packages in a Gradle multi-project build). `SessionRoutes.kt:3` imports the local fork. The fork is active at runtime.

2. **Unbounded store (FND-02) — CONFIRMED, Broken.** The shadowing fork instantiates `EventStore()` backed by an unbounded `CopyOnWriteArrayList`; `SessionManager.configure()` is never called. The bounded `ArrayDeque` implementation exists only in the shadowed core library. Events grow without bound.

3. **Unwired performance (PERF-01) — CONFIRMED, Partial.** `EventSampler` (109 lines, 392-line test) is never instantiated or called. `VizSession.send()` and the SSE route emit every event with no sampling and no X-Sampled header. The sampler is dead code. (Related: PERF-02 batching also absent.)

4. **Open auth (AUTH-01) — CONFIRMED, Broken.** `authenticatedApi()` is defined in `Auth.kt:56-60` but never invoked by production `Routing.kt`. All `/api/*` routes are open regardless of `API_KEY`. Passing AuthTests use a custom test module that manually wraps routes, not the real `module()`.

5. **Plugin TODO stub (IDE-01) — CONFIRMED, Partial.** `RunWithVisualizerAction.actionPerformed()` wires receiver/session/tool-window correctly, but `RunWithVisualizerAction.kt:32-33` is an explicit TODO for the javaagent/classpath instrumentation — the core capability. User code is never instrumented.

---

## Recommended next

Tackle the **Foundation area first (FND-01 → FND-02 → FND-03)** before any feature work. Reasoning:

- **FND-01 is the root cause** of a cascade of false-positive states. The active source fork in `backend/src/main` means the running server silently ignores the well-implemented, well-tested `coroutine-viz-core` library — including the bounded store (FND-02), retention policy (PERS-03), share-token service (SHAR-01), event sampler (PERF-01), and the configurable `SessionManager`. De-forking (delete the `backend/src/main` session duplicates, depend solely on `coroutine-viz-core`) will likely flip several Partial/Broken verdicts toward Works with comparatively little new code, because the correct implementations already exist and are tested.
- Once the fork is removed, **FND-02 (bounded store) and PERS-03 (retention)** become a wiring exercise: call `SessionManager.configure(...)` and `RetentionPolicy.start()` from `Application.kt`, add the config keys to `application.yaml`, and add **FND-03**'s missing runtime regression test asserting `store.all().size <= maxEvents`.
- **Immediately after Foundation, prioritize AUTH-01** (Broken, security-critical): wrap non-public route groups in the already-defined `authenticatedApi()` and add the AUTH-05 E2E test that runs the real `module()` with `API_KEY` set. This is a small, high-value change closing an open-door vulnerability.

A productive sequencing: **(1) De-fork — FND-01; (2) Wire bounded store + retention + regression test — FND-02/FND-03/PERS-03; (3) Close open auth — AUTH-01/AUTH-05.** Defer the genuinely-greenfield areas (Persistence JDBC, OTEL, Playwright/Storybook, WebM export, JWT/RBAC) until the existing-but-unwired assets are actually delivered to users.

---

## Runtime Walkthrough Addendum

**Date:** 2026-06-11 · **Method:** live browser-driven walkthrough of the running React UI (`localhost:3000`) against the running backend (`localhost:8080`), via the Claude-for-Chrome MCP tools, cross-checked with direct REST/SSE calls and backend log inspection (`/tmp/vizcore-backend.log`). This is the runtime-evidence pass deferred from the static audit above. Servers: backend `BUILD`/`run` healthy at `/health`; frontend Vite dev server. Scenario exercised end-to-end: **Nested Coroutines** (create session → run → reload from persistence).

The static audit's guiding rule — *tested-in-isolation but never-wired code is not a working feature* — held. The walkthrough **confirmed** the dead-code verdicts AND surfaced **two HIGH-severity runtime defects that static review and the 221/221 frontend + green backend test suites did not catch**, because those suites exercise mocked or snapshot data rather than live polymorphic HTTP/SSE serialization and the real backend↔frontend response contract.

### New runtime findings (not in the matrix above)

| ID | Severity | Title | Verdict |
|---|---|---|---|
| RT-01 | HIGH | `VizEvent` polymorphic serialization is unregistered → SSE stream and `/events` both fail | Broken |
| RT-02 | HIGH | Validation feature crashes the whole page (backend↔frontend contract drift) | Broken |
| RT-03 | MED | Events tab is always empty (downstream of RT-01) | Broken |
| RT-04 | LOW | `/gallery` works but is unlinked in the top nav (orphaned route) | Partial |

**RT-01 — `VizEvent` polymorphic serialization is broken (HIGH, Broken).**
Backend log throws on every raw-event serialization:
`kotlinx.serialization.SerializationException: Serializer for subclass 'CoroutineCreated' is not found in the polymorphic scope of 'VizEvent'.`
- **Root cause:** `events/VizEvent.kt:9` declares `interface VizEvent` **non-sealed** (comment: *"Not sealed to allow extension from subpackages"*). Subclasses (`events/coroutine/CoroutineCreated.kt`, `@Serializable @SerialName("CoroutineCreated")`) are serializable individually, but no `SerializersModule` registers them in a polymorphic scope — `grep -r "polymorphic(\|SerializersModule\|subclass(" src/main` finds none. kotlinx.serialization therefore cannot resolve the serializer at runtime.
- **Blast radius:** (1) SSE `/api/sessions/{id}/stream` errors and ends prematurely — log line `SessionRoutesKt$registerSessionRoutes$9:222 "Error in SSE stream" → :224 "SSE stream ended"` on the first emitted event; (2) `GET /api/sessions/{id}/events` returns **HTTP 500** (verified by curl: `HTTP 500 bytes=0`), repeatedly logged at `DefaultEnginePipelineKt:120`.
- **Why the visualizations still render:** the tree/graph/thread-lanes and the header counters are driven by the **projected snapshot** (`GET /api/sessions/{id}` → `RuntimeSnapshot`/projection), which serializes plain DTOs, not the polymorphic `VizEvent` list. The raw event stream is the only consumer that hits the broken path. The green "Live Stream Active" indicator is therefore **misleading** — the underlying SSE connection has already errored out.
- **Fix:** register all `VizEvent` subclasses in a `SerializersModule { polymorphic(VizEvent::class) { subclass(CoroutineCreated::class); … } }` installed on the Ktor `Json`/ContentNegotiation config (or seal the hierarchy). Add a runtime test that serializes a populated event list over the real `/events` route.

**RT-02 — Validation feature crashes the entire page (HIGH, Broken).**
Clicking **Run Validation** on a session replaces the whole app with the error-boundary screen *"Error — Cannot read properties of undefined (reading 'length')"*.
- **Root cause:** backend `POST /api/validate/session/{id}` returns `{ sessionId, results: [{ type:'Pass'|…, ruleName, message }], timing }` (verified by curl — top-level keys `['sessionId','results','timing']`, 21 results, **no** `errors`/`warnings`/`valid`). The frontend `components/validation/ValidationPanel.tsx:84-85` reads `data.errors.length` and `data.warnings.length`, both `undefined` → `TypeError` caught by `CatchBoundaryImpl` (console stack: `ValidationPanel … react-dom_client`). The `ValidationResult` type in `frontend/src/types/api.ts` is out of sync with the actual backend response shape.
- **Note:** the backend validation engine itself works (log: *"Running validation on session … with 28 events"*, returns 21 `Pass` rule results). This is purely a response-contract mismatch in the frontend adapter.
- **Fix:** reconcile the `ValidationResult` type and `ValidationPanel` with the real `{results, timing}` shape (derive errors/warnings by filtering `results` on `type`), or change the backend to emit the `{valid, errors, warnings}` shape the UI expects. Regenerate `shared/api-types` from the OpenAPI spec and verify the validate endpoint's documented schema matches the served JSON.

**RT-03 — Events tab always empty (MED, Broken — downstream of RT-01).**
The "Events" tab renders *"No events yet"* both during a live run (header showed **20 events**, Threads tab populated) and after reload (header **28 events**). `SessionDetails.tsx:45` feeds `EventsList` from `allEvents = streamEnabled ? liveEvents : storedEvents || []`; `storedEvents` comes from `useSessionEvents` → the 500-ing `/events` endpoint, and `liveEvents` comes from the SSE stream that RT-01 kills. So both inputs are empty. Fixing RT-01 should resolve this; add an integration check that the Events tab count matches the header `eventCount`.

### Static verdicts confirmed at runtime

- **Hierarchy visualization — works at runtime.** Graph View renders the parent→child-1/child-2→child-1-1 tree with live state colors, IDs, jobs, scopes, parent refs; List View renders the same hierarchy with Job-Status badges. Driven by the projected snapshot (see RT-01).
- **Thread lanes — works at runtime.** Threads tab shows "Thread Activity / 4 Threads" with per-worker `ASSIGNED` events and timestamps.
- **Session lifecycle + persistence — works at runtime.** Create-session form, scenario "Prepare → Run", session-list listing, and reload-rebuilds-from-snapshot (4 coroutines / 28 events survived a full navigation away and back) all function.
- **Scenario catalog + builder + gallery render.** 3 real-world (Order Processing, User Registration, Report Generation) + 9 basic patterns; `/scenarios/builder` (hierarchy editor, Execute, **Export JSON**, Quick Guide) and `/gallery` (category tabs Basic/Patterns/Flow/Sync/Channel, difficulty badges) all render.
- **RPLY (Replay) — Missing, confirmed.** No playback/scrubber controls anywhere in the session UI; `ReplayController` has zero non-test importers.
- **EXPT (Export) — Partial, confirmed.** No session-level Export button in the UI; `ExportButton` has zero non-test importers. (A *separate* "Export JSON" exists inside the Scenario Builder — that is the builder's own control, not the unwired `ExportButton`.)
- **CMPR (Session Comparison) — absent at runtime, confirmed.** No comparison route exists (`routes/` = gallery, scenarios, sessions, index only); `ComparisonView` has zero non-test importers. Backend comparison may work, but it is unreachable from the UI.

### Runtime-adjusted takeaways

1. **RT-01 is now the highest-priority defect alongside FND-01.** The product's headline promise — *"real-time SSE event streaming"* — is broken end-to-end at the serialization layer, even though the snapshot-driven visualizations create the appearance of a working live stream. De-forking (FND-01) will not fix it; the polymorphic `SerializersModule` registration is a distinct, mandatory change.
2. **Trust the snapshot, not the stream, when reasoning about why the UI "works."** Every visualization that renders does so via `RuntimeSnapshot`, masking the broken raw-event path.
3. **The validate and events endpoints need contract tests against the *served* JSON**, not just unit tests with hand-built fixtures — both RT-01 and RT-02 are contract/serialization failures invisible to the existing green suites.

**Revised runtime sequencing:** **(0) Fix RT-01 (`VizEvent` polymorphic registration) — unblocks SSE + `/events` + the Events tab (RT-03); (0b) Fix RT-02 (validation contract) — stops a full-page crash;** then proceed with the static plan: **(1) De-fork FND-01 → (2) bounded store FND-02/03 + PERS-03 → (3) close auth AUTH-01/05.** RT-01/RT-02 are small, self-contained, and user-visible, so they belong before the larger Foundation refactor.

> **See also:** `.planning/SCENARIO-AUDIT.md` (2026-06-11) — logic + pedagogy audit of all 12 built-in scenarios. Headline findings: **SC-01** the FAILED coroutine state is unreachable (`VizScope.kt:182` label-in-message heuristic) so every failure demo renders CANCELLED, contradicting the on-screen lesson; the **Cancellation scenario's** targeted cancel is commented out (kills the whole tree instead); **User Registration's** "notifications don't fail registration" intent is disproven at runtime (needs `supervisorScope`); **Report Generation's** "timeout handling" is fake (no `withTimeout`); failure params (`?fail=true` etc.) are unreachable from the UI; scenario routes report `success:true` on failed runs.
