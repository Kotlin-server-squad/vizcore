# Phase 1: Foundation & Production Readiness - Context

**Gathered:** 2026-06-11
**Status:** Ready for planning

<domain>
## Phase Boundary

Structural repair and runtime correctness of the running server: remove the `backend/src/main` session fork (core classes only, bounded store), fix the four high-severity runtime defects from the 2026-06-11 audits (FIX-01..04), and complete the production surface (health endpoints, logging profiles, CORS test, OpenAPI accuracy, full ADR-020 metrics).

</domain>

<spec_lock>
## Requirements (locked via SPEC.md)

**12 requirements are locked.** See `01-SPEC.md` for full requirements, boundaries, and acceptance criteria.

Downstream agents MUST read `01-SPEC.md` before planning or implementing. Requirements are not duplicated here.

**In scope (from SPEC.md):** SerializersModule polymorphic registration for the full VizEvent hierarchy + ContentNegotiation/SSE wiring; frontend ValidationPanel/ValidationResult adaptation to `{results, timing}`; VizScope cause-type classification; Cancellation scenario targeted cancel; deletion of the backend/src/main session fork; bounded store wiring (application.yaml key, default 10000) + regression test; `/api/health`, `/api/live`, `/api/ready` + `/health` alias; logback prod-profile selection; CORS config test; OpenAPI updates + validator pass; remaining five ADR-020 metrics; regression tests for each FIX using the audit's headless REST checks.

**Out of scope (from SPEC.md):** supervisorScope in User Registration, real withTimeout in Report Generation; "Simulate failure" UI toggle; VizScope Job context + honest `success:false`; Replay/Export/Comparison mounting (Phase 2); persistence/retention/auth/share tokens (Phase 3); sampling/batching/compression/OTEL/SDK (Phase 4); plugin + frontend coverage/E2E (Phase 5); scenario card duration text + Deep Nesting off-by-one (backlog).

</spec_lock>

<decisions>
## Implementation Decisions

### De-fork mechanics (FND-01)
- **D-01:** Big-bang delete — remove the entire forked `backend/src/main/kotlin/com/jh/proj/coroutineviz/session/` subtree in one change, fix compilation against `coroutine-viz-core`, and prove parity with the test suite plus the audit's headless REST checks (scenario run → snapshot shape unchanged).
- **D-02:** Any fork-only behavior discovered during the delete surfaces as compile/test failure and is reconciled *into core* (core is the single source of truth — CONCERNS.md already assessed core as "more complete").

### FIX-01 serializer mechanism
- **D-03:** Explicit `SerializersModule` — one module file registering every `@Serializable` `VizEvent` subclass (all packages: coroutine, job, flow, dispatcher, deferred, channel, sync) in `polymorphic(VizEvent::class) { … }`, installed on the server's Json (ContentNegotiation + SSE serialization path).
- **D-04:** Guard against future drift with a registration-completeness test: reflect over (or enumerate) all `@Serializable` VizEvent subtypes and assert each is resolvable in the polymorphic scope — a newly added event type that isn't registered must fail CI, not production.
- **D-05:** Do NOT seal the hierarchy — sealing would force all event classes into one package (large refactor, deferred).

### Logging profile mechanism (PROD-02)
- **D-06:** JVM flag selection — `-Dlogback.configurationFile=…/logback-prod.xml` set in the Dockerfile/compose-prod ENTRYPOINT. No janino conditional config, no separate image stage. Dev runs remain on the default `logback.xml`.

### Metrics exposure (PROD-05)
- **D-07:** Add a `PrometheusMeterRegistry` and a `/metrics` scrape endpoint alongside the existing `LoggingMeterRegistry`, and wire the five missing ADR-020 metrics into both. This pre-positions Phase 4 observability with ~20 lines + 1 dependency.

### Claude's Discretion
- Health `version` field source (build-generated vs manifest), exact Ktor module layout for the SerializersModule install, metric naming details within ADR-020's set, test-host vs integration-test style per existing TESTING.md conventions.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Locked requirements
- `.planning/phases/01-foundation-production-readiness/01-SPEC.md` — Locked requirements — MUST read before planning

### Audit evidence (current-state ground truth, file:line cited)
- `.planning/VERIFICATION.md` — §Runtime Walkthrough Addendum: RT-01 (VizEvent serialization), RT-02 (validation contract), RT-03 (Events tab), plus FND/PROD verdict matrix
- `.planning/SCENARIO-AUDIT.md` — SC-01 (FAILED unreachable, `VizScope.kt:182`), §3 Cancellation scenario (`ScenarioRunner.kt:117,124`), SC-03/SC-04 systemic findings

### Architecture decisions
- `docs/adr/` ADR-020 — bounded EventStore, EventSampler, RetentionPolicy, the 7-metric Micrometer set, SSE compression (this phase wires the bounded store + metrics; sampler/retention later phases)

### Codebase maps
- `.planning/codebase/CONCERNS.md` — fork inventory + fix approach (delete subtree, resolve against core; core EventStore already `maxEvents = 10_000`)
- `.planning/codebase/TESTING.md` — test conventions for the regression tests
- `.planning/codebase/ARCHITECTURE.md` — event/session layer relationships

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `coroutine-viz-core` session classes: bounded `EventStore` (ArrayDeque + ReentrantReadWriteLock, `maxEvents = 10_000` default), complete `VizSession`, configurable `SessionManager` — the de-fork target already exists and is tested.
- `frontend/src/hooks/use-validation.ts` + `ValidationPanel.tsx` — FIX-02 touches these two plus `types/api.ts`'s `ValidationResult`.
- Captured live responses from the audit (`/tmp` checks documented in VERIFICATION/SCENARIO-AUDIT) define the exact JSON shapes for regression tests.

### Established Patterns
- Wildcard imports from `com.jh.proj.coroutineviz.session` in routes/wrappers currently resolve to the fork — after deletion they resolve to core automatically (same package names), which is what makes big-bang viable.
- `MetricsWiring.kt` already registers 2 gauges — extend the same wiring pattern for the remaining 5 metrics.
- Kotlin conventions: detekt/ktlint, structured concurrency, never GlobalScope (CLAUDE.md).

### Integration Points
- `SerializersModule` installs at Ktor ContentNegotiation config + the SSE route's serialization path (`SessionRoutesKt` SSE handler).
- `SessionManager.configure(maxEvents)` call belongs in `Application` startup, reading the new `application.yaml` key.
- Health routes mount in `Routing.kt`; `/health` alias preserved.
- Prometheus registry joins the existing Micrometer setup in `MetricsWiring.kt`.

</code_context>

<specifics>
## Specific Ideas

- Regression tests should reuse the audit's exact headless checks: run scenario via REST → assert `/events` 200 + event count; exception scenario → `failing-child == FAILED`, `normal-child == CANCELLED`; cancellation scenario → `child-to-be-cancelled == CANCELLED`, `normal-child == COMPLETED`; bounded store → `store.all().size <= maxEvents`.
- FIX-01 acceptance includes an SSE client receiving ≥1 event with zero `SerializationException` in logs.

</specifics>

<deferred>
## Deferred Ideas

- supervisorScope in User Registration + real withTimeout in Report Generation — scenario-semantics polish batch (post-Phase-1)
- "Simulate failure" toggle in Scenario Controls — Phase 2 frontend work
- VizScope Job context + honest `success:false` on failed scenario runs — rides with the scenario-polish batch
- Scenario card duration-text corrections; Deep Nesting "5 levels" off-by-one — cosmetic backlog
- `X-Accel-Buffering: no` SSE header (ADR-020, noted in CONCERNS.md) — Phase 4 SSE/perf work

### Reviewed Todos (not folded)
- "Retire standalone vizcor-be & vizcor-fe, redirect to monorepo" — repo housekeeping, unrelated to Phase 1 code scope; stays a standalone todo.

</deferred>

---

*Phase: 01-foundation-production-readiness*
*Context gathered: 2026-06-11*
