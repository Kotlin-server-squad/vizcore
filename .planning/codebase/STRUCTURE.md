# Codebase Structure

**Analysis Date:** 2026-06-11

## Directory Layout

```
vizcore/                              # Monorepo root
├── backend/                          # Kotlin 2.2 + Ktor 3.3 server
│   ├── coroutine-viz-core/           # Pure Kotlin library module (no Ktor)
│   │   └── src/
│   │       ├── main/kotlin/com/jh/proj/coroutineviz/
│   │       │   ├── events/           # 32+ VizEvent types
│   │       │   │   ├── coroutine/    # CoroutineCreated, Started, Suspended, Resumed, Completed, Cancelled, Failed, BodyCompleted
│   │       │   │   ├── job/          # JobStateChanged, JobCancellationRequested, JobJoinRequested/Completed
│   │       │   │   ├── flow/         # FlowCreated, CollectionStarted/Completed/Cancelled, ValueEmitted/Filtered/Transformed, SharedFlow*, StateFlow*
│   │       │   │   ├── dispatcher/   # DispatcherSelected, ThreadAssigned
│   │       │   │   ├── deferred/     # DeferredAwaitStarted/Completed, DeferredValueAvailable
│   │       │   │   ├── channel/      # ChannelCreated, SendStarted/Completed/Suspended, ReceiveStarted/Completed/Suspended, BufferStateChanged, Closed
│   │       │   │   ├── VizEvent.kt   # Base interfaces (VizEvent, CoroutineEvent)
│   │       │   │   ├── MutexEvents.kt
│   │       │   │   ├── SemaphoreEvents.kt
│   │       │   │   ├── ActorEvents.kt
│   │       │   │   ├── SelectEvents.kt
│   │       │   │   ├── DeadlockEvents.kt
│   │       │   │   └── AntiPatternEvents.kt
│   │       │   ├── session/          # Core session management
│   │       │   │   ├── EventStoreInterface.kt
│   │       │   │   ├── EventStore.kt
│   │       │   │   ├── EventBus.kt
│   │       │   │   ├── ProjectionService.kt
│   │       │   │   ├── SessionStoreInterface.kt
│   │       │   │   ├── SessionManager.kt
│   │       │   │   ├── EventApplier.kt
│   │       │   │   ├── EventContext.kt
│   │       │   │   ├── FlowEventContext.kt
│   │       │   │   ├── ChannelEventContext.kt
│   │       │   │   ├── JobStatusMonitor.kt
│   │       │   │   ├── EventSampler.kt
│   │       │   │   ├── RetentionPolicy.kt
│   │       │   │   ├── ComparisonService.kt
│   │       │   │   └── ShareTokenService.kt
│   │       │   ├── models/           # Data models (HierarchyNode, RuntimeSnapshot, CoroutineTimeline, etc.)
│   │       │   ├── checksystem/      # Post-hoc validators and anti-pattern detectors
│   │       │   ├── sync/             # DeadlockDetector
│   │       │   ├── validation/       # Validation rules
│   │       │   └── wrappers/         # (also in backend/src — see below)
│   │       └── test/kotlin/...       # Mirrored test structure
│   └── src/                          # Ktor application module
│       └── main/kotlin/com/jh/proj/coroutineviz/
│           ├── Application.kt        # Server entry point
│           ├── Routing.kt            # Installs SSE, registers all route groups
│           ├── Auth.kt
│           ├── Compression.kt
│           ├── HTTP.kt
│           ├── Serialization.kt
│           ├── Monitoring.kt
│           ├── MetricsWiring.kt
│           ├── routes/               # Ktor route handlers
│           │   ├── SessionRoutes.kt  # Core: CRUD + SSE /api/sessions/{id}/stream
│           │   ├── ValidationRoutes.kt
│           │   ├── ComparisonRoutes.kt
│           │   ├── ScenarioRunnerRoutes.kt
│           │   ├── FlowScenarioRoutes.kt
│           │   ├── SyncScenarioRoutes.kt
│           │   ├── VizScenarioRoutes.kt
│           │   ├── PatternRoutes.kt
│           │   ├── HealthRoutes.kt
│           │   ├── RootRoutes.kt
│           │   └── RouteDtos.kt
│           ├── session/              # VizSession + copies of core session types
│           │   └── VizSession.kt
│           ├── wrappers/             # Instrumentation wrappers (also in core)
│           │   ├── VizScope.kt
│           │   ├── InstrumentedFlow.kt
│           │   ├── InstrumentedChannel.kt
│           │   ├── InstrumentedDeferred.kt
│           │   ├── InstrumentedSharedFlow.kt
│           │   ├── InstrumentedStateFlow.kt
│           │   ├── InstrumentedDispatcher.kt
│           │   ├── VizMutex.kt
│           │   ├── VizSemaphore.kt
│           │   ├── VizDispatchers.kt
│           │   ├── VizCoroutineElement.kt
│           │   ├── VizActor.kt
│           │   └── VizSelect.kt
│           ├── scenarios/            # Built-in scenario DSL and runners
│           │   ├── ScenarioDSL.kt
│           │   ├── ScenarioRunner.kt
│           │   ├── FlowScenarios.kt
│           │   ├── SyncScenarios.kt
│           │   └── PatternScenarios.kt
│           ├── events/               # (duplicate of core events for Ktor serialization annotations)
│           ├── checksystem/          # (duplicate of core checksystem)
│           ├── models/               # (duplicate of core models)
│           ├── sync/                 # DeadlockDetector
│           └── examples/
├── frontend/                         # React 19 + TypeScript + Vite
│   ├── src/
│   │   ├── main.tsx                  # React entry point (providers: Query, HeroUI, Router)
│   │   ├── index.css                 # Tailwind base styles
│   │   ├── routeTree.gen.ts          # Auto-generated by TanStack Router codegen
│   │   ├── routes/                   # File-system routes
│   │   │   ├── __root.tsx            # Root layout + QueryClient context
│   │   │   ├── index.tsx             # Home / sessions list
│   │   │   ├── sessions/
│   │   │   │   ├── index.tsx         # Sessions list page
│   │   │   │   └── $sessionId.tsx    # Session detail + all visualization panels
│   │   │   ├── scenarios/
│   │   │   │   ├── index.tsx
│   │   │   │   └── builder.tsx
│   │   │   └── gallery/
│   │   │       └── index.tsx
│   │   ├── hooks/                    # React hooks
│   │   │   ├── use-event-stream.ts   # SSE connection, event accumulation, normalization
│   │   │   ├── use-hierarchy.ts
│   │   │   ├── use-enhanced-hierarchy.ts
│   │   │   ├── use-flow-events.ts
│   │   │   ├── use-channel-events.ts
│   │   │   ├── use-job-events.ts
│   │   │   ├── use-sync-events.ts
│   │   │   ├── use-timeline.ts
│   │   │   ├── use-thread-activity.ts
│   │   │   ├── use-sessions.ts
│   │   │   ├── use-replay.ts
│   │   │   ├── use-replay-motion.ts
│   │   │   ├── use-validation.ts
│   │   │   ├── use-anti-patterns.ts
│   │   │   ├── use-comparison.ts
│   │   │   ├── use-scenarios.ts
│   │   │   ├── use-event-categories.ts
│   │   │   ├── use-event-retention.ts
│   │   │   ├── use-select-events.ts
│   │   │   ├── use-actor-events.ts
│   │   │   ├── use-animated-in-view.ts
│   │   │   └── use-keyboard-nav.ts
│   │   ├── components/               # UI components
│   │   │   ├── CoroutineTree.tsx
│   │   │   ├── CoroutineTreeGraph.tsx
│   │   │   ├── CoroutineTimelineView.tsx
│   │   │   ├── ThreadLanesView.tsx
│   │   │   ├── ThreadTimeline.tsx
│   │   │   ├── SessionDetails.tsx
│   │   │   ├── EventsList.tsx
│   │   │   ├── flow/                 # FlowPanel, SharedFlowPanel, StateFlowPanel, FlowOperatorChain, FlowParticlePath
│   │   │   ├── channels/             # ChannelPanel, ChannelBufferGauge, ChannelTimeline, ChannelProducerConsumer
│   │   │   ├── jobs/                 # JobPanel, JobHierarchyView, JobStateBadge
│   │   │   ├── sync/                 # SyncPanel, MutexStateIndicator, SemaphoreGauge, DeadlockVisualization
│   │   │   ├── actors/               # ActorCard, ActorMailbox, ActorPoolView
│   │   │   ├── anti-patterns/        # AntiPatternBadge, AntiPatternOverlay
│   │   │   ├── validation-dashboard/ # ValidationDashboard, FindingCard, SeverityFilter
│   │   │   ├── comparison/           # ComparisonView
│   │   │   ├── replay/               # ReplayController, EventHighlight
│   │   │   ├── patterns/             # FanOutFanInView, ProducerConsumerView, RetryVisualization
│   │   │   ├── scenarios/            # OrderProcessingView, RegistrationFlowView
│   │   │   ├── select/               # SelectClauseBar, SelectVisualization
│   │   │   ├── export/               # ExportButton
│   │   │   └── validation/           # TimingReportView
│   │   ├── lib/                      # Utilities and shared logic
│   │   │   ├── api-client.ts         # REST API client (singleton `apiClient`)
│   │   │   ├── query-client.ts       # TanStack Query client config
│   │   │   ├── utils.ts              # normalizeEvent(), normalizeEvents()
│   │   │   ├── animation-variants.ts
│   │   │   ├── animation-throttle.ts
│   │   │   ├── layout-transition.ts
│   │   │   ├── coroutine-state-colors.ts
│   │   │   ├── dispatcher-utils.tsx
│   │   │   ├── accessibility.ts
│   │   │   ├── high-contrast.ts
│   │   │   ├── export-png.ts
│   │   │   └── export-svg.ts
│   │   ├── types/
│   │   │   └── api.ts                # TypeScript types for all API payloads
│   │   └── mocks/                    # MSW mock handlers for development/test
│   │       ├── browser.ts
│   │       ├── handlers.ts
│   │       └── mock-data.ts
│   └── public/
├── shared/
│   └── api-types/                    # Generated TypeScript from backend OpenAPI spec
│       ├── generated.ts              # Auto-generated — do not edit manually
│       ├── events.ts                 # Event type definitions
│       ├── index.ts                  # Re-exports
│       └── openapi.json              # OpenAPI spec source
├── docs/
│   └── adr/                          # Architecture Decision Records (001–010)
├── .planning/                        # GSD planning documents
│   └── codebase/
├── .claude/
│   └── skills/                       # Project-specific AI skill files
├── docker-compose.yml
└── CLAUDE.md
```

## Directory Purposes

**`backend/coroutine-viz-core/`:**
- Purpose: Portable Kotlin library — the domain logic with zero Ktor dependency
- Contains: All event types, session interfaces, `EventStore`, `EventBus`, `ProjectionService`, check system, models
- Key files: `events/VizEvent.kt`, `session/EventStoreInterface.kt`, `session/SessionStoreInterface.kt`, `session/ProjectionService.kt`

**`backend/src/`:**
- Purpose: Ktor HTTP server application — wires the core library into HTTP endpoints
- Contains: `Application.kt`, plugin configs, all route handlers, `VizSession.kt`, wrappers, scenarios
- Key files: `Application.kt`, `Routing.kt`, `routes/SessionRoutes.kt`, `session/VizSession.kt`, `wrappers/VizScope.kt`

**`frontend/src/routes/`:**
- Purpose: TanStack Router file-system routes — each file is a route segment
- Key files: `__root.tsx` (root context), `sessions/$sessionId.tsx` (main visualization page)

**`frontend/src/hooks/`:**
- Purpose: All React hooks — both data-fetching (via TanStack Query) and SSE event processing
- Key files: `use-event-stream.ts` (SSE), `use-hierarchy.ts`, `use-flow-events.ts`

**`frontend/src/components/`:**
- Purpose: All React components organized by domain
- Top-level flat files are shared/generic; sub-directories are domain-specific (flow/, channels/, jobs/, sync/, etc.)

**`frontend/src/lib/`:**
- Purpose: Non-React utilities — API client, animation helpers, state color maps
- Key files: `api-client.ts` (all HTTP calls), `utils.ts` (event normalization)

**`shared/api-types/`:**
- Purpose: TypeScript type definitions generated from the backend OpenAPI spec — shared contract
- `generated.ts` is auto-generated; prefer importing from `index.ts`

**`docs/adr/`:**
- Purpose: Architecture Decision Records — decisions 001–010 covering monorepo, shared types, CI/CD, Docker, plugin architecture

## Key File Locations

**Entry Points:**
- `backend/src/main/kotlin/com/jh/proj/coroutineviz/Application.kt`: Kotlin server main
- `frontend/src/main.tsx`: React app entry

**Configuration:**
- `backend/src/main/kotlin/com/jh/proj/coroutineviz/Routing.kt`: All route registration
- `frontend/src/lib/query-client.ts`: TanStack Query configuration
- `frontend/src/routeTree.gen.ts`: Auto-generated router tree (never edit manually)

**Core Interfaces:**
- `backend/coroutine-viz-core/src/main/kotlin/.../events/VizEvent.kt`: `VizEvent` + `CoroutineEvent` interfaces
- `backend/coroutine-viz-core/src/main/kotlin/.../session/EventStoreInterface.kt`
- `backend/coroutine-viz-core/src/main/kotlin/.../session/SessionStoreInterface.kt`

**Primary Write Path:**
- `backend/src/main/kotlin/.../session/VizSession.kt`: `send(event)` method
- `backend/src/main/kotlin/.../wrappers/VizScope.kt`: `vizLaunch()`, `vizAsync()`, `vizFlow()`, `vizChannel()`

**SSE Endpoint:**
- `backend/src/main/kotlin/.../routes/SessionRoutes.kt`: `sse("/api/sessions/{id}/stream")` at line 170

**Frontend SSE Consumer:**
- `frontend/src/hooks/use-event-stream.ts`: `useEventStream(sessionId)`

**Type Contract:**
- `frontend/src/types/api.ts`: All TypeScript API types used in frontend
- `shared/api-types/generated.ts`: OpenAPI-generated types

**Testing:**
- Backend: `backend/coroutine-viz-core/src/test/kotlin/...` mirroring main structure
- Frontend: Co-located `*.test.tsx` files beside components (e.g., `CoroutineTree.test.tsx`, `FlowPanel.test.tsx`)

## Naming Conventions

**Files (Kotlin):**
- Wrappers: `Viz{Concept}.kt` or `Instrumented{Concept}.kt` (e.g., `VizScope.kt`, `InstrumentedFlow.kt`)
- Events: `{Concept}{EventName}.kt` using PascalCase (e.g., `CoroutineCreated.kt`, `FlowValueEmitted.kt`)
- Routes: `{Domain}Routes.kt` (e.g., `SessionRoutes.kt`, `ValidationRoutes.kt`)
- Session services: Descriptive noun (`EventStore.kt`, `ProjectionService.kt`, `JobStatusMonitor.kt`)

**Files (TypeScript/React):**
- Components: `PascalCase.tsx` (e.g., `CoroutineTree.tsx`, `FlowPanel.tsx`)
- Hooks: `use-kebab-case.ts` (e.g., `use-event-stream.ts`, `use-flow-events.ts`)
- Utilities: `kebab-case.ts` (e.g., `api-client.ts`, `animation-variants.ts`)
- Route files: TanStack Router convention — `$param.tsx` for dynamic segments, `__root.tsx` for layout routes, `index.tsx` for index routes

**Directories:**
- Backend packages: `com.jh.proj.coroutineviz.{layer}` (all lowercase)
- Frontend: domain-named lowercase (e.g., `flow/`, `channels/`, `sync/`)

## Where to Add New Code

**New event type:**
- Create `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/{domain}/{EventName}.kt` implementing `VizEvent` or `CoroutineEvent`
- Mirror in `backend/src/main/kotlin/.../events/{domain}/` if Ktor serialization annotations are needed
- Add corresponding TypeScript type in `frontend/src/types/api.ts`

**New instrumentation wrapper:**
- Implementation: `backend/src/main/kotlin/.../wrappers/Viz{Concept}.kt` or `Instrumented{Concept}.kt`
- Follow `VizScope` pattern: accept `VizSession`, call `session.send(event)` for each operation

**New API route:**
- Implementation: `backend/src/main/kotlin/.../routes/{Domain}Routes.kt`
- Register in `Routing.kt` via `register{Domain}Routes()`

**New visualization component:**
- Domain component: `frontend/src/components/{domain}/{ComponentName}.tsx`
- Generic shared component: `frontend/src/components/{ComponentName}.tsx`
- Co-locate tests: `frontend/src/components/{domain}/{ComponentName}.test.tsx`

**New domain hook:**
- File: `frontend/src/hooks/use-{domain-concept}.ts`
- Accept `VizEvent[]` from `useEventStream` as input, return derived state

**New route/page:**
- Add file under `frontend/src/routes/` following TanStack Router file-system convention
- `routeTree.gen.ts` is regenerated automatically by `pnpm dev` / `pnpm build`

**New scenario:**
- Add to `backend/src/main/kotlin/.../scenarios/{Domain}Scenarios.kt` using `ScenarioDSL`
- Register in `ScenarioRunner.kt` scenario registry

## Special Directories

**`.planning/`:**
- Purpose: GSD planning documents (phases, codebase maps, intel)
- Generated: No
- Committed: Yes

**`.claude/skills/`:**
- Purpose: Project-specific AI skill files for Claude Code
- Generated: No
- Committed: Yes

**`frontend/src/mocks/`:**
- Purpose: MSW (Mock Service Worker) handlers for development without the backend
- Generated: No
- Committed: Yes

**`shared/api-types/generated.ts`:**
- Purpose: TypeScript types auto-generated from `openapi.json`
- Generated: Yes (from OpenAPI spec)
- Committed: Yes (checked in for stability)

**`frontend/src/routeTree.gen.ts`:**
- Purpose: TanStack Router auto-generated route tree
- Generated: Yes (by `pnpm dev` / `pnpm build`)
- Committed: Yes

**`backend/coroutine-viz-core/`:**
- Purpose: Standalone Kotlin library module intended to eventually be published independently
- Generated: No
- Committed: Yes

---

*Structure analysis: 2026-06-11*
