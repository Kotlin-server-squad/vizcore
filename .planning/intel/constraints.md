# Constraints (from SPECs)

Synthesized from `docs/planning/IMPLEMENTATION_ANALYSIS.md` — a binding technical specification covering 13 feature areas with concrete file paths, data models, endpoint schemas, dependency versions, and effort estimates. Each entry is a constraint the implementation must satisfy.

source (all entries): docs/planning/IMPLEMENTATION_ANALYSIS.md

---

## CON-production-readiness
- type: nfr
- Health endpoint (`GET /api/health` + `/live` + `/ready`) returning `HealthStatus` with component checks (sessionManager, memory), uptime, version. Logging profiles (dev/prod Logback), CORS read from `application.yaml`/env, OpenAPI descriptions, bounded event store, Micrometer metrics enhancement.
- est: 3-4 days. Concrete files: HealthRoutes.kt, Routing.kt, logback-{dev,prod}.xml, HTTP.kt, Monitoring.kt.

## CON-storage-abstractions
- type: api-contract
- `SessionStore` / `EventStoreInterface` interfaces live in `coroutine-viz-core/.../storage/` with in-memory implementations (`InMemorySessionStore.kt`, `InMemoryEventStore.kt`). This is the `*StoreInterface` seam from ADR-015.
- aligns-with: ADR-015 (the seam is the Accepted, designed-but-unimplemented persistence interface). Consistent — no contradiction.

## CON-jdbc-persistence
- type: schema
- Optional JDBC backend: `JdbcSessionStore.kt`, `JdbcEventStore.kt`, `Tables.kt`, `DatabaseConfig.kt`, `StorageFactory.kt`, `RetentionService.kt`, Flyway migrations `V001__create_sessions_table.sql`, `V002__create_events_table.sql`, `V003__add_indexes.sql`. Exposed ORM + HikariCP; events stored as JSONB; H2 (dev) / PostgreSQL (prod).
- aligns-with: ADR-015. **Unimplemented today** — these files are prescribed targets, not present in the repo (code-verified zero Exposed/JDBC/H2). Build-when-implementing-ADR-015.

## CON-authentication
- type: api-contract
- API-key auth (`ApiKeyStore.kt`, `AuthConfig.kt`, `X-API-Key`) and JWT (`JwtConfig.kt`, `/api/auth/token`, `UserPrincipal` with roles). `/health` and `/openapi` unauthenticated. Tenant isolation via per-user session filtering.
- aligns-with: ADR-016.

## CON-replay-engine
- type: api-contract
- Frontend: `use-replay.ts` hook, `ReplayController.tsx`, integrated into `SessionDetails.tsx`. Client-side only; reconstruction from `events.slice(0, currentIndex+1)`.
- aligns-with: ADR-017.

## CON-export-system
- type: protocol
- `export-utils.ts` — PNG via html2canvas, SVG serialization, WebM via MediaRecorder. Client-side. `package.json` adds html2canvas dependency.
- aligns-with: ADR-018.

## CON-session-sharing
- type: schema
- `shares` table + share endpoints (`POST /api/sessions/:id/share`, `GET /api/shared/:token`, revoke). Requires persistence layer (CON-jdbc-persistence).
- aligns-with: ADR-019. Depends on ADR-015 implementation.

## CON-session-comparison
- type: api-contract
- `ComparisonService.compare(a, b)` returning event-count/duration/thread-utilization deltas; `GET /api/sessions/compare?a=&b=`; side-by-side UI.
- note: Defined by SPEC + ROADMAP (Epic #22); no dedicated ADR.

## CON-opentelemetry
- type: protocol
- OTLP exporter mapping events to spans, batch processor, configurable flush interval, zero overhead when disabled; verify in Jaeger/Zipkin.
- note: SPEC + ROADMAP (Epic #23); no dedicated ADR.

## CON-performance-scaling
- type: nfr
- Bounded event store, per-event-type sampling, event batching, SSE gzip compression, dev-only load-test harness with latency/memory reporting.
- aligns-with: ADR-020.

## CON-intellij-plugin
- type: protocol
- Tool window (JCEF loading the React app), backend auto-detect on :8080, custom run configuration, DebugProbes hybrid, Marketplace publishing.
- aligns-with: ADR-010 (Proposed), ADR-014.

## CON-sdk-cicd
- type: protocol
- Publish `coroutine-viz-core` to GitHub Packages; CLI tool (`coroutine-viz-ci.jar check --config ci-config.yaml`); Gradle task `coroutineVizCheck`; sample app.
- aligns-with: ADR-021.

## CON-frontend-testing
- type: nfr
- Fill missing actor/select/anti-pattern tests; Playwright E2E (`frontend/e2e/`); Chromatic visual regression; Storybook. Target 80%+ coverage.
- aligns-with: ADR-022.
