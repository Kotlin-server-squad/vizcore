# Decisions (from ADRs)

Synthesized from `docs/adr/`. One entry per ADR. Status reflects the ADR's own `## Status` field; `locked` reflects the classifier's lock flag (Accepted ADRs = locked; Proposed = unlocked).

---

## ADR-001: Monorepo Architecture
- source: docs/adr/001-monorepo-architecture.md
- status: Accepted (locked)
- decision: Consolidate frontend and backend into a single monorepo using pnpm workspaces and Docker Compose.
- scope: repository structure, frontend, backend, shared API types, CI/CD workflows, development environment

## ADR-002: Shared API Contract via OpenAPI
- source: docs/adr/002-shared-api-contract.md
- status: Accepted (locked)
- decision: Generate TypeScript types from the backend OpenAPI specification; the backend spec is the single source of truth for API contracts (`shared/api-types`).
- scope: frontend, backend, API contract, TypeScript type generation, OpenAPI spec, shared/api-types workspace package

## ADR-003: Test Strategy
- source: docs/adr/003-test-strategy.md
- status: Accepted (locked)
- decision: Adopt Vitest + Testing Library + MSW for frontend and JUnit 5 + Ktor Test Host for backend, with prioritized component/hook and integration test targets.
- scope: frontend testing framework, backend testing framework, coverage targets, CI integration

## ADR-004: CI/CD Pipeline
- source: docs/adr/004-ci-cd-pipeline.md
- status: Accepted (locked)
- decision: GitHub Actions workflows with path filtering for lint/test/build across frontend, backend, and shared packages, plus branch protection.
- scope: CI/CD, GitHub Actions, frontend/backend/shared workflows, branch protection, ESLint, detekt, ktlint, Vitest

## ADR-005: Docker Development Environment
- source: docs/adr/005-docker-development.md
- status: Accepted (locked)
- decision: Adopt Docker Compose for local development to standardize environment setup across services.
- scope: docker-compose.yml, backend/frontend Docker setup, development workflow, contributor onboarding

## ADR-006: PR Consolidation Strategy
- source: docs/adr/006-pr-consolidation.md
- status: Accepted (locked)
- decision: Defines merge order and handling of 8 open PRs across frontend and backend before monorepo conversion.
- scope: pull-request management, frontend/backend repositories, monorepo conversion, merge strategy

## ADR-007: Frontend Visualization Expansion
- source: docs/adr/007-frontend-visualization-expansion.md
- status: Accepted (locked)
- decision: Expand frontend visualization panels for channels, flow operators, sync primitives, job status, and validation event categories.
- scope: frontend, visualization panels (channel, flow operator, sync primitives, job status, validation), session detail view, event-type detection

## ADR-008: Frontend Migration to Shared Types
- source: docs/adr/008-shared-types-migration.md
- status: Accepted (locked)
- decision: Replace the frontend's manually maintained API types with generated types from the shared api-types package (single source of truth).
- scope: frontend type migration, API contracts, TS path aliases, shared types package, event normalization

## ADR-009: Deployment Strategy
- source: docs/adr/009-deployment-strategy.md
- status: Accepted (locked)
- decision: Deploy backend as a Docker container on container PaaS and frontend as static assets on CDN, with a self-hosted docker-compose option. **No database in the current deployment** — sessions are ephemeral, served by the in-memory event store (the only store implemented today). ADR-009 explicitly defers to ADR-015 for the optional, future persistence seam.
- scope: deployment, backend, frontend, Docker, PaaS, CDN, CORS, environment configuration
- note: Reconciled with ADR-015 (see context.md). ADR-009 governs current/ephemeral deployment; ADR-015 governs future optional persistence. No longer contradictory.

## ADR-010: IntelliJ Plugin Architecture
- source: docs/adr/010-intellij-plugin-architecture.md
- status: **Proposed (NOT locked)**
- decision: Hybrid architecture for an IntelliJ IDEA plugin using VizSession events as the primary data source with DebugProbes as a fallback; requires core library extraction (ADR-013) and HTTP-based communication (ADR-014).
- scope: IntelliJ IDEA plugin, VizSession instrumentation, DebugProbes integration, event system, library extraction, HTTP protocol, Swing UI, coroutine-viz-core module
- note: Proposed status → unlocked; this is the only non-locked ADR. Decisions here are advisory until accepted.

## ADR-011: Animation System Design
- source: docs/adr/011-animation-system-design.md
- status: Accepted (locked)
- decision: Centralize animation variants using framer-motion to eliminate duplication and enforce consistent timing across visualizer UI components.
- scope: animation-variants.ts, CoroutineTree.tsx, entrance/exit animations, state indicators, flow visualizations, gauges, graph animations

## ADR-012: Validation Engine Architecture
- source: docs/adr/012-validation-engine-architecture.md
- status: Accepted (locked)
- decision: Introduce a `ValidationRule` interface and `ValidationRuleRegistry` for auto-discovery and execution of coroutine correctness rules with real-time validation and a dashboard.
- scope: validation system, validation rules, rule registry, real-time validation, validation dashboard, event-sourced architecture

## ADR-013: Core Library Extraction
- source: docs/adr/013-core-library-extraction.md
- status: Accepted (locked)
- decision: Extract core visualization logic from the monolithic Ktor backend into a standalone `coroutine-viz-core` library to support IntelliJ plugin integration and SDK distribution.
- scope: coroutine-viz-core module, backend refactoring, multi-module Gradle project, IntelliJ plugin, library publishing

## ADR-014: Plugin Communication Protocol
- source: docs/adr/014-plugin-communication-protocol.md
- status: Accepted (locked)
- decision: Use HTTP-based communication with a lightweight CIO server (port 8090) for plugin-app event exchange via PluginEventSink / PluginEventReceiver.
- scope: IntelliJ plugin, plugin communication, event protocol, HTTP/REST, port 8090
- depends-on: ADR-010, ADR-013

## ADR-015: Persistence Strategy
- source: docs/adr/015-persistence-strategy.md
- status: Accepted (locked) — **designed but not yet implemented**
- decision: Define `SessionStoreInterface` and `EventStoreInterface` in coroutine-viz-core (the `*StoreInterface` seam). In-memory implementations remain the default. Optional database-backed variants use Exposed ORM with H2 (dev) / PostgreSQL (prod), HikariCP pooling, JSONB event storage, and a retention policy. Storage type is config-selectable (`storage.type: memory | database`, default `memory`).
- scope: session storage, event storage, persistence layer, database schema, connection pooling, retention policy, configuration
- note: Code-verified: zero Exposed/PostgreSQL/JDBC/H2 in the repo today. EventStore is `CopyOnWriteArrayList`; sessions in `ConcurrentHashMap`. Treat the database-backed variant as an Accepted-but-unimplemented enhancement. Governs the future optional persistence layer; ADR-009 governs current deployment. Relationship to ADR-019/ADR-020 is one-way (those depend on this); ADR-015 references them only as advisory back-references.

## ADR-016: Authentication Architecture
- source: docs/adr/016-authentication-architecture.md
- status: Accepted (locked)
- decision: Two-phase auth via `ktor-server-auth`. Phase A: API keys (`X-API-Key`, SHA-256 hashed, env-loaded). Phase B: JWT (HMAC-SHA256 dev / RS256 prod, `/api/auth/token`, refresh tokens). Role-based access control (VIEWER/RUNNER/ADMIN), tenant isolation by user ID. `/health`, `/openapi.json`, and the token endpoint remain unauthenticated.
- scope: authentication, authorization, API key management, JWT tokens, RBAC, tenant isolation, session management, Ktor auth plugin
- depends-on: ADR-015 (key store migrates to DB once persistence lands)

## ADR-017: Replay Engine Design
- source: docs/adr/017-replay-engine-design.md
- status: Accepted (locked)
- decision: Client-side replay engine in the React frontend (no backend changes). ReplayController toolbar, `useReplayEngine` hook, useReducer state machine (IDLE/PLAYING/PAUSED/STEPPING_*), shared `visibleEvents` slice across all panels, speed selector (0.5x-5x), keyboard shortcuts.
- scope: ReplayController, useReplayEngine, SessionDetails, CoroutineTree, ThreadLanes, EventsList, DispatcherOverview, playback state machine, event reconstruction
- depends-on: ADR-011 (animations respect replay speed)
- note: ADR-018 (export video) depends on this; the 017↔018 edge is one-way (018 → 017) — the prior reciprocal cross-ref was removed during conflict resolution.

## ADR-018: Export System Design
- source: docs/adr/018-export-system-design.md
- status: Accepted (locked)
- decision: Three-tier client-side export — PNG (html2canvas), SVG (style-inlined serialization of graph views), and video/WebM (MediaRecorder capturing a replay). ExportMenu dropdown in SessionDetails toolbar plus per-panel PNG export. JSON export bonus.
- scope: export system, PNG/SVG/video export, ExportMenu, SessionDetails toolbar, html2canvas, MediaRecorder API, client-side rendering
- depends-on: ADR-017 (video export drives a replay), ADR-011

## ADR-019: Session Sharing
- source: docs/adr/019-session-sharing.md
- status: Accepted (locked)
- decision: Share-token model — UUID tokens with expiry (1d/7d/30d/never), revocation, read-only views, rate limiting, access tracking. Requires the persistence layer (shares table). Share tokens bypass user auth by design.
- scope: share token generation, session sharing, read-only views, share revocation, token expiry, rate limiting, access tracking
- depends-on: ADR-015 (shares table needs a DB), ADR-016 (share tokens bypass user auth), ADR-017 (replay available in shared view)
- note: 016↔019 edge is one-way (019 → 016) — prior reciprocal cross-ref removed during conflict resolution.

## ADR-020: Performance Scaling
- source: docs/adr/020-performance-scaling.md
- status: Accepted (locked)
- decision: Six performance areas — bounded event store (drop-newest at limit), Micrometer/Prometheus metrics, per-event-type sampling (deterministic by hash of sessionId+seq, lifecycle events always pass), dev-only load-test harness, SSE gzip compression, and event batching.
- scope: event store, metrics/observability, event sampling, load testing, SSE optimization, event batching, backend performance
- depends-on: ADR-015 (retention complements bounded store), ADR-013 (metrics live in backend module, not core)

## ADR-021: SDK Distribution
- source: docs/adr/021-sdk-distribution.md
- status: Accepted (locked)
- decision: Publish `coroutine-viz-core` to GitHub Packages (`com.jh.coroutine-viz:coroutine-viz-core`), Maven Central as follow-up; provide a CLI tool (fat JAR) for CI/CD validation, a Gradle task wrapper, sample apps, and semantic versioning. License declared MIT in the publishing POM.
- scope: coroutine-viz-core, GitHub Packages, Maven Central, CLI tool, CI/CD integration, semantic versioning
- depends-on: ADR-013 (module being published), ADR-014 (plugin consumes artifact), ADR-012 (CLI wraps validation engine)

## ADR-022: Frontend Testing Strategy
- source: docs/adr/022-frontend-testing-strategy.md
- status: Accepted (locked)
- decision: Four-tier frontend testing — complete Vitest/Testing Library unit/component coverage, Playwright E2E (6 critical flows), Storybook docs, and Chromatic visual regression. Target 80%+ coverage, enforced in CI.
- scope: frontend testing, unit/component tests, E2E (Playwright), Storybook, Chromatic, visual regression, code coverage
- depends-on: ADR-003 (extends original test strategy), ADR-017, ADR-018, ADR-019 (new features need E2E)

## ADR-023: Code Quality Tooling (ktlint + detekt)
- source: docs/adr/023-code-quality-tooling.md
- status: Accepted (locked)
- decision: Add ktlint v12.2.0 and detekt v1.23.7 to enforce formatting and static analysis in the Kotlin backend, gated in CI.
- scope: ktlint, detekt, backend module, code formatting, static analysis, Kotlin, CI gates
- depends-on: ADR-004

## ADR-024: Animation Color System
- source: docs/adr/024-animation-color-system.md
- status: Accepted (locked)
- decision: Centralized color configuration mapping 7 coroutine states to distinct colors with state-specific animations for visual consistency across all UI components.
- scope: coroutine-state-colors.ts, animation-variants.ts, CoroutineTree.tsx, CoroutineTreeGraph.tsx, EventsList.tsx, color mapping, animation variants, coroutine states

## ADR-025: Flow Particle Path Animation
- source: docs/adr/025-flow-particle-animation.md
- status: Accepted (locked)
- decision: SVG-based particle animation view visualizing values moving through flow operator chains, with distinct rendering for filtered, transformed, and regular values.
- scope: flow visualization, particle animation, SVG rendering, FlowOperatorChain, FlowParticlePath, FlowPanel, animation variants

## ADR-026: Micro-interactions, Pattern Animations, Thread Lanes
- source: docs/adr/026-micro-interactions-patterns.md
- status: Accepted (locked)
- decision: Standardize micro-interactions, pattern animations, and thread-lane animations using motion.div, stagger presets, and reusable animation variants.
- scope: micro-interactions, pattern animations, thread lanes, animation-variants, stagger presets, motion.div, gallery cards, coroutine-tree, events-list, channel-timeline, retry/producer-consumer/fan-out-fan-in views

## ADR-027: Animation Performance and Replay Integration
- source: docs/adr/027-animation-performance-replay.md
- status: Accepted (locked)
- decision: Viewport-aware animation rendering, adaptive frame-rate throttling, and smooth replay motion interpolation to improve performance on lower-end devices.
- scope: animation system, viewport visibility, frame-rate throttling, replay scrubbing, motion interpolation, performance optimization
