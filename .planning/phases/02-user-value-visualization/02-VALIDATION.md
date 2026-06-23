---
phase: 2
slug: user-value-visualization
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-06-13
---

# Phase 2 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Derived from 02-RESEARCH.md §Validation Architecture.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework (frontend)** | Vitest 4.1 + jsdom + @testing-library/react 16 + MSW (wire-shape mocks) |
| **Framework (backend)** | JUnit 5 (Jupiter) + Ktor Test Host + kotlinx-coroutines-test |
| **Config file** | `frontend/vitest.config.ts` · `backend/build.gradle.kts` (`useJUnitPlatform()`) |
| **Quick run command** | `cd frontend && pnpm test <pattern>` · `cd backend && ./gradlew test --tests "*X*"` |
| **Full suite command** | `cd frontend && pnpm test:coverage` · `cd backend && ./gradlew test` |
| **Estimated runtime** | frontend ~20–40s · backend ~30s |

**Browser-API note:** `MediaRecorder`, `HTMLCanvasElement.captureStream`, `CanvasCaptureMediaStreamTrack.requestFrame`, and `html2canvas` are absent in jsdom. Recording and export logic MUST live in pure pipeline modules (`lib/record-replay.ts`, `lib/export-svg.ts`) so they are mock-testable. Precedent: `frontend/src/lib/export-png.test.ts` mocks `html2canvas` via `vi.mock`. Real-codec/real-canvas validation defers to Playwright E2E (FETEST-02, **Phase 5**).

---

## Sampling Rate

- **After every task commit:** Run the area-scoped quick command (`pnpm test <changed-area>` or `./gradlew test --tests "*Area*"`).
- **After every plan wave:** Run the full suite for the touched side(s).
- **Before `/gsd-verify-work`:** `pnpm test:coverage` AND `./gradlew test` both green.
- **Max feedback latency:** ~40 seconds.

---

## Per-Task Verification Map

> Task IDs are indicative (final IDs assigned by the planner). Each row maps a phase requirement to an automated check.

| Plan area | Wave | Requirement | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|-----------|------|-------------|-----------------|-----------|-------------------|-------------|--------|
| Backend: compare rename + thread delta | 1 | CMPR-01 | unknown id ⇒ 404 (D-12), not silent-create | integration | `./gradlew test --tests "*Comparison*"` | ⚠ rename + add thread-delta + 404 cases | ⬜ pending |
| Backend: fork reconciliation | 1 | (folded todo) | TimingAnalyzer ns→ms lives in core | static + unit | `./gradlew test --tests "*ForkDeletionTest*"` `*TimingAnalyzer*` | ✅ extend to events/+checksystem/ | ⬜ pending |
| Frontend: event→snapshot projection | 2 | RPLY-01 (OQ-1) | projection == server snapshot (oracle) | unit | `pnpm test projections` | ❌ W0 | ⬜ pending |
| Frontend: HeroUI 2.7 toast upgrade | 2 | EXPT (feedback) | existing HeroUI panels unbroken | full suite | `pnpm test` | ✅ existing suite is the guard | ⬜ pending |
| Frontend: replay engine + controller | 3 | RPLY-01, RPLY-02 | clamp 50–2000ms; Stop/FastForward/keyboard | unit + component | `pnpm test use-replay ReplayController` | ❌ W0 | ⬜ pending |
| Frontend: replay panel sync + dim | 3 | RPLY-03, D-16/D-18 | snap-on-scrub, dim future | component | `pnpm test replay` | ❌ W0 | ⬜ pending |
| Frontend: SSE replay buffering | 3 | RPLY-01, D-02 | invalidation gated, EventSource kept open | unit | `pnpm test use-event-stream` | ✅ extend existing | ⬜ pending |
| Frontend: PNG + info header | 3 | EXPT-01, D-08 | download triggers; header present | unit (mock html2canvas) | `pnpm test export-png` | ✅ extend | ⬜ pending |
| Frontend: SVG export | 3 | EXPT-02 | inlines styles; hidden when no `<svg>` root | unit | `pnpm test export-svg` | ❌ W0 | ⬜ pending |
| Frontend: JSON export | 3 | D-22 | serializes normalized event array | unit | `pnpm test export` | ❌ W0 | ⬜ pending |
| Frontend: /compare route + tree pair | 3 | CMPR-02, D-10/D-19/D-20 | route mounts; delta badges; selection sync | component | `pnpm test compare` | ❌ W0 | ⬜ pending |
| Frontend: WebM recording pipeline | 4 | EXPT-02 | codec cascade; null⇒disabled; abort-on-hidden; duration estimate | unit (mock MediaRecorder) | `pnpm test record-replay` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `frontend/src/lib/projections/*.test.ts` — events→snapshot oracle tests (OQ-1)
- [ ] `frontend/src/hooks/use-replay.test.ts` — playback/seek/speed/clamp
- [ ] `frontend/src/components/replay/ReplayController.test.tsx` — controls + keyboard
- [ ] `frontend/src/lib/export-svg.test.ts` — style inlining + svg-detect
- [ ] `frontend/src/lib/record-replay.test.ts` — codec cascade, duration estimate, abort (mock MediaRecorder/captureStream)
- [ ] `frontend/src/routes/compare.test.tsx` (or component test) — tree pair + selection sync
- [ ] Backend: rename/extend `*Comparison*` test for new path + thread delta + 404; extend `ForkDeletionTest` to events/+checksystem/

*Existing infra covers PNG export and SSE-stream tests — extend, don't recreate.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Real WebM file plays + honors replay speed in a real browser | EXPT-02 | `MediaRecorder`/`captureStream` unavailable in jsdom | Run app, enter replay, click Record, confirm downloaded `.webm` plays start-to-finish at chosen speed (Chrome/Firefox). Safari: video option disabled with tooltip (D-25). Defers to Playwright E2E in Phase 5. |
| Recording capture quality at 2x on a large DOM panel | EXPT-02, D-27 | rendering fidelity is visual | Record the Coroutines panel of a 100+ event session; confirm text legibility and acceptable smoothness. |
| Replay "feel" of the 50–2000ms clamp | RPLY-02 | subjective pacing | Scrub + play a session; confirm playback is neither too fast nor too slow. |

---

## Validation Sign-Off

- [ ] All tasks have automated verify or Wave 0 dependencies (3 manual items above are inherently browser-bound / subjective; covered by Phase 5 E2E)
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 40s
- [ ] `nyquist_compliant: true` set in frontmatter (planner/checker to confirm)

**Approval:** pending
