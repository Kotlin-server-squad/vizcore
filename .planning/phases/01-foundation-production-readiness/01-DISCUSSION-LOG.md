# Phase 1: Foundation & Production Readiness - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-06-11
**Phase:** 1-foundation-production-readiness
**Areas discussed:** De-fork mechanics, FIX-01 serializer mechanism, Logging profile mechanism, Metrics exposure

---

## De-fork mechanics (FND-01)

| Option | Description | Selected |
|--------|-------------|----------|
| Big-bang delete | Delete the whole forked session/ subtree, fix compilation against core, prove parity via test suite + audit's headless REST checks | ✓ |
| Incremental migration | Replace one class at a time over several commits | |
| Diff-first reconciliation | Diff each fork class vs core, port fork-only fixes into core, then delete | |

**User's choice:** Big-bang delete ("go with your picks")
**Notes:** Fork-only behavior discovered during the delete is reconciled into core (core is the single source of truth; CONCERNS.md assessed core as "more complete").

---

## FIX-01 serializer mechanism

| Option | Description | Selected |
|--------|-------------|----------|
| Explicit SerializersModule | One module registering all ~48 event subclasses; add registration-completeness test so unregistered new event types fail CI | ✓ |
| Seal the hierarchy | Compiler-enforced, but forces all event files into one package (large refactor) | |
| Hybrid | Seal per-category parents, register categories | |

**User's choice:** Explicit SerializersModule
**Notes:** Sealing explicitly rejected for this phase due to package-move churn.

---

## Logging profile mechanism (PROD-02)

| Option | Description | Selected |
|--------|-------------|----------|
| JVM flag in Docker | -Dlogback.configurationFile=logback-prod.xml in Dockerfile/compose-prod ENTRYPOINT | ✓ |
| Env-var + conditional config | logback `<if>` blocks keyed on env var (needs janino) | |
| Separate image stage | Prod image copies logback-prod.xml over logback.xml | |

**User's choice:** JVM flag in Docker

---

## Metrics exposure (PROD-05)

| Option | Description | Selected |
|--------|-------------|----------|
| Logs-only | Wire 5 missing ADR-020 metrics into existing LoggingMeterRegistry | |
| + Prometheus /metrics | Same wiring + PrometheusMeterRegistry + scrape endpoint (pre-positions Phase 4 observability) | ✓ |

**User's choice:** + Prometheus /metrics

---

## Todo cross-reference

| Option | Description | Selected |
|--------|-------------|----------|
| Don't fold | "Retire standalone vizcor-be & vizcor-fe" (match score 0.2) stays a standalone todo | ✓ |
| Fold into Phase 1 | Add repo retirement to Phase 1 scope | |

**User's choice:** Don't fold — repo housekeeping, unrelated to Phase 1 code scope.

---

## Claude's Discretion

- Health `version` field source (build-generated vs manifest)
- Ktor module layout for the SerializersModule install
- Metric naming details within ADR-020's set
- Test style (Ktor test host vs integration) per TESTING.md conventions

## Deferred Ideas

- supervisorScope (Registration) + withTimeout (Report) — scenario-polish batch
- "Simulate failure" UI toggle — Phase 2
- VizScope Job + honest success:false on failed runs — scenario-polish batch
- Duration-text + Deep Nesting off-by-one — cosmetic backlog
- `X-Accel-Buffering: no` SSE header — Phase 4

## Process note

Discussion ran as a single consolidated round: the four gray areas were presented with full trade-off tables inline (user request: "i would like to know which steps we can do it"), and the user approved the recommended option for all four plus the todo decision in one reply ("go with your picks"). SPEC.md (01-SPEC.md, 12 requirements) was already locked before this discussion; only HOW-level decisions were taken here.
