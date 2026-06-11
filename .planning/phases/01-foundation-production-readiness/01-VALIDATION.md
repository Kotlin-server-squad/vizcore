---
phase: 1
slug: foundation-production-readiness
status: draft
nyquist_compliant: true
wave_0_complete: true
created: 2026-06-11
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Backend: JUnit 5 + Ktor Test Host (Gradle); Frontend: Vitest + Testing Library |
| **Config file** | `backend/build.gradle.kts` / `frontend/vitest.config.ts` |
| **Quick run command** | `cd backend && ./gradlew test --tests '<changed test class>'` / `cd frontend && pnpm test -- --run <file>` |
| **Full suite command** | `cd backend && ./gradlew test` and `cd frontend && pnpm test -- --run` |
| **Estimated runtime** | ~60–120 seconds (backend), ~30 seconds (frontend) |

---

## Sampling Rate

- **After every task commit:** Run the targeted quick command for the touched module
- **After every plan wave:** Run both full suite commands
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

> Filled in by the planner — every code-producing task gets a row, sourced directly from the five PLAN.md task `<verify><automated>` commands.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 01-01-T1 | 01-01 | 1 | FIX-01 | T-01-01 | polymorphic module resolves every subtype (no stream-kill) | unit | `cd backend && ./gradlew test --tests "*.VizEventSerializersModuleTest"` | ✅ created in 01-01-T1 | ⬜ pending |
| 01-01-T2 | 01-01 | 1 | FIX-03, FIX-04 | T-01-02 | failure cause-type surfaced, no PII | integration | `cd backend && ./gradlew test --tests "*.ExceptionScenarioRegressionTest" --tests "*.CancellationScenarioRegressionTest"` | ✅ created in 01-01-T2 | ⬜ pending |
| 01-01-T3 | 01-01 | 1 | FIX-01 | T-01-01 | SSE/`/events` serialize without SerializationException | integration | `cd backend && ./gradlew test --tests "*.SerializersModuleIntegrationTest" --tests "*.SseStreamTest"` | ✅ new + SseStreamTest un-cast in 01-01-T3 | ⬜ pending |
| 01-02-T1 | 01-02 | 1 | FIX-02 | T-01-04 | types match backend contract (no `.length` on undefined) | type-check | `cd frontend && pnpm exec tsc --noEmit` | ✅ types in api.ts | ⬜ pending |
| 01-02-T2 | 01-02 | 1 | FIX-02 | T-01-04 | filter-guarded render stops error-boundary crash | component | `cd frontend && pnpm exec tsc --noEmit && pnpm test -- --run ValidationPanel` | ✅ ValidationPanel adapted | ⬜ pending |
| 01-02-T3 | 01-02 | 1 | FIX-02 | T-01-04 | component test feeds real backend response shape | component | `cd frontend && pnpm test -- --run ValidationPanel && pnpm test -- --run` | ✅ fixture updated in 01-02-T3 | ⬜ pending |
| 01-03-T1 | 01-03 | 2 | FND-01 | T-01-08 | fork removed; compiles against core only | compile + suite | `cd backend && ./gradlew compileKotlin && ./gradlew test` | ✅ ForkDeletionTest guard (01-03-T3) | ⬜ pending |
| 01-03-T2 | 01-03 | 2 | FND-02 | T-01-06, T-01-07 | bounded store wired; `toIntOrNull() ?: 10_000` safe default | smoke | `cd backend && ./gradlew test --tests "*.BoundedStoreWiringTest"` | ✅ created in 01-03-T2 | ⬜ pending |
| 01-03-T3 | 01-03 | 2 | FND-03 | T-01-06, T-01-08 | cap holds (`store.all().size <= maxEvents`); fork cannot return | integration + static | `cd backend && ./gradlew test --tests "*.BoundedStoreRegressionTest" --tests "*.ForkDeletionTest"` | ✅ created in 01-03-T3 | ⬜ pending |
| 01-04-T1 | 01-04 | 3 | PROD-01 | T-01-09 | health surfaces no secrets/PII | integration | `cd backend && ./gradlew test --tests "*.HealthRoutesTest"` | ✅ HealthRoutesTest extended | ⬜ pending |
| 01-04-T2 | 01-04 | 3 | PROD-03 | T-01-10 | CORS origins from config, not wildcard literal | unit | `cd backend && ./gradlew test --tests "*.CorsConfigTest"` | ✅ created in 01-04-T2 | ⬜ pending |
| 01-04-T3 | 01-04 | 3 | PROD-04 | T-01-11 | spec matches served shape (dev-only validator) | spec lint | `cd backend && npx --yes @redocly/cli@latest lint src/main/resources/openapi/documentation.yaml` | ✅ documentation.yaml updated | ⬜ pending |
| 01-05-T1 | 01-05 | 3 | PROD-02 | T-01-SC | blocking-human Maven legitimacy check before install | manual gate | checkpoint:human-verify (blocking-human) — verify coordinates on Maven Central | n/a (checkpoint) | ⬜ pending |
| 01-05-T2 | 01-05 | 3 | PROD-02 | T-01-13 | prod log profile selected; logstash dep present | compile + grep | `cd backend && ./gradlew compileKotlin && grep -q 'logstash-logback-encoder' build.gradle.kts && grep -q 'logback.configurationFile' Dockerfile && echo OK` | ✅ build.gradle.kts + Dockerfile | ⬜ pending |
| 01-05-T3 | 01-05 | 3 | PROD-05 | T-01-12 | all 7 ADR-020 metrics present; core stays Micrometer-free | integration | `cd backend && ./gradlew test --tests "*.MetricsWiringTest"` | ✅ MetricsWiringTest extended | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [x] Backend integration-test fixture that runs a scenario via real routes and reads the session snapshot (shared by FIX-01/03/04, FND-03 checks) — **covered by Plan 01-01 Task 3** (`SerializersModuleIntegrationTest` establishes the `testApplication { application { module() } }` create-session → run-scenario → read-snapshot boilerplate; reused by the FIX-03/FIX-04 regression tests in 01-01 Task 2 and the bounded-store regression in 01-03 Task 3, all building on the existing `SessionRoutesTest`/`HealthRoutesTest` boilerplate)
- [x] Captured real validation-response JSON fixture for the frontend component test (from the audit's `/tmp/vz-validate.json` shape) — **covered by Plan 01-02 Task 3** (the `ValidationPanel.test.tsx` fixture is replaced with the real `{sessionId, results[], timing}` `ValidationResponse` shape)

*Existing infrastructure (JUnit 5 + Ktor Test Host, Vitest) covers the rest. No standalone Wave 0 plan is required: the two fixtures above are produced as the first integration/component tasks within Plans 01-01 and 01-02 respectively, before the downstream waves consume them.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Prod container logs are JSON-formatted | PROD-02 | Requires Docker image build + run | `docker compose -f docker-compose.prod.yml up backend`, observe stdout is JSON lines |
| Events/Channels tabs render in browser | FIX-01 (UI effect) | Visual confirmation | Run channel scenario, open session, confirm Events tab populated + Channels tab mounts |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references (both fixtures mapped to 01-01-T3 and 01-02-T3)
- [x] No watch-mode flags
- [x] Feedback latency < 120s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved (support-document finalization complete; all 14 code-producing tasks mapped to automated commands; Wave 0 fixtures resolved within Plans 01-01/01-02)
