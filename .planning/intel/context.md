# Context (from DOCs and reconciliation notes)

Running notes keyed by topic, with source attribution. Verbatim-leaning; for narrative/status background that is neither a locked decision, a requirement, nor a binding constraint.

---

## Build status / roadmap
- source: docs/planning/ROADMAP.md (the project's single source of truth for what's built vs. left)
- The roadmap reports ~92% of items done despite older trackers showing 0%. Done: 48 event types, event serialization (88 tests), 9 instrumentation wrappers, session management (VizSession, EventBus over SharedFlow, in-memory EventStore, RuntimeSnapshot, ProjectionService, SessionManager), validation engine (20+ rules / 6 categories), deadlock + anti-pattern detection, 9 route modules, SSE streaming, 20+ scenarios, core library extraction (`coroutine-viz-core`, ~11,045 LoC), 60+ frontend components, 18 hooks, full REST/SSE client, TanStack routing, animation system, accessibility, 22 ADRs, CI, Dependabot.
- What's left (phased): production readiness (health/logging/CORS/bounded store/metrics), persistence, auth, replay, export, session comparison, performance, OpenTelemetry, IntelliJ plugin, SDK/CI, frontend testing/polish, marketing site. Billing is explicitly deferred / out of current scope.
- Priority order is 5 phases (Production Readiness → User Value → Data & Security → Scale & Integration → Polish & Growth).

## Persistence reconciliation (ADR-009 vs ADR-015) — RESOLVED, not a blocker
- sources: docs/adr/009-deployment-strategy.md, docs/adr/015-persistence-strategy.md
- ADR-009 (Deployment) and ADR-015 (Persistence) were previously flagged as contradictory. They have been reconciled in source and no longer contradict:
  - ADR-009 governs the **current deployment**: in-memory / ephemeral; sessions lost on restart; no database wired in. The in-memory event store is the **only implemented store**.
  - ADR-015 defines an **optional, future** persistence layer via the `*StoreInterface` seam (`SessionStoreInterface`, `EventStoreInterface`). It is **Accepted but not yet implemented** — Exposed/PostgreSQL/JDBC/H2 are designed targets, not present in code.
- Code-verified at reconciliation time: zero Exposed/PostgreSQL/JDBC/H2 in the repo; `EventStore` is a `CopyOnWriteArrayList`; sessions held in a `ConcurrentHashMap`.
- Downstream flag: treat database-backed persistence (and everything that depends on it — ADR-019 session sharing, the `shares` table, key-store-to-DB migration in ADR-016) as designed-but-unimplemented enhancements, not as currently-available capabilities.

## Cross-reference graph health — RESOLVED
- Two previously-reported cross-ref cycles are resolved and re-verified by cycle detection in this run:
  - ADR-017 ↔ ADR-018: reciprocal edge removed from ADR-017; one-way dependency ADR-018 → ADR-017 retained.
  - ADR-016 ↔ ADR-019: reciprocal edge removed from ADR-016; one-way dependency ADR-019 → ADR-016 retained.
- ADR-015's references to ADR-019/ADR-020 are advisory back-references (those ADRs depend on ADR-015), not dependency edges, so no ADR-015 ↔ ADR-019 cycle exists. DFS three-color cycle detection over the full ADR dependency graph returns no cycles.

## Architecture background
- source: docs/COROUTINE-VISUALIZER-BUSINESS-ANALYSIS.md (technical sections), CLAUDE.md
- Event-sourced architecture in three layers: instrumentation wrappers (VizScope, InstrumentedFlow, VizMutex, VizSemaphore, etc.) → event system (32–48 event types across coroutine/job/flow/dispatcher/deferred/sync/select/actor packages) → session management (VizSession, EventBus, EventStore, RuntimeSnapshot, ProjectionService).
- Non-invasive instrumentation: wraps `kotlinx-coroutines-core` at API boundaries via decorators, context propagation through `CoroutineContext.Element`, hook-based observation (`invokeOnCompletion`, `ContinuationInterceptor`). Frontend consumes events over SSE; the deep-dive PRD describes a WebSocket transport, but the implemented/decided transport is SSE (see ADR-002 / CLAUDE.md). Treat the WebSocket references in the deep-dive PRD as superseded background, not a current contract.
- Dual-mode operation envisioned: teaching (verbose, deterministic) and diagnostics (lightweight); DebugProbes bridge and OpenTelemetry export described as integrations.
