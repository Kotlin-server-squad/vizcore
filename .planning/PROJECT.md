# vizcore — Visualizer for Coroutines

## What This Is

vizcore is a real-time tool that makes invisible Kotlin coroutine execution visible. There are two capture paths: (1) developers wrap their `kotlinx-coroutines-core` usage with non-invasive instrumentation (VizScope, InstrumentedFlow, VizMutex, VizSemaphore, etc.), or (2) — new in v1.1 — they point vizcore at their **own running app** with zero manual wrapping via an embeddable client library (`coroutine-viz-client`) that installs `DebugProbes`, polls live coroutines, and streams synthesized events to the backend over WebSocket. Either way the event-sourced Kotlin/Ktor backend streams events over SSE to a React frontend that renders interactive timeline, tree, graph, thread-lane, and event-log views — now with per-coroutine source attribution (`file:line` jump-to-code), an IDE-docked live view, aggregate metrics (active/peak/throughput/dispatcher-util/leaks), and a badged LIVE/DEMO sessions home + connect wizard. It serves learning developers, educators/content creators, enterprise training, and (advisory) IDE-tool partnership. It ships as a containerized web app (Ktor backend on :8080 + React frontend on :3000), a publishable SDK artifact (`coroutine-viz-core`, MIT-licensed per ADR-021), an embeddable client lib, and an optional IntelliJ plugin.

**Build status: BROWNFIELD.** Shipped v1.0 (foundation/runtime fixes, replay/export/compare, persistence/auth/sharing) and v1.1 (real-code coroutine observability). This document and ROADMAP.md describe the remaining feature work (Phase 4 scale/observability/SDK, Phase 5 IntelliJ plugin + FE quality).

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
- ✓ Persistence — optional JDBC/Exposed store (H2 dev / PostgreSQL prod), DB-aware retention policy, sessions survive restart (PERS-01/02/03, ADR-015). Validated in Phase 3: Persistence, Auth & Sharing.
- ✓ Authentication & multi-tenancy — dual-provider auth (SHA-256 API keys + seeded-user JWT) enforced on non-public routes, fail-open when unconfigured, tenant-isolated session reads + ownership-enforced share routes (AUTH-01..05, ADR-016, D-03/D-04). Validated in Phase 3: Persistence, Auth & Sharing.
- ✓ Sharing — revocable, expiring, rate-limited read-only share tokens + standalone `/shared/:token` view (SHAR-01/02, ADR-019). Validated in Phase 3: Persistence, Auth & Sharing.
- ✓ Production hardening — health endpoints, dev/prod logging, config-driven CORS, OpenAPI, bounded event store wired into the running server, full Micrometer metrics; structural consolidation (the duplicated `session.*` fork removed) — v1.0 (Phase 1; FQN-shadowing trap closed permanently in v1.1 Phase 08.4 with a build guard).
- ✓ Replay & time-travel + Export (PNG/SVG/WebM) + Session comparison — v1.0 (Phase 2; RPLY-01..03, EXPT-01/02, CMPR-01/02).
- ✓ Real-code coroutine observability — pluggable `InstrumentationSource` + `DebugProbesSource`, embeddable `coroutine-viz-client` lib, WebSocket ingest endpoint, live real-app view with hierarchy/source-attribution/metrics/leaks, IDE-docked FE + badged connect/onboarding — **v1.1 (Phases 6–8.5; RCO-01..07, FE-ALIGN, ONB-01).**

### Active

<!-- Remaining scope (post-v1.1). Building toward these. See ROADMAP.md for phase mapping. -->

- [ ] Performance & scaling — per-event-type sampling, batching, SSE compression header (`X-Accel-Buffering: no`), dev-only load-test harness, rate/cap protection (PERF-01..04, ADR-020) — Phase 4
- [ ] SDK distribution — publish `coroutine-viz-core` to GitHub Packages, CLI fat JAR, `coroutineVizCheck` Gradle task (SDK-01/02, ADR-021) — Phase 4
- [ ] Observability integration — OpenTelemetry/OTLP exporter (zero overhead when off), verify spans in Jaeger/Zipkin (OTEL-01/02) — Phase 4
- [ ] IntelliJ plugin — complete `RunWithVisualizerAction` (javaagent launch), JCEF tool window, plugin tests, Marketplace (IDE-01..03; ADR-010 Proposed/advisory, ADR-014) — Phase 5
- [ ] Frontend testing & polish — actor/select/anti-pattern tests, FE coverage ≥80% in CI, Playwright E2E, Storybook, visual regression (FETEST-01..03, ADR-022) — Phase 5
- [ ] ONB-01 connect-wizard follow-up — bind the `ConnectWizard` auto-resolve to the real `VizcoreClient` session (deferred tech-debt from v1.1; small slice)

### Out of Scope

- Billing / payment processing — explicitly deferred; pricing model itself unresolved (see Key Decisions)
- WebSocket as the **browser-facing** transport — superseded by SSE (ADR-002). (Note: v1.1 added WebSocket for the **remote-client → backend ingest** path only; the FE still consumes via SSE.)
- Marketing site & go-to-market content — tracked separately; not engineering scope for this milestone

## Context

- **Event-sourced, three-layer backend:** instrumentation/`InstrumentationSource`s → event types → session management (VizSession, EventBus over SharedFlow, EventStore, RuntimeSnapshot, ProjectionService, MetricsProjection). Frontend consumes via SSE; `normalizeEvent()` maps backend `type`/`kind`.
- **Three Gradle modules:** `coroutine-viz-core` (pure Kotlin, no Ktor, authoritative — also home to the shared `appJson` serializer), `backend/src` (Ktor server: routes, auth, ingest, persistence), and `coroutine-viz-client` (embeddable JVM client lib that streams a remote app's coroutines over WebSocket). The old `session.*` fork in `backend/` is gone (Phase 1); the same-FQN model-duplication trap is closed permanently by the `verifyNoDuplicateSourceFqns` build guard (Phase 08.4).
- **Real-code observability pipeline (v1.1):** `VizcoreClient.start(appName)` → JWT auth → `POST /api/sessions` → `DebugProbesSource` (poll/diff/synthesize w/ source attribution) → lifetime-scoped `OutboundBuffer` → WebSocket `/api/sessions/{id}/ingest` → `VizSession.send` → EventStore → EventBus → SSE → FE live IDE-dock (source frames + metrics + leaks). Verified end-to-end (audit: 11/11 seams wired).
- **Deployment:** ephemeral by default (in-memory, ADR-009); optional DB persistence (`storage.type=database`, Exposed/HikariCP, H2/PostgreSQL) survives restart (Phase 3). Dual-provider auth (API-key + JWT) with tenant isolation is wired on non-public routes, fail-open when unconfigured.
- **Remaining gap (was #5):** IntelliJ plugin (ADR-010) mostly built, but `RunWithVisualizerAction.actionPerformed` is a TODO stub (javaagent launch missing); zero plugin tests — Phase 5.
- **Competing variants (unresolved, do NOT block):** business model/pricing/license and success-metric/KPI sets differ between the two PRDs. BUSINESS_ANALYSIS_V2 is the working default; the alternative is preserved in `milestones/v1.1-REQUIREMENTS.md` for later go-to-market resolution.

## Constraints

- **Tech stack**: Kotlin 2.2.20 / Ktor 3.3.2 (Netty) backend on JVM 21; React 19 / Vite 6 / TypeScript 5.7 frontend on Node 24 — locked by ADR-001/002/005/009 and CLAUDE.md.
- **Transport**: SSE for the browser-facing event stream (ADR-002). WebSocket is used **only** for the remote-client → backend ingest path (v1.1 Phase 7, RCO-05); it never reaches the FE.
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
| Pluggable `InstrumentationSource` fronting the EventBus (v1.1) | Multiple capture paths (wrappers + DebugProbes) share one downstream | ✓ Good |
| `DebugProbesSource` poll/diff/synthesize (~150 ms) for zero-wrap capture (v1.1) | Visualize a real app with no code changes (poll-bounded timing accepted) | ✓ Good (timing ±150 ms is the known tradeoff) |
| WebSocket for remote-client → backend ingest (v1.1, RCO-05) | Bidirectional streaming from a separate JVM; FE still SSE | ✓ Good (scoped reversal of the SSE-only constraint, ingest-only) |
| Embeddable `coroutine-viz-client` module, JWT-auth + reconnect + zero-loss `OutboundBuffer` (v1.1) | Client half of real-app transport; no event loss across reconnect | ✓ Good |
| Build guard `verifyNoDuplicateSourceFqns` wired into `check` (v1.1, Phase 08.4) | Same-FQN model duplication caused a runtime HTTP 500; trap closed structurally | ✓ Good |
| Client-side LIVE/DEMO derivation (no backend field) (v1.1, ONB-01) | Ship the badged sessions home without a backend schema change | ⚠️ Revisit (a clean backend `sourceType` discriminator deferred; ConnectWizard auto-resolve decoupled — tech-debt) |
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
*Last updated: 2026-06-27 after **v1.1 milestone** ("Real-Code Coroutine Observability") — 8 phases / 21 plans, 9/9 requirements satisfied, audit `tech_debt` (no blockers, 11/11 integration seams wired). vizcore can now point at a developer's own running Kotlin app via the `coroutine-viz-client` lib and render it live with source attribution + metrics. Carried-forward debt: ONB-01 ConnectWizard auto-resolve is decoupled from the real client session (onboarding UX polish). Next: Phase 4 (scale/observability/SDK) and Phase 5 (IntelliJ plugin + FE quality).*
