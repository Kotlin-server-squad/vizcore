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

- [ ] **FIX-01**: All `VizEvent` subclasses are registered in a kotlinx.serialization `SerializersModule` polymorphic scope — SSE streaming and `GET /api/sessions/{id}/events` work end-to-end (no `SerializationException`), unblocking the Events tab and the conditional Channels/Flow/Sync/Jobs tabs. (Evidence: VERIFICATION.md RT-01/RT-03, SCENARIO-AUDIT.md SC-03.)
- [x] **FIX-02**: The frontend validation feature consumes the real backend response shape (`{sessionId, results[], timing}`) — Run Validation renders results and never crashes the page. (Evidence: VERIFICATION.md RT-02.) ✓ Completed 01-02
- [ ] **FIX-03**: `VizScope` classifies terminal coroutine state by cause type (not message-contains-label) — a throwing coroutine emits `CoroutineFailed` and renders FAILED, distinct from cancelled victims. (Evidence: SCENARIO-AUDIT.md SC-01.)
- [ ] **FIX-04**: The Cancellation scenario performs a targeted child cancel — `child-to-be-cancelled` ends CANCELLED while `normal-child` ends COMPLETED. (Evidence: SCENARIO-AUDIT.md §3.)

### Production Readiness

- [ ] **PROD-01**: `GET /api/health` (plus `/live`, `/ready`) returns `HealthStatus` with component checks (sessionManager, memory), uptime, and version.
- [ ] **PROD-02**: Logging uses environment-selectable dev/prod Logback profiles; no stray `println`.
- [ ] **PROD-03**: CORS allowed origins/methods are read from `application.yaml`/env, not hardcoded.
- [ ] **PROD-04**: All endpoints and DTOs have OpenAPI descriptions and the generated spec validates.
- [ ] **PROD-05**: Micrometer exposes the full ADR-020 metric set (events emitted/dropped, scenario duration, event-processing duration, plus active-sessions and SSE-client gauges).

### Replay & Time Travel

- [ ] **RPLY-01**: A developer can play, pause, stop, step-forward, and step-back through a session's events from a replay controller, with all panels reflecting the current event index.
- [ ] **RPLY-02**: A developer can select playback speed (0.5x-5x) and scrub to any position via a progress bar.
- [ ] **RPLY-03**: Animations respect the active replay speed and the current event (per ADR-011/027).

### Export & Sharing

- [ ] **EXPT-01**: A developer can export a visualization as PNG (via html2canvas) and download it.
- [ ] **EXPT-02**: A developer can export a graph view as standalone style-inlined SVG, and export a replay as WebM video (MediaRecorder).
- [ ] **SHAR-01**: A developer can create a share token (expiry 1d/7d/30d/never) for a session via `POST /api/sessions/:id/share`.
- [ ] **SHAR-02**: Anyone with a valid token can open a read-only shared view via `GET /api/shared/:token`; tokens are revocable and rate-limited.

### Persistence & Data

- [ ] **PERS-01**: An optional JDBC store (Exposed + HikariCP, H2 dev / PostgreSQL prod) implements the `SessionStoreInterface`/`EventStoreInterface` seam, selectable via `storage.type=database`.
- [ ] **PERS-02**: Sessions and events survive a backend restart when database storage is enabled (Flyway-migrated schema, events stored as JSONB).
- [ ] **PERS-03**: A retention policy (max-age TTL + max-events trim) runs as a background process when persistence is enabled.

### Authentication & Multi-tenancy

- [ ] **AUTH-01**: Non-public routes are wrapped in `authenticatedApi()`; with `API_KEY` set, requests without a valid `X-API-Key` are rejected, while `/health`, `/openapi.json`, and the token endpoint stay open.
- [ ] **AUTH-02**: API keys are compared as SHA-256 hashes (not plaintext), per ADR-016.
- [ ] **AUTH-03**: JWT auth (`/api/auth/token`, HMAC dev / RS256 prod, refresh) issues a `UserPrincipal` with VIEWER/RUNNER/ADMIN roles.
- [ ] **AUTH-04**: Sessions are filtered by authenticated user (tenant isolation) — no cross-tenant reads.
- [ ] **AUTH-05**: Route-level auth enforcement is covered by end-to-end tests (reject-without-key, allow-with-key).

### Performance & Scaling

- [ ] **PERF-01**: Per-event-type sampling (deterministic by hash of sessionId+seq; lifecycle events always pass) is wired into event emission with `X-Sampled` metadata.
- [ ] **PERF-02**: Event batching (configurable size/timeout) and SSE gzip compression — including the `X-Accel-Buffering: no` header — are active for the SSE stream.
- [ ] **PERF-03**: A dev-only load-test harness produces N coroutines × M events/sec and reports latency/memory.
- [ ] **PERF-04**: Rate limiting (or a configurable max-active-session cap) protects session creation and scenario execution.

### Session Comparison

- [ ] **CMPR-01**: `ComparisonService.compare(a, b)` returns event-count, duration, and thread-utilization deltas via `GET /api/sessions/compare?a=&b=`.
- [ ] **CMPR-02**: A developer can view two sessions side-by-side with delta highlights.

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
| FND-01 | Phase 1 | Complete |
| FND-02 | Phase 1 | Complete |
| FND-03 | Phase 1 | Complete |
| PROD-01 | Phase 1 | Pending |
| PROD-02 | Phase 1 | Pending |
| PROD-03 | Phase 1 | Pending |
| PROD-04 | Phase 1 | Pending |
| PROD-05 | Phase 1 | Pending |
| RPLY-01 | Phase 2 | Pending |
| RPLY-02 | Phase 2 | Pending |
| RPLY-03 | Phase 2 | Pending |
| EXPT-01 | Phase 2 | Pending |
| EXPT-02 | Phase 2 | Pending |
| CMPR-01 | Phase 2 | Pending |
| CMPR-02 | Phase 2 | Pending |
| PERS-01 | Phase 3 | Pending |
| PERS-02 | Phase 3 | Pending |
| PERS-03 | Phase 3 | Pending |
| AUTH-01 | Phase 3 | Pending |
| AUTH-02 | Phase 3 | Pending |
| AUTH-03 | Phase 3 | Pending |
| AUTH-04 | Phase 3 | Pending |
| AUTH-05 | Phase 3 | Pending |
| SHAR-01 | Phase 3 | Pending |
| SHAR-02 | Phase 3 | Pending |
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

**Coverage:**

- v1 requirements: 39 total
- Mapped to phases: 39
- Unmapped: 0 ✓

*Note: SHAR-01/SHAR-02 depend on PERS-* (share tokens need the DB / shares table per ADR-019). They are mapped to Phase 3 alongside persistence so the dependency resolves within the phase.*

---
*Requirements defined: 2026-06-11*
*Last updated: 2026-06-11 after brownfield ingest + codebase audit*
