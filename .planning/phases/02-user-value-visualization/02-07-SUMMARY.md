---
phase: 02-user-value-visualization
plan: 07
subsystem: ui
tags: [react, replay, sse, framer-motion, heroui, time-travel, projections]

# Dependency graph
requires:
  - phase: 02-user-value-visualization (02-03)
    provides: projectCoroutines/projectThreadActivity client projections + useReplay 50–2000ms clamp
  - phase: 02-user-value-visualization (02-04)
    provides: HeroUI 2.7 toast + ToastProvider at root
  - phase: 02-user-value-visualization (02-05)
    provides: ExportMenu (PNG/SVG/JSON export)
  - phase: 01-foundation (01-15)
    provides: Phase-1 SSE hardening (seenSeqsRef dedup, bounded fatal-retry, max-wait debounce)
provides:
  - Mounted, user-visible replay (time-travel) experience in SessionDetails
  - Completed ReplayController (Stop/FastForward/Rewind + ADR-017 keyboard set + recording-state slot)
  - LiveDataNotice (D-17) banner for projection-backed tabs
  - replayActive gate on useEventStream SSE invalidation (event buffering without panel jitter)
  - ExportMenu mounted in the SessionDetails toolbar
affects: [02-08 (WebM recording — consumes the recording-state slot + mounted controller)]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Frozen-snapshot replay: useReplay over a snapshot captured at replay entry; panels read visibleEvents while live SSE keeps appending in the background"
    - "Gated cache side-effect: a replayActive ref mirrors the prop so the SSE listener suppresses invalidation/refetch without tearing down the EventSource"
    - "Projection-backed panel data source: panelEvents/panelCoroutines switch between live session state and projected visibleEvents based on replayActive"

key-files:
  created:
    - frontend/src/components/replay/LiveDataNotice.tsx
  modified:
    - frontend/src/components/replay/ReplayController.tsx
    - frontend/src/hooks/use-event-stream.ts
    - frontend/src/components/SessionDetails.tsx

key-decisions:
  - "replayActive mirrored into a ref so the SSE event listener reads the live value without re-subscribing the EventSource (no connection churn on toggle)"
  - "On replay exit, exactly one invalidation flush is performed (stale debounce timer cancelled first) so buffered events apply to the now-live panels (D-04)"
  - "Recording-state cluster (red dot + m:ss elapsed + Stop) lives in the controller chrome and is props-driven; the 02-08 scripted pipeline drives it (D-08/D-23)"
  - "Both the useEventStream invalidation effect AND the local SessionDetails debounced refetch are gated behind !replayActive (D-02)"

patterns-established:
  - "Frozen-snapshot replay with background event buffering and a clickable new-events badge that exits+applies+forgets the cursor"
  - "Phase-1 SSE hardening preserved by gating only the cache side effect, never the connect/retry/dedup machinery"

requirements-completed: [RPLY-01, RPLY-02, RPLY-03]

# Metrics
duration: ~16min
completed: 2026-06-14
---

# Phase 2 Plan 07: Replay Integration Summary

**Mounted the full time-travel replay experience in SessionDetails — sticky ReplayController with Stop/FastForward + ADR-017 keyboard shortcuts, projected event-derived panels (visibleEvents via projectCoroutines/projectThreadActivity), gated SSE buffering with a clickable new-events badge, dim-future/scrub-snap, LiveDataNotice on projection tabs, and the ExportMenu in the toolbar.**

## Performance

- **Duration:** ~16 min
- **Started:** 2026-06-14T13:46:30Z (approx, after 02-06 completion)
- **Completed:** 2026-06-14T13:57:40Z (last task commit)
- **Tasks:** 3
- **Files modified:** 4 (1 created, 3 modified) + 3 test files

> **Closeout note:** All three tasks were implemented and committed normally by the executor (commits at 13:51, 13:52, 13:57 on 2026-06-14). The API socket dropped before the executor could write this SUMMARY and update the tracking files. **No implementation work was lost** — every task commit is present in git history (verified below), and the orchestrator's verification (`pnpm test ... --run` → 6 files / 59 tests passed; `pnpm tsc --noEmit` clean) passed before the drop. This closeout was performed by a continuation agent that re-derived the SUMMARY from the committed diffs; it created **no new feat commits**.

## Accomplishments
- Completed `ReplayController`: Stop (FiSquare → seek0+pause), Jump-to-end (seek last), Rewind-to-start, the full ADR-017 keyboard set (Space/←/→/Home/End/1–4) suppressed in form fields with Space preventDefault, and a props-driven recording-state cluster that disables playback controls while recording (02-08 pipeline drives it).
- Gated `useEventStream` SSE invalidation behind a `replayActive` flag (mirrored in a ref): events keep appending for the new-events badge but the invalidation/refetch side effect is suppressed during replay, with exactly one flush on exit — Phase-1 dedup/backoff/max-wait machinery left untouched.
- Mounted replay in `SessionDetails`: replay toggle (frozen snapshot at entry → seek-to-end + paused), sticky controller bar (`top-16 z-30`) above the tabs, REPLAY chip + clickable "N new events" badge, panel data source switched to projected `visibleEvents`, LiveDataNotice on projection-backed tabs, and the ExportMenu in the toolbar.
- Created `LiveDataNotice` (D-17) banner for projection-backed tabs during replay.

## Task Commits

Each task was committed atomically:

1. **Task 1: Complete ReplayController + LiveDataNotice** — `6df64be` (feat, TDD)
2. **Task 2: Gate SSE invalidation during replay (D-02/D-04)** — `9c30abf` (feat, TDD)
3. **Task 3: Mount replay in SessionDetails (RPLY-01/02/03, D-01..18)** — `171a436` (feat, TDD)

**Plan metadata:** this closeout commit (docs: complete replay integration plan)

_Note: TDD tasks combined test+impl into single per-task feat commits in this run._

## Files Created/Modified
- `frontend/src/components/replay/LiveDataNotice.tsx` (created) — D-17 "live data — not replayed" banner (FiInfo + locked copy + default-100 classes)
- `frontend/src/components/replay/ReplayController.tsx` (modified) — Stop/FastForward/Rewind buttons, ADR-017 keyboard shortcuts, recording-state cluster slot
- `frontend/src/hooks/use-event-stream.ts` (modified) — `replayActive` param gating the invalidation/refetch side effect; one flush on exit
- `frontend/src/components/SessionDetails.tsx` (modified) — replay toggle, sticky controller, REPLAY chip + new-events badge, projected panel data source, LiveDataNotice, ExportMenu mount, dim-future/scrub-snap
- Test files: `ReplayController.test.tsx`, `use-event-stream.test.ts`, `SessionDetails.test.tsx`

## Decisions Made
- `replayActive` mirrored into a ref so the SSE listener reads the live value without re-subscribing the EventSource (no connection churn on toggle).
- On replay exit, exactly one invalidation flush is performed (stale debounce timer cancelled first) so buffered events apply to now-live panels (D-04).
- Both the `useEventStream` invalidation effect and the local SessionDetails debounced refetch are gated behind `!replayActive` (D-02).
- The recording-state cluster is props-driven and lives in the controller chrome (D-08/D-23); the 02-08 scripted pipeline wires it.

## Deviations from Plan
None - plan executed exactly as written. (TDD test+impl were combined into single per-task feat commits rather than separate test→feat commits; behavior and coverage match the plan's acceptance criteria.)

## Issues Encountered
The executor's API socket dropped after the final task commit (`171a436`) but before SUMMARY/tracking writes. Resolved by this continuation agent performing the closeout from committed git history — no source re-implementation and no new feat commits. Orchestrator verification (6 files / 59 tests passed, `tsc --noEmit` clean) had already passed prior to the drop.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- The mounted ReplayController + props-driven recording-state slot are ready for **02-08** (WebM recording) to wire the scripted record pipeline.
- ExportMenu is mounted; its Record item remains a no-op placeholder until 02-08 wires recording (per 02-05 decision).

## Verification Evidence
- `cd frontend && pnpm test ReplayController use-event-stream SessionDetails --run` → 6 files / 59 tests passed.
- `cd frontend && pnpm tsc --noEmit` → clean.
- Phase-1 SSE tests (dedup/backoff/max-wait/zero-event) remain green (no regression).

## Self-Check: PASSED

Files on disk:
- FOUND: frontend/src/components/replay/LiveDataNotice.tsx
- FOUND: frontend/src/components/replay/ReplayController.tsx
- FOUND: frontend/src/hooks/use-event-stream.ts
- FOUND: frontend/src/components/SessionDetails.tsx

Commits present:
- FOUND: 6df64be (Task 1)
- FOUND: 9c30abf (Task 2)
- FOUND: 171a436 (Task 3)

---
*Phase: 02-user-value-visualization*
*Completed: 2026-06-14*
