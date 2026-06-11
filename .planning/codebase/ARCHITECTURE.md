<!-- refreshed: 2026-06-11 -->
# Architecture

**Analysis Date:** 2026-06-11

## System Overview

```text
┌──────────────────────────────────────────────────────────────────────────┐
│                     Layer 1: Instrumentation Wrappers                    │
│  VizScope · InstrumentedFlow · VizMutex · VizSemaphore                  │
│  InstrumentedChannel · InstrumentedDeferred · VizDispatchers             │
│  `backend/src/main/kotlin/.../wrappers/`                                 │
└────────────────────────────────┬─────────────────────────────────────────┘
                                  │ emits VizEvent instances
                                  ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                     Layer 2: Event System (32+ types)                    │
│  VizEvent interface · CoroutineEvent interface                           │
│  packages: coroutine / job / flow / dispatcher / deferred / channel      │
│  `backend/coroutine-viz-core/src/main/kotlin/.../events/`                │
└────────────────────────────────┬─────────────────────────────────────────┘
                                  │ send() → store + applier + bus
                                  ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                     Layer 3: Session Management                          │
│  VizSession (central container)                                          │
│  ├── EventStore (append-only log, EventStoreInterface)                   │
│  ├── EventBus (MutableSharedFlow, real-time pub/sub)                     │
│  ├── RuntimeSnapshot (current coroutine states)                          │
│  ├── ProjectionService (CQRS read model: hierarchy, thread timelines)    │
│  └── JobStatusMonitor                                                    │
│  `backend/*/session/VizSession.kt`                                       │
│  SessionManager implements SessionStoreInterface (ConcurrentHashMap)     │
└────────────────────────────────┬─────────────────────────────────────────┘
                                  │ SSE stream (replay stored + live)
                                  ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                     Ktor HTTP + SSE Routes                               │
│  `backend/src/main/kotlin/.../routes/SessionRoutes.kt`                   │
│  GET /api/sessions/{id}/stream  (SSE endpoint)                           │
│  REST: sessions, hierarchy, threads, coroutine timelines                 │
└────────────────────────────────┬─────────────────────────────────────────┘
                                  │ EventSource (browser SSE)
                                  ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                     Frontend: React 19 + TanStack                        │
│  useEventStream hook → accumulates VizEvent[]                            │
│  Domain hooks: use-hierarchy, use-flow-events, use-sync-events, ...      │
│  Visualizations: CoroutineTree · CoroutineTreeGraph · ThreadLanesView    │
│                  CoroutineTimelineView · FlowPanel · ChannelPanel        │
│  `frontend/src/`                                                         │
└──────────────────────────────────────────────────────────────────────────┘
```

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| `VizScope` | Instrumented CoroutineScope — wraps `launch`/`async`/flow/channel builders, emits lifecycle events | `backend/src/main/kotlin/.../wrappers/VizScope.kt` |
| `InstrumentedFlow` | Wraps any `Flow<T>`, emits FlowCreated/Collected/ValueEmitted events | `backend/src/main/kotlin/.../wrappers/InstrumentedFlow.kt` |
| `VizMutex` | Wraps `Mutex`, emits lock/unlock/contention events | `backend/src/main/kotlin/.../wrappers/VizMutex.kt` |
| `VizSemaphore` | Wraps `Semaphore`, emits acquire/release events | `backend/src/main/kotlin/.../wrappers/VizSemaphore.kt` |
| `InstrumentedChannel` | Wraps `Channel<T>`, emits send/receive/buffer state events | `backend/src/main/kotlin/.../wrappers/InstrumentedChannel.kt` |
| `VizEvent` | Base interface for all events (`sessionId`, `seq`, `tsNanos`, `kind`) | `backend/coroutine-viz-core/src/main/kotlin/.../events/VizEvent.kt` |
| `CoroutineEvent` | Sub-interface adding `coroutineId`, `jobId`, `parentCoroutineId`, `scopeId` | `backend/coroutine-viz-core/src/main/kotlin/.../events/VizEvent.kt` |
| `VizSession` | Central container per session — orchestrates store/bus/snapshot/projections | `backend/src/main/kotlin/.../session/VizSession.kt` |
| `EventStore` | Append-only, in-memory event log (`ArrayDeque`), bounded capacity | `backend/*/session/EventStore.kt` |
| `EventBus` | `MutableSharedFlow` with 10,000-event buffer; drops oldest on overflow | `backend/*/session/EventBus.kt` |
| `RuntimeSnapshot` | Mutable current-state map of coroutines keyed by coroutineId | `backend/*/models/RuntimeSnapshot.kt` |
| `ProjectionService` | CQRS read model: hierarchy tree, thread timelines, per-coroutine timeline | `backend/*/session/ProjectionService.kt` |
| `SessionManager` | `SessionStoreInterface` impl; `ConcurrentHashMap` of active sessions | `backend/src/main/kotlin/.../session/SessionManager.kt` |
| `SessionRoutes` | Ktor route handlers including SSE `/api/sessions/{id}/stream` | `backend/src/main/kotlin/.../routes/SessionRoutes.kt` |
| `useEventStream` | Frontend SSE hook — opens `EventSource`, accumulates events, normalizes format | `frontend/src/hooks/use-event-stream.ts` |

## Pattern Overview

**Overall:** Event Sourcing + CQRS

**Key Characteristics:**
- The `EventStore` is the system of record; every state question can be answered by replaying it
- `RuntimeSnapshot` and `ProjectionService` are write-through read models — they subscribe to `EventBus` and stay up to date
- The SSE endpoint first replays all stored events then switches to the live `EventBus` stream, so late-connecting frontend clients see the full history
- Instrumentation wrappers are opt-in — user code replaces `launch` with `scope.vizLaunch`, `Flow` with `scope.vizFlow`, etc.
- The backend is split into two Gradle modules: `coroutine-viz-core` (portable, no Ktor) and `backend/src` (Ktor server layer)

## Layers

**Layer 1 — Instrumentation Wrappers:**
- Purpose: Intercept coroutine/flow/channel operations and emit `VizEvent` instances into a `VizSession`
- Location: `backend/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/`
- Contains: `VizScope`, `InstrumentedFlow`, `InstrumentedChannel`, `VizMutex`, `VizSemaphore`, `VizDispatchers`, `InstrumentedDeferred`, `InstrumentedSharedFlow`, `InstrumentedStateFlow`, `VizActor`, `VizSelect`
- Depends on: `VizSession` (to call `session.send(event)`)
- Used by: User application code, scenario runners

**Layer 2 — Event System:**
- Purpose: Define the 32+ domain event types as typed Kotlin data classes
- Location: `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/`
- Contains: Sub-packages `coroutine/`, `job/`, `flow/`, `dispatcher/`, `deferred/`, `channel/`, plus `MutexEvents.kt`, `SemaphoreEvents.kt`, `SuspensionPoint.kt`, `ActorEvents.kt`, `SelectEvents.kt`, `DeadlockEvents.kt`, `AntiPatternEvents.kt`
- Depends on: `VizEvent` / `CoroutineEvent` interfaces only
- Used by: Wrappers (emit), SessionManagement (consume), routes (serialize to SSE)

**Layer 3 — Session Management:**
- Purpose: Store, distribute, and project events within a session lifetime
- Location: `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/` and `backend/src/main/kotlin/.../session/`
- Contains: `VizSession`, `EventStore`, `EventBus`, `ProjectionService`, `SessionManager`, `EventApplier`, `EventContext`, `FlowEventContext`, `ChannelEventContext`, `JobStatusMonitor`, `EventSampler`, `RetentionPolicy`, `ComparisonService`, `ShareTokenService`
- Depends on: Event system
- Used by: HTTP routes, wrappers (via `VizSession.send()`)

**Ktor HTTP Layer:**
- Purpose: Expose REST + SSE API consumed by the frontend
- Location: `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/`
- Contains: `SessionRoutes`, `ValidationRoutes`, `ComparisonRoutes`, `ScenarioRunnerRoutes`, `FlowScenarioRoutes`, `SyncScenarioRoutes`, `VizScenarioRoutes`, `PatternRoutes`, `HealthRoutes`, `RootRoutes`
- Depends on: Session management, validation check system
- Used by: Frontend via `apiClient` and `useEventStream`

**Frontend:**
- Purpose: Consume the event stream and render interactive visualizations
- Location: `frontend/src/`
- Contains: Route pages, domain hooks, visualization components, `api-client.ts`
- Depends on: Backend SSE/REST API
- Used by: End users via browser

## Data Flow

### Primary Request Path: Instrumentation → SSE → Frontend Render

1. User wraps coroutines with `VizScope.vizLaunch()` (`backend/src/main/kotlin/.../wrappers/VizScope.kt:75`)
2. On coroutine start, wrapper calls `session.send(ctx.coroutineCreated())` which synchronously: appends to `EventStore`, applies to `RuntimeSnapshot` via `EventApplier`, broadcasts on `EventBus` (`VizSession.kt:64-68`)
3. Frontend connects to `GET /api/sessions/{id}/stream` which first replays all stored events then collects from `EventBus` live (`SessionRoutes.kt:170-226`)
4. Each SSE message is typed by `event.kind` (e.g. `"CoroutineCreated"`)
5. `useEventStream` hook (`frontend/src/hooks/use-event-stream.ts`) receives events via `EventSource`, calls `normalizeEvent()` (PascalCase `type` → camelCase `kind`), accumulates into `events` state
6. Domain hooks (`use-hierarchy.ts`, `use-flow-events.ts`, `use-sync-events.ts`, etc.) derive structured data from accumulated events
7. Visualization components render: `CoroutineTree.tsx`, `CoroutineTreeGraph.tsx`, `ThreadLanesView.tsx`, `CoroutineTimelineView.tsx`, `FlowPanel.tsx`, `ChannelPanel.tsx`

### Session Lifecycle Flow

1. POST `/api/sessions` → `SessionManager.createSession()` → returns `sessionId`
2. POST `/api/scenarios/{id}?sessionId=...` → scenario runner calls `vizLaunch`/`vizFlow`/etc. on a `VizScope` bound to that session
3. GET `/api/sessions/{id}/hierarchy` → `session.projectionService.getHierarchyTree()`
4. GET `/api/sessions/{id}/threads` → `session.projectionService.getThreadActivity()`
5. DELETE `/api/sessions/{id}` → `SessionManager.closeSession()` → `VizSession.close()` cancels `sessionScope`

### Check System Flow

1. After events are stored, check system validators (`HierarchyValidator`, `LifecycleValidator`, `StructuredConcurrencyValidator`, `SyncValidators`) analyze the event log
2. POST `/api/validate/session/{id}` → `ValidationRoutes` → validators produce `ValidationResult`
3. Frontend `ValidationDashboard` component displays findings

**State Management (Frontend):**
- `useEventStream` holds the raw `VizEvent[]` array in React state
- Domain hooks filter/transform events on each render — no global store (no Redux/Zustand)
- TanStack Query caches REST responses (sessions list, hierarchy, threads)
- TanStack Router provides file-system routing with type-safe params

## Key Abstractions

**`VizEvent` interface:**
- Purpose: Common contract for every event in the system — `sessionId`, `seq`, `tsNanos`, `kind`
- Examples: `backend/coroutine-viz-core/src/main/kotlin/.../events/VizEvent.kt`
- Pattern: Interface (not sealed) to allow extension across sub-packages

**`CoroutineEvent` interface:**
- Purpose: Extends `VizEvent` for coroutine-scoped events adding `coroutineId`, `jobId`, `parentCoroutineId`, `scopeId`, `label`
- Examples: `backend/coroutine-viz-core/src/main/kotlin/.../events/VizEvent.kt`

**`EventStoreInterface`:**
- Purpose: Append-only log contract; `record()`, `all()`, `since(seq)`, `byCoroutine(id)`, `count()`, `clear()`
- Implementation: `EventStore` (in-memory `ArrayDeque`)
- File: `backend/coroutine-viz-core/src/main/kotlin/.../session/EventStoreInterface.kt`

**`SessionStoreInterface`:**
- Purpose: Session lifecycle contract; `createSession()`, `getSession()`, `listSessions()`, `deleteSession()`, `clearAll()`
- Implementation: `SessionManager` (ConcurrentHashMap)
- File: `backend/coroutine-viz-core/src/main/kotlin/.../session/SessionStoreInterface.kt`

**`ProjectionService`:**
- Purpose: CQRS read model — subscribes to `EventBus`, maintains `ConcurrentHashMap<String, HierarchyNode>` and thread activity, exposes `getHierarchyTree()`, `getThreadActivity()`, `getCoroutineTimeline()`
- File: `backend/coroutine-viz-core/src/main/kotlin/.../session/ProjectionService.kt`

**`VizSession`:**
- Purpose: Central container tying store/bus/snapshot/projections together; `send(event)` is the single write path
- File: `backend/src/main/kotlin/.../session/VizSession.kt`

**`EventContext` / `FlowEventContext` / `ChannelEventContext`:**
- Purpose: Factory helpers that construct typed event instances with the current coroutine/flow/channel's identity fields pre-filled
- Files: `backend/*/session/EventContext.kt`, `FlowEventContext.kt`, `ChannelEventContext.kt`

## Entry Points

**Backend main:**
- Location: `backend/src/main/kotlin/com/jh/proj/coroutineviz/Application.kt`
- Triggers: `io.ktor.server.netty.EngineMain.main()`
- Responsibilities: Wires Ktor plugins — `configureCompression()`, `configureHTTP()`, `configureAuth()`, `configureMonitoring()`, `configureSerialization()`, `configureRouting()`

**Backend routing:**
- Location: `backend/src/main/kotlin/com/jh/proj/coroutineviz/Routing.kt`
- Responsibilities: Installs SSE plugin, registers all 10 route groups

**Frontend main:**
- Location: `frontend/src/main.tsx`
- Triggers: Browser entry, mounts React tree
- Responsibilities: Provides `QueryClientProvider`, `HeroUIProvider`, `RouterProvider` with `routeTree.gen.ts`

**Frontend router root:**
- Location: `frontend/src/routes/__root.tsx`
- Responsibilities: `createRootRouteWithContext<{queryClient}>()` — makes TanStack Query available in all route loaders

**Frontend session page:**
- Location: `frontend/src/routes/sessions/$sessionId.tsx`
- Responsibilities: Primary visualization page — mounts `useEventStream`, renders all visualization panels for a session

## Architectural Constraints

- **Threading:** Ktor runs on Netty (multi-threaded), but `VizSession.send()` is non-suspending and uses `AtomicLong` for sequence generation. `EventStore` and `RuntimeSnapshot` use `ConcurrentHashMap` / thread-safe collections.
- **Global state:** `SessionManager` is a Kotlin `object` (singleton) — single shared instance for all HTTP requests. Tests that use `SessionManager` must call `clearAll()` in teardown.
- **EventBus overflow:** `EventBus` uses `DROP_OLDEST` on buffer overflow (10,000 capacity). High-frequency scenarios can silently drop events.
- **Dual module:** `coroutine-viz-core` is a pure Kotlin library (no Ktor). `backend/src` is the Ktor application. Both duplicate some source trees (`events/`, `checksystem/`, `models/`, `session/`) — the core module is authoritative.
- **SSE reconnection:** The SSE handler replays `store.all()` then filters live bus events with `seq > lastReplayedSeq`. This prevents duplicates on reconnect.
- **Frontend event normalization:** Backend sends `type` field (PascalCase); frontend expects `kind` field. `normalizeEvent()` in `frontend/src/lib/utils.ts` performs the mapping.

## Anti-Patterns

### Bypassing `VizSession.send()` for event emission

**What happens:** Calling `session.store.append(event)` or `session.eventBus.send(event)` directly instead of `session.send(event)`.
**Why it's wrong:** `session.send()` atomically appends to store, applies to snapshot, and broadcasts on bus. Bypassing it leaves snapshot or bus out of sync.
**Do this instead:** Always call `session.send(event)` or `session.sendAsync(event)` from wrappers. See `VizSession.kt:64`.

### Using `GlobalScope` in instrumented coroutines

**What happens:** Launching a child coroutine with `GlobalScope.launch` inside a `vizLaunch` block.
**Why it's wrong:** Breaks structured concurrency — the child escapes the `VizScope`, and no lifecycle events are emitted for it.
**Do this instead:** Use `scope.vizLaunch { }` or `currentCoroutineContext()` based scoping as in `VizScope.kt:109-113`.

### Reading event history from `RuntimeSnapshot` instead of `EventStore`

**What happens:** Querying `session.snapshot.coroutines` for event replay or export.
**Why it's wrong:** `RuntimeSnapshot` is a derived, lossy state view — it does not preserve event history or ordering.
**Do this instead:** Use `session.store.all()` or `session.store.since(seq)` for event history. Use `session.projectionService` for hierarchy/timeline queries.

## Error Handling

**Strategy:** Ktor global exception handler returns JSON error responses; coroutine failures emit `CoroutineFailed` events; SSE errors send an `{"error":"..."}` SSE event before closing.

**Patterns:**
- Wrapper `invokeOnCompletion` handlers distinguish `null` (normal), `CancellationException` (cancelled), and other `Throwable` (failed) to emit correct terminal events
- SSE endpoint wraps `session.bus.stream().collect { }` in try/catch to handle subscriber disconnection gracefully
- Check system validators return `ValidationResult` (not exceptions) — results are displayed in the UI

## Cross-Cutting Concerns

**Logging:** SLF4J via `LoggerFactory.getLogger(...)` in all backend classes. Frontend uses no explicit logging framework — errors are silent-caught in `useEventStream`.
**Validation:** `checksystem/` package — `HierarchyValidator`, `LifecycleValidator`, `StructuredConcurrencyValidator`, `SyncValidators`, `AntiPatternDetector` operate post-hoc on the event log.
**Authentication:** `Auth.kt` configures Ktor auth plugin; routes are currently split into public (`/health`, `/`) and API groups (no auth enforcement on most routes as of analysis date).

---

*Architecture analysis: 2026-06-11*
