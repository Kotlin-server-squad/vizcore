# vizcore — Visualizer for Coroutines

## What This Is

vizcore is a real-time tool that makes invisible Kotlin coroutine execution visible. Developers wrap their `kotlinx-coroutines-core` usage with non-invasive instrumentation (VizScope, InstrumentedFlow, VizMutex, VizSemaphore, etc.); the event-sourced Kotlin/Ktor backend streams the resulting events over SSE to a React frontend that renders interactive timeline, tree, graph, thread-lane, and event-log views. It serves learning developers, educators/content creators, enterprise training, and (advisory) IDE-tool partnership. It ships as a containerized web app (Ktor backend on :8080 + React frontend on :3000), a publishable SDK artifact (`coroutine-viz-core`, MIT-licensed per ADR-021), and an optional IntelliJ plugin.

**Build status: BROWNFIELD, ~92% implemented.** This document and ROADMAP.md describe the remaining ~8% of feature work plus the verified structural gaps that block production readiness — NOT a greenfield build.

## Core Value

A developer can SEE and UNDERSTAND coroutine, Flow, and structured-concurrency execution that is otherwise invisible — reducing time-to-understand of concurrent Kotlin behavior (per BUSINESS_ANALYSIS_V2). Captured behavior is real (instrumented from the live library), not simulated, and is event-sourced for replay/time-travel.

## Requirements

### Validated

<!-- Shipped and relied upon in the existing codebase (~92% complete). -->

- ✓ Core visualization engine — 48 event types, 9 instrumentation wrappers, SSE streaming, event-sourced session management (REQ-core-visualization, partial)
- ✓ Multi-view frontend — coroutine tree, graph, thread lanes, timeline, flow/channel/sync/job/actor/select panels, validation dashboard (60+ components, 18 hooks)
- ✓ Teaching scenarios — 20+ built-in scenarios + custom-scenario authoring endpoint (REQ-teaching-scenarios, partial)
- ✓ Validation/check engine — 20+ rules across 6 categories, deadlock + anti-pattern detection (ADR-012)
- ✓ Animation system — framer-motion variants, state colors, replay-aware motion, viewport throttling (ADR-011, 024-027)
- ✓ Core library extraction — `coroutine-viz-core` module (~11,045 LoC) separated from Ktor app (ADR-013)
- ✓ Monorepo + CI/CD + shared OpenAPI types + Docker (ADR-001, 002, 004, 005, 023)

### Active

<!-- Remaining scope. Building toward these. See ROADMAP.md for phase mapping. -->

- [ ] Production hardening — health endpoints, dev/prod logging profiles, config-driven CORS, OpenAPI polish, bounded event store wired into the running server, full Micrometer metrics (CON-production-readiness, ADR-020)
- [ ] Structural consolidation — remove the duplicated `com.jh.proj.coroutineviz.session.*` fork in `backend/src/main/` so the stronger `coroutine-viz-core` versions run (root cause of the bounded-store and perf gaps)
- [ ] Replay & time-travel — client-side replay controller, speed selector, step-through, scrubber (REQ-core-visualization replay; ADR-017)
- [ ] Export & sharing — PNG/SVG/WebM export; share tokens + read-only shared views (REQ-export-and-sharing; ADR-018, ADR-019)
- [ ] Persistence — optional JDBC/Exposed store (H2 dev / PostgreSQL prod), retention policy, sessions survive restart (ADR-015; unblocks sharing persistence)
- [ ] Authentication & multi-tenancy — wire `authenticatedApi()` onto routes, API-key hashing, JWT, tenant isolation (ADR-016)
- [ ] Performance & scaling — sampling, batching, SSE compression header, load-test harness (ADR-020)
- [ ] Session comparison — `ComparisonService` + compare endpoint + side-by-side UI (CON-session-comparison)
- [ ] SDK distribution — publish `coroutine-viz-core`, CLI tool, Gradle task wrapper (REQ-core-visualization SDK; ADR-021)
- [ ] IntelliJ plugin — complete `RunWithVisualizerAction` (javaagent launch), tests, tool window, Marketplace (REQ-ide-plugin; ADR-010 Proposed/advisory, ADR-014)
- [ ] Frontend testing & polish — actor/select/anti-pattern tests, Playwright E2E, Storybook, visual regression (ADR-022)
- [ ] Observability integration — OpenTelemetry/OTLP exporter, verify in Jaeger/Zipkin (CON-opentelemetry)

### Out of Scope

- Billing / payment processing — explicitly deferred; pricing model itself unresolved (see Key Decisions)
- WebSocket transport — superseded by SSE (ADR-002 / CLAUDE.md); deep-dive PRD references are background only
- Marketing site & go-to-market content — tracked separately; not engineering scope for this milestone

## Context

- **Event-sourced, three-layer backend:** instrumentation wrappers → 48 event types → session management (VizSession, EventBus over SharedFlow, EventStore, RuntimeSnapshot, ProjectionService). Frontend consumes via SSE; `normalizeEvent()` maps backend `type` (PascalCase) → frontend `kind` (camelCase).
- **Dual Gradle modules:** `coroutine-viz-core` (pure Kotlin, no Ktor, authoritative) and `backend/src` (Ktor server). The backend currently forks a copy of the `session/` package under the same FQN, shadowing the core versions at runtime — root cause of two verified gaps below.
- **Current deployment is ephemeral** (ADR-009): no database, in-memory stores only; sessions lost on restart. Persistence (ADR-015) is an Accepted-but-unimplemented optional seam.
- **Verified gaps from codebase audit (2026-06-11):**
  1. Duplicate `com.jh.proj.coroutineviz.session.*` fork in `backend/src/main/` shadows the stronger core versions (root cause of gaps 2 and 3).
  2. Persistence (ADR-015) unimplemented — runtime EventStore is an unbounded in-memory `CopyOnWriteArrayList`; no DB.
  3. Perf-scaling (ADR-020) not wired — bounded EventStore/EventSampler/RetentionPolicy exist in core but are unused by the backend; only 2 of 7 Micrometer gauges present.
  4. Auth (ADR-016) effectively off — `Auth.kt` + `authenticatedApi()` exist but `Routing.kt` registers all route groups without auth; every endpoint open.
  5. IntelliJ plugin (ADR-010) mostly built, but `RunWithVisualizerAction.actionPerformed` is a TODO stub (javaagent launch missing); zero plugin tests.
- **Competing variants (unresolved, do NOT block):** business model/pricing/license and success-metric/KPI sets differ between the two PRDs. BUSINESS_ANALYSIS_V2 is the working default; the alternative is preserved in REQUIREMENTS.md for later go-to-market resolution.

## Constraints

- **Tech stack**: Kotlin 2.2.20 / Ktor 3.3.2 (Netty) backend on JVM 21; React 19 / Vite 6 / TypeScript 5.7 frontend on Node 24 — locked by ADR-001/002/005/009 and CLAUDE.md.
- **Transport**: SSE only (ADR-002). No WebSocket.
- **Concurrency**: Structured concurrency, never `GlobalScope`; all event emission goes through `VizSession.send()` (never bypass to store/bus directly).
- **Instrumentation**: must non-invasively wrap official `kotlinx-coroutines-core` — capture real behavior, not simulations (REQ-core-visualization).
- **Quality gates**: ktlint 12.2.0 + detekt 1.23.7 (backend, ADR-023); ESLint 9 + Prettier + TS strict (frontend); CI path-filtered (ADR-004).
- **Persistence seam**: any persistence work must implement the `SessionStoreInterface`/`EventStoreInterface` seam in `coroutine-viz-core`; storage is config-selectable (`storage.type: memory | database`, default `memory`) per ADR-015.
- **SDK license**: published `coroutine-viz-core` artifact is MIT (ADR-021) — distinct from the disputed product free-tier license.

## Key Decisions

<!-- 26 Accepted ADRs are LOCKED. ADR-010 is Proposed/advisory. -->

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Monorepo + pnpm workspaces + Docker Compose (ADR-001) | Single repo for FE/BE/core/shared | ✓ Locked |
| Shared API contract via OpenAPI → generated TS types (ADR-002) | Backend spec is single source of truth | ✓ Locked |
| SSE as the real-time transport (ADR-002) | Simpler than WebSocket for one-way event push | ✓ Locked |
| Event-sourced + CQRS architecture | Enables replay/time-travel, derived projections | ✓ Locked |
| Core library extraction `coroutine-viz-core` (ADR-013) | Enables plugin + SDK reuse | ✓ Locked (but FE fork shadows it — see gaps) |
| Persistence as optional `*StoreInterface` seam (ADR-015) | Keep dev zero-config; DB optional | ✓ Locked, ⚠️ unimplemented |
| Current deployment is in-memory/ephemeral (ADR-009) | No DB until ADR-015 lands | ✓ Locked |
| Two-phase auth: API-key then JWT, RBAC (ADR-016) | Layered rollout | ✓ Locked, ⚠️ not wired to routes |
| Client-side replay engine (ADR-017) | No backend changes needed | ✓ Locked |
| Three-tier client-side export PNG/SVG/WebM (ADR-018) | Browser-native, no server render | ✓ Locked |
| Share-token model, requires DB (ADR-019) | Read-only shareable sessions | ✓ Locked, depends on ADR-015 |
| Perf scaling: bounded store, sampling, batching, metrics (ADR-020) | Prevent OOM under load | ✓ Locked, ⚠️ core-only, not wired |
| SDK published to GitHub Packages, MIT POM (ADR-021) | Library distribution | ✓ Locked |
| IntelliJ plugin: VizSession events + DebugProbes fallback (ADR-010) | Native IDE experience | — Proposed/advisory (NOT locked) |
| Business model / pricing / license | Two PRDs disagree | — Pending (V2 working default; not blocking) |
| Success metrics / KPI set | Two PRDs disagree | — Pending (V2 working default; not blocking) |

## Evolution

**After each phase transition:**
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone:**
1. Full review of all sections
2. Core Value check — still the right priority?
3. Resolve the pending business-model / KPI decisions before go-to-market work
4. Update Context with current state

---
*Last updated: 2026-06-11 after brownfield ingest + codebase audit*
