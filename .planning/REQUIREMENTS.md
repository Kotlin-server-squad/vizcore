# Requirements: vizcore — Visualizer for Coroutines

**Defined:** 2026-06-11
**Core Value:** A developer can SEE and UNDERSTAND coroutine, Flow, and structured-concurrency execution that is otherwise invisible — reducing time-to-understand.

> **Brownfield note:** This product is ~92% implemented. Requirements below cover the REMAINING work (Active) plus already-shipped capabilities (recorded as Validated for traceability). v1 here = "what closes out this milestone," not "build from zero." Source intel: `.planning/intel/{requirements,decisions,constraints,context}.md`.

## v1 Requirements

Remaining-scope requirements for the current milestone. Each maps to exactly one roadmap phase.

### Foundation & Structural Consolidation

- [x] **FND-01**: The running backend uses the single authoritative `coroutine-viz-core` session classes — the duplicate `com.jh.proj.coroutineviz.session.*` fork in `backend/src/main/` is removed and compilation resolves against core.
- [x] **FND-02**: The running server's EventStore is the bounded (`maxEvents`) variant — events evict at the cap, no unbounded growth (verifiable: a high-volume session never exceeds the configured ceiling).
- [x] **FND-03**: A regression test asserts the in-use EventStore is the bounded variant, so the fork cannot silently return.

### Runtime Correctness (2026-06-11 walkthrough + scenario-audit findings)

- [x] **FIX-01**: All `VizEvent` subclasses are registered in a kotlinx.serialization `SerializersModule` polymorphic scope — SSE streaming and `GET /api/sessions/{id}/events` work end-to-end (no `SerializationException`), unblocking the Events tab and the conditional Channels/Flow/Sync/Jobs tabs. (Evidence: VERIFICATION.md RT-01/RT-03, SCENARIO-AUDIT.md SC-03.)
- [x] **FIX-02**: The frontend validation feature consumes the real backend response shape (`{sessionId, results[], timing}`) — Run Validation renders results and never crashes the page. (Evidence: VERIFICATION.md RT-02.) ✓ Completed 01-02; thread-activity wire-shape alignment (Threads tab, UAT round-2 gap 1) completed 01-14
- [x] **FIX-03**: `VizScope` classifies terminal coroutine state by cause type (not message-contains-label) — a throwing coroutine emits `CoroutineFailed` and renders FAILED, distinct from cancelled victims. (Evidence: SCENARIO-AUDIT.md SC-01.)
- [x] **FIX-04**: The Cancellation scenario performs a targeted child cancel — `child-to-be-cancelled` ends CANCELLED while `normal-child` ends COMPLETED. (Evidence: SCENARIO-AUDIT.md §3.)

### Production Readiness

- [x] **PROD-01**: `GET /api/health` (plus `/live`, `/ready`) returns `HealthStatus` with component checks (sessionManager, memory), uptime, and version.
- [x] **PROD-02**: Logging uses environment-selectable dev/prod Logback profiles; no stray `println`.
- [x] **PROD-03**: CORS allowed origins/methods are read from `application.yaml`/env, not hardcoded.
- [x] **PROD-04**: All endpoints and DTOs have OpenAPI descriptions and the generated spec validates.
- [x] **PROD-05**: Micrometer exposes the full ADR-020 metric set (events emitted/dropped, scenario duration, event-processing duration, plus active-sessions and SSE-client gauges).

### Replay & Time Travel

- [x] **RPLY-01**: A developer can play, pause, stop, step-forward, and step-back through a session's events from a replay controller, with all panels reflecting the current event index.
- [x] **RPLY-02**: A developer can select playback speed (0.5x-5x) and scrub to any position via a progress bar.
- [x] **RPLY-03**: Animations respect the active replay speed and the current event (per ADR-011/027).

### Export & Sharing

- [x] **EXPT-01**: A developer can export a visualization as PNG (via html2canvas) and download it.
- [x] **EXPT-02**: A developer can export a graph view as standalone style-inlined SVG, and export a replay as WebM video (MediaRecorder).
- [x] **SHAR-01**: A developer can create a share token (expiry 1d/7d/30d/never) for a session via `POST /api/sessions/:id/share`.
- [x] **SHAR-02**: Anyone with a valid token can open a read-only shared view via `GET /api/shared/:token`; tokens are revocable and rate-limited.

### Persistence & Data

- [x] **PERS-01**: An optional JDBC store (Exposed + HikariCP, H2 dev / PostgreSQL prod) implements the `SessionStoreInterface`/`EventStoreInterface` seam, selectable via `storage.type=database`.
- [x] **PERS-02**: Sessions and events survive a backend restart when database storage is enabled (Flyway-migrated schema, events stored as JSONB).
- [x] **PERS-03**: A retention policy (max-age TTL + max-events trim) runs as a background process when persistence is enabled.

### Authentication & Multi-tenancy

- [x] **AUTH-01**: Non-public routes are wrapped in `authenticatedApi()`; with `API_KEY` set, requests without a valid `X-API-Key` are rejected, while `/health`, `/openapi.json`, and the token endpoint stay open.
- [x] **AUTH-02**: API keys are compared as SHA-256 hashes (not plaintext), per ADR-016.
- [x] **AUTH-03**: JWT auth (`/api/auth/token`, HMAC dev / RS256 prod, refresh) issues a `UserPrincipal` with VIEWER/RUNNER/ADMIN roles.
- [x] **AUTH-04**: Sessions are filtered by authenticated user (tenant isolation) — no cross-tenant reads.
- [x] **AUTH-05**: Route-level auth enforcement is covered by end-to-end tests (reject-without-key, allow-with-key).

### Performance & Scaling

- [ ] **PERF-01**: Per-event-type sampling (deterministic by hash of sessionId+seq; lifecycle events always pass) is wired into event emission with `X-Sampled` metadata.
- [ ] **PERF-02**: Event batching (configurable size/timeout) and SSE gzip compression — including the `X-Accel-Buffering: no` header — are active for the SSE stream.
- [ ] **PERF-03**: A dev-only load-test harness produces N coroutines × M events/sec and reports latency/memory.
- [ ] **PERF-04**: Rate limiting (or a configurable max-active-session cap) protects session creation and scenario execution.

### Session Comparison

- [x] **CMPR-01**: `ComparisonService.compare(a, b)` returns event-count, duration, and thread-utilization deltas via `GET /api/sessions/compare?a=&b=`. _(Phase 2 Plan 01)_
- [x] **CMPR-02**: A developer can view two sessions side-by-side with delta highlights.

### SDK & CI/CD

- [ ] **SDK-01**: `coroutine-viz-core` is published to GitHub Packages (`com.jh.coroutine-viz:coroutine-viz-core`, MIT POM) with semantic versioning and a working sample app.
- [ ] **SDK-02**: A CLI fat JAR (`coroutine-viz-ci.jar check --config ci-config.yaml`) and a Gradle task wrapper (`coroutineVizCheck`) run scenarios/validation for CI/CD.

### IntelliJ Plugin

- [ ] **IDE-01**: `RunWithVisualizerAction.actionPerformed` launches the current run configuration with the instrumentation agent (javaagent/classpath) so clicking "Run with Visualizer" actually instruments user code.
- [ ] **IDE-02**: A tool window (JCEF) loads the React app and auto-detects the backend on :8080, showing connection status.
- [ ] **IDE-03**: The plugin has automated tests covering the receiver, session manager, and the run action; (advisory) Marketplace metadata prepared.

> IDE-* derive from ADR-010 which is **Proposed/advisory, not locked** — treat plugin architecture as non-binding and revisit acceptance before heavy investment.

### Frontend Testing & Polish

- [ ] **FETEST-01**: Actor, select, and anti-pattern components/hooks have unit/component tests; overall frontend coverage ≥ 80%, enforced in CI.
- [ ] **FETEST-02**: Playwright E2E covers the critical flows (create session → run scenario → verify panels → validate → replay/export).
- [ ] **FETEST-03**: Storybook stories and Chromatic visual-regression snapshots exist for key components.

### Observability Integration

- [ ] **OTEL-01**: An OpenTelemetry/OTLP exporter maps events to spans with a batch processor and configurable flush interval, with zero overhead when disabled.
- [ ] **OTEL-02**: Coroutine spans are verifiable in Jaeger/Zipkin with parent-child relationships preserved.

## v2 Requirements

Deferred beyond the current milestone.

### Growth & UX

- **GROW-01**: Marketing/landing site (hero, demo, features, how-it-works) and content marketing.
- **GROW-02**: Theme customization beyond light/dark; drag-and-drop panel layout with saved presets.
- **GROW-03**: Interactive onboarding tutorial (e.g., react-joyride).
- **GROW-04**: Maven Central publication (follow-up to GitHub Packages).

## Competing Variants — UNRESOLVED (do NOT merge)

Preserved verbatim per ingest gate. BUSINESS_ANALYSIS_V2 (PRD-A) is the **working default**; the alternative is retained for later go-to-market resolution. Neither blocks engineering.

### REQ-business-model

**Variant A (working default) — PRD-A (BUSINESS_ANALYSIS_V2.md §4.1):** Freemium open source. Free tier license **Apache 2.0**. Premium **$29/mo or $249/yr** (advanced wrappers, custom scenario builder, export, hosted sharing, priority support, commercial license). Enterprise **$999–9,999/yr** (private deployment, custom branding, team analytics, SLA).

**Variant B (alternative) — PRD-B (COROUTINE-VISUALIZER-BUSINESS-ANALYSIS.md §10.3):** Open-source core license **MIT**. Individual **Free** (web UI, 10 scenarios, community support). Team **$49/mo** (scenario sharing, collaboration, priority support). Enterprise **$499/mo** (on-premise, custom scenarios, SSO, SLA).

*Divergences:* free-tier license (Apache 2.0 vs MIT); tier names (Premium vs Team); price points. Note: ADR-021 sets the published SDK artifact license to MIT in its POM — that governs the library artifact, distinct from the product free-tier license under dispute.

### REQ-success-metrics-kpis

**Variant A (working default) — PRD-A (BUSINESS_ANALYSIS_V2.md §7):** Product — avg session 15+ min; 5+ scenarios/session; 40% 7-day return; API p95 < 200ms; event processing < 10ms; render < 1s/1000 events; uptime 99.5%+. Adoption — Free→Premium trial 10%; Trial→Paid 25%. Business — MRR growth 15%+ MoM; churn < 5%; NPS 50+; LTV:CAC 3:1. Community — 1K+ stars (early); 5K+ free users (year 1).

**Variant B (alternative) — PRD-B (COROUTINE-VISUALIZER-BUSINESS-ANALYSIS.md §10.4):** Adoption — 1,000+ stars in 6 months; MAU 5,000+ year 1; 3+ training partners. Engagement — avg session 15+ min; scenario completion 80%+; 20+ community scenarios. Business — paid conversion **5% of active users**; 2+ enterprise deals year 1; 10+ talks/workshops year 1.

*Divergences:* paid-conversion definition (10%/25% funnel vs flat 5%); engagement metric set; business-health framing (MRR/churn/NPS vs partner/conversion/talk counts).

## v1.1 Requirements — Real-Code Coroutine Observability (added 2026-06-24)

From the `/gsd-explore` deep-dive. Dev-first: point vizcore at the developer's own running
Kotlin app and visualize it in the existing FE. Design record:
`.planning/notes/real-code-observability-architecture.md`. Future sources:
`.planning/seeds/future-instrumentation-sources.md`.

- [ ] **RCO-01**: A pluggable `InstrumentationSource` interface sits in front of the
  `EventBus`; today's `VizScope`/`InstrumentedFlow`/`VizMutex` wrappers are formalized as a
  `WrapperSource` behind it. Multiple sources run concurrently and are independently
  enable/disable-able. Downstream (EventStore/Projection/SSE/FE) is unchanged.

- [ ] **RCO-02**: A `DebugProbesSource` installs kotlinx `DebugProbes`
  (`enableCreationStackTraces = true`), polls `dumpCoroutinesInfo()` (~150 ms), diffs
  snapshots by coroutine identity, and synthesizes existing `VizEvent`s from the deltas
  (created/started/suspended/resumed/completed).

- [ ] **RCO-03**: Each coroutine carries source attribution — function + `file:line` from
  the creation stack trace, plus dispatcher and `CoroutineName` — surfaced in the FE.

- [x] **RCO-04**: An embeddable **vizcore client library** installs `DebugProbesSource` in a
  user's JVM app and streams events to the backend over HTTP/WebSocket; zero overhead when
  not enabled.

- [x] **RCO-05**: A backend **ingest endpoint** accepts streamed events from a remote client
  and feeds them into a session's `EventBus` (auth-scoped, reusing existing session/SSE/FE).

- [x] **RCO-06**: The existing FE animations render a **real app's** coroutines live, in
  real-time, via the same SSE pipeline (live "what's running now").

- [x] **RCO-07**: Aggregate metrics for a real-app session — active/peak coroutine count,
  throughput, dispatcher/thread-pool utilization, and leak detection
  (long-lived/never-completing coroutines).

> **Constraint (accepted v1):** DebugProbes timing is poll-bounded (±~150 ms) and misses
> coroutines that start+finish between polls; precise timing is deferred to `WrapperSource`
> (hybrid) and future `AgentSource`. Dev-only (overhead rules out prod).
>
> **Delivery vehicle:** IntelliJ "Run with Visualizer" (`IDE-01..03`, issues `#36/#37/#38`)
> is the ergonomic front door; not duplicated here.

## FE-Alignment & Onboarding (added 2026-06-27, milestone v1.1 — Phase 08.5)

Closes the frontend sketch-vs-shipped divergences recorded in `Skill("sketch-findings-vizcore")`
and adds the greenfield connect/onboarding surface. FE-only — no backend change, no new dependency.

- [x] **FE-ALIGN**: Close the sketch-vs-shipped FE divergences per `sketch-findings-vizcore` —
  (1) the live real-app view becomes the IDE-docked layout (canvas on top + dock with a header
  metric-tile strip, live list left, source panel right; metrics un-buried from the Threads tab;
  leaks inline/amber); (2) source attribution becomes inline compact-chips → expand-to-full-stack
  in the dock (jump-to-code preserved verbatim). Live-only; replay/shared keep the tabbed layout.

- [x] **ONB-01**: Greenfield connect/onboarding (sketch 003) — a badged LIVE/DEMO sessions
  home (sidebar-as-home, unmistakable per-row pill) + a 3-step HeroUI modal connect wizard
  (add client lib → enable `VizcoreClient.start()` → run, with a "waiting for events" spinner
  that resolves to the live view). LIVE/DEMO derived client-side (no backend field).

> Source contract: `.planning/phases/08.5-…/08.5-UI-SPEC.md` (approved) is the binding design
> contract; the three sketch winners are VALIDATED + LOCKED. No backend `sourceType`/`appName`
> field — a clean LIVE/DEMO discriminator is deferred to a future backend phase.

## Out of Scope

| Feature | Reason |
|---------|--------|
| Billing / payment processing | Explicitly deferred; pricing model itself unresolved (competing variants) |
| WebSocket transport | Superseded by SSE (ADR-002); PRD WebSocket references are background |
| Marketing site & GTM content | v2 growth scope, not engineering for this milestone |
| Maven Central publication | v2 follow-up after GitHub Packages (SDK-01) |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| FIX-01 | Phase 1 | Complete |
| FIX-02 | Phase 1 | Complete |
| FIX-03 | Phase 1 | Complete |
| FIX-04 | Phase 1 | Complete |
| FND-01 | Phase 1 | Complete |
| FND-02 | Phase 1 | Complete |
| FND-03 | Phase 1 | Complete |
| PROD-01 | Phase 1 | Complete |
| PROD-02 | Phase 1 | Complete |
| PROD-03 | Phase 1 | Complete |
| PROD-04 | Phase 1 | Complete |
| PROD-05 | Phase 1 | Complete |
| RPLY-01 | Phase 2 | Complete |
| RPLY-02 | Phase 2 | Complete |
| RPLY-03 | Phase 2 | Complete |
| EXPT-01 | Phase 2 | Complete |
| EXPT-02 | Phase 2 | Complete |
| CMPR-01 | Phase 2 | Complete (P01) |
| CMPR-02 | Phase 2 | Complete (P06) |
| PERS-01 | Phase 3 | Complete |
| PERS-02 | Phase 3 | Complete |
| PERS-03 | Phase 3 | Complete |
| AUTH-01 | Phase 3 | Complete |
| AUTH-02 | Phase 3 | Complete |
| AUTH-03 | Phase 3 | Complete |
| AUTH-04 | Phase 3 | Complete |
| AUTH-05 | Phase 3 | Complete |
| SHAR-01 | Phase 3 | Complete |
| SHAR-02 | Phase 3 | Complete |
| PERF-01 | Phase 4 | Pending |
| PERF-02 | Phase 4 | Pending |
| PERF-03 | Phase 4 | Pending |
| PERF-04 | Phase 4 | Pending |
| OTEL-01 | Phase 4 | Pending |
| OTEL-02 | Phase 4 | Pending |
| SDK-01 | Phase 4 | Pending |
| SDK-02 | Phase 4 | Pending |
| IDE-01 | Phase 5 | Pending |
| IDE-02 | Phase 5 | Pending |
| IDE-03 | Phase 5 | Pending |
| FETEST-01 | Phase 5 | Pending |
| FETEST-02 | Phase 5 | Pending |
| FETEST-03 | Phase 5 | Pending |
| GROW-01 | Growth backlog | Deferred |
| GROW-02 | Growth backlog | Deferred |
| GROW-03 | Growth backlog | Deferred |
| GROW-04 | Growth backlog | Deferred |
| RCO-01 | v1.1 (real-code obs) | Pending |
| RCO-02 | v1.1 (real-code obs) | Pending |
| RCO-03 | v1.1 (real-code obs) | Pending |
| RCO-04 | v1.1 (real-code obs) | Complete (Phase 07 Plan 03; CR-01 zero-loss gap closed Plan 04) |
| RCO-05 | v1.1 (real-code obs) | Complete (Phase 07 Plan 02) |
| RCO-06 | v1.1 (real-code obs) | Backend half done (Phase 08 Plan 01: hierarchy reconstruction + parent/scope propagation; ProjectionService/CoroutineTree now populate). FE live-render lands in Wave-3 plan 08-03 |
| RCO-07 | v1.1 (real-code obs) | Backend done (Phase 08 Plan 02: MetricsProjection active/peak/throughput/dispatcher-util/leaks + tenant-scoped GET /api/sessions/{id}/metrics). FE metrics panel + leak list lands in Wave-3 plan 08-03 |
| FE-ALIGN | Phase 08.5 | Complete (Phase 08.5 Plans 01+02: LiveDockPanel IDE-dock live view with un-buried metric tiles + single amber leak list; inline compact-chips→expand source panel, live Drawer retired, LOCKED copyFileLine preserved) |
| ONB-01 | Phase 08.5 | Complete (Phase 08.5 Plan 03: client-side LIVE/DEMO derivation + badged SessionsSidebar-as-home + 3-step ConnectWizard with polled auto-resolve) |
**Coverage:**

- v1 requirements: 39 total
- v1.1 requirements (real-code observability): 7 (RCO-01..07)
- Mapped to phases: 39 + 7 (v1.1 milestone)
- Unmapped: 0 ✓

*Note: SHAR-01/SHAR-02 depend on PERS-* (share tokens need the DB / shares table per ADR-019). They are mapped to Phase 3 alongside persistence so the dependency resolves within the phase.*

---
*Requirements defined: 2026-06-11*
*Last updated: 2026-06-24 — added RCO-01..07 (real-code observability, milestone v1.1) via /gsd-explore*
