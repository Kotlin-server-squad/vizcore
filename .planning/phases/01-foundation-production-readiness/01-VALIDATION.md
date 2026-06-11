---
phase: 1
slug: foundation-production-readiness
status: draft
nyquist_compliant: false
wave_0_complete: false
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

> Filled in by the planner — every task gets a row. Key planned regression checks (from SPEC acceptance + RESEARCH Validation Architecture):

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| TBD | TBD | TBD | FIX-01 | — | N/A | integration | scenario via REST → `/events` 200, SSE ≥1 event, 0 SerializationException | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | FIX-01 | — | N/A | unit | registration-completeness: every @Serializable VizEvent subtype resolvable in polymorphic scope | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | FIX-02 | — | N/A | component | ValidationPanel renders captured real {sessionId, results[], timing} JSON without throwing | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | FIX-03 | — | N/A | integration | exception scenario → failing-child==FAILED, normal-child==CANCELLED | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | FIX-04 | — | N/A | integration | cancellation scenario → child-to-be-cancelled==CANCELLED, normal-child==COMPLETED | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | FND-02/03 | — | N/A | integration | emit > maxEvents → `store.all().size <= maxEvents` | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | PROD-01 | — | health leaks no secrets | integration | GET /api/health, /api/live, /api/ready, /health all 200 + version non-empty | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | PROD-05 | — | N/A | integration | /metrics exposes all 7 ADR-020 metric names after scenario run | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] Backend integration-test fixture that runs a scenario via real routes and reads the session snapshot (shared by FIX-01/03/04, FND-03 checks)
- [ ] Captured real validation-response JSON fixture for the frontend component test (from the audit's `/tmp/vz-validate.json` shape)

*Existing infrastructure (JUnit 5 + Ktor Test Host, Vitest) covers the rest.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Prod container logs are JSON-formatted | PROD-02 | Requires Docker image build + run | `docker compose -f docker-compose.prod.yml up backend`, observe stdout is JSON lines |
| Events/Channels tabs render in browser | FIX-01 (UI effect) | Visual confirmation | Run channel scenario, open session, confirm Events tab populated + Channels tab mounts |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
