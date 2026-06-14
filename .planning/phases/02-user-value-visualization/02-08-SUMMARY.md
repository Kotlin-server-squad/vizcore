---
phase: 02-user-value-visualization
plan: 08
subsystem: ui
tags: [react, recording, mediarecorder, webm, replay, heroui, export, codec-cascade]

# Dependency graph
requires:
  - phase: 02-user-value-visualization (02-05)
    provides: ExportMenu with the Record item + onRecord prop placeholder + buildExportFilename + export-png/svg download idioms
  - phase: 02-user-value-visualization (02-07)
    provides: Mounted ReplayController (props-driven recording-state cluster) + frozen-snapshot replay in SessionDetails (useReplay + enterReplay/exitReplay)
  - phase: 02-user-value-visualization (02-03)
    provides: useReplay 50–2000ms inter-event clamp (mirrored by estimateDurationMs)
  - phase: 02-user-value-visualization (02-04)
    provides: HeroUI 2.7 toast helpers (toastSuccess/toastError) + ToastProvider at root
provides:
  - Pure React-free WebM recording pipeline (codec cascade, 2x mirror-canvas capture, duration estimate, stop/discard)
  - useRecordReplay scripted glue (seek0→record→auto-stop, >120s confirm, abort-on-hidden, controller Stop discard)
  - RecordConfirmModal (D-26 >120s confirmation)
  - SessionDetails wiring (ExportMenu onRecord → recording; ReplayController recording props; modal)
affects: [Phase 5 (Playwright E2E real-codec validation of the recorded .webm)]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Pure browser-API pipeline behind a small interface (MediaRecorder/captureStream/html2canvas wrapped in record-replay.ts) so the React glue stays mock-testable in jsdom"
    - "captureStream(0) + track.requestFrame() per scripted replay step so a slow html2canvas frame is HELD not dropped (RESEARCH Pitfall 3)"
    - "stop() resolves the built .webm filename so the success toast reflects the exact downloaded name"
    - "Three DoS brakes on the unbounded WebM blob (T-02-15): >120s confirm (D-26), controller Stop discard (D-23), visibilitychange→hidden abort (D-24) — no hard cap by design"

key-files:
  created:
    - frontend/src/hooks/use-record-replay.ts
    - frontend/src/hooks/use-record-replay.test.ts
    - frontend/src/components/replay/RecordConfirmModal.tsx
    - frontend/src/components/replay/RecordConfirmModal.test.tsx
  modified:
    - frontend/src/lib/record-replay.ts
    - frontend/src/components/SessionDetails.tsx
    - frontend/src/components/SessionDetails.test.tsx

key-decisions:
  - "stop() return type changed from Promise<void> to Promise<string> (resolves the .webm filename) so the hook's success toast can show the real downloaded name rather than a reconstructed approximation"
  - "useRecordReplay takes an optional mimeTypeOverride test seam (defaults to pickMimeType()) so the Safari null-codec path is unit-testable without stubbing the global MediaRecorder"
  - "The estimate + auto-stop read whichever events WILL be frozen on entry (live/stored when not yet replaying, the snapshot when already replaying) so a one-click record from the live view records the full timeline"
  - "onRecord is passed to ExportMenu only when canRecord is true (codec supported); ExportMenu's own D-25 codec detection still independently disables the item"

requirements-completed: [EXPT-02]

# Metrics
duration: ~20min
completed: 2026-06-14
---

# Phase 2 Plan 08: WebM Recording Summary

**One-click scripted WebM recording of the active visualization panel — codec-cascade feature-detection (vp9→vp8→bare-webm, null⇒disabled on Safari), a pure mock-testable pipeline (2x mirror canvas + `captureStream(0)`/`requestFrame` capture, 50–2000ms÷speed duration estimate), and the React glue that enters replay → seeks 0 → records while auto-playing → auto-stops at the last event → downloads `.webm`, gated by a >120s confirm modal and aborted-with-discard when the tab is backgrounded — wired into SessionDetails via the ExportMenu Record item.**

> **Continuation note (mid-plan socket drop):** A prior executor implemented and committed **Task 1** (the pure `record-replay.ts` pipeline + its test) as `cd8fc20` before its API connection dropped mid-plan. **Task 2** (the React glue) was only partially on disk — `RecordConfirmModal.tsx`/`.test.tsx` existed but were uncommitted; `use-record-replay.ts`/`.test.ts` and the SessionDetails wiring were missing. This continuation agent reviewed the untracked modal (kept as-is — correct + complete), created the hook + test, wired SessionDetails, and committed Task 2 atomically as `236c255`. **Task 1 was left intact — no re-commit.** No prior work was reverted or duplicated.

## Performance
- **Duration:** ~20 min (continuation segment)
- **Completed:** 2026-06-14
- **Tasks:** 2 (Task 1 by prior executor; Task 2 by this continuation agent)
- **Files:** 4 created, 3 modified (incl. 3 test files)

## Accomplishments
- **Task 1 (prior executor, `cd8fc20`):** Pure `record-replay.ts` — `pickMimeType()` D-25 cascade (null on Safari), `estimateDurationMs()` (Σ clamp(gap,50,2000)/speed, D-26), `createReplayRecorder()` (2x mirror canvas/D-27, `captureStream(0)`+MediaRecorder@2.5Mbps, SVG-fast-path vs html2canvas DOM path → `requestFrame`, `stop()` download + `discard()`). Fully mock-tested.
- **Task 2 (this agent, `236c255`):**
  - `useRecordReplay` glue: scripted `enterReplay→seekTo(0)→createReplayRecorder→start→play`, per-frame `captureFrame()` driven off `currentIndex` changes, auto-`stop()` at the last event + "Saved {filename}" success toast; >120s estimate opens the confirm modal (D-26); `visibilitychange→hidden` aborts with `discard()` + the locked "Recording cancelled — keep the tab visible while recording." toast (D-24); controller Stop → `discard()` + "Recording discarded" (D-23); inert when `pickMimeType()` is null (D-25); elapsed-timer state for the controller cluster.
  - `RecordConfirmModal` (kept from the untracked prior work, verified correct): HeroUI `size="sm"`, locked title/body ("Record replay?" / "This recording will take about {m} min {s} s at {speed}x speed…"), Cancel (`variant="light"`) / Start recording (`color="primary"`).
  - `SessionDetails` wiring: `ExportMenu onRecord={canRecord ? startRecording : undefined}`, `ReplayController` recording props (`isRecording`/`elapsedMs`/`onStopRecording`), `<RecordConfirmModal>` driven by the hook's modal state. All plan-02-07 replay wiring left intact.

## Task Commits
1. **Task 1: Pure WebM recording pipeline** — `cd8fc20` (feat, prior executor, TDD) — left intact, not re-committed
2. **Task 2: record-replay hook + confirm modal + SessionDetails wiring** — `236c255` (feat, this continuation agent)

**Plan metadata:** the `docs(02-08)` closeout commit (SUMMARY + STATE + ROADMAP).

## Decisions Made
- `record-replay.ts` `stop()` now resolves the built `.webm` filename (`Promise<string>`) so the success toast shows the exact downloaded name; the existing pipeline test (which ignores the return value) stays green.
- `useRecordReplay` accepts an optional `mimeTypeOverride` test seam (defaults to `pickMimeType()`), letting the unit test exercise the Safari null-codec path without stubbing the global `MediaRecorder`.
- The estimate + auto-stop read whichever events WILL be frozen on entry (live/stored before replay, the snapshot once replaying), so a one-click record from the live view records the full timeline.
- `onRecord` is only passed to `ExportMenu` when `canRecord` (codec supported); ExportMenu's own D-25 detection independently disables the item as a second guard.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `stop()` returned void, so the success toast could not show the real filename**
- **Found during:** Task 2 (wiring the success toast).
- **Issue:** The plan's success copy is "Saved {filename}", but the `.webm` filename is built inside the pipeline's `stop()` (via `buildExportFilename`). With `stop(): Promise<void>` the hook would have to reconstruct/approximate the name, risking a mismatch with the actually-downloaded file.
- **Fix:** Changed `stop()` to resolve the built filename (`Promise<string>`); the hook awaits it and toasts the exact name. The pipeline test was unaffected (it asserts `mockLink.download`, ignoring the return value).
- **Files modified:** `frontend/src/lib/record-replay.ts`
- **Commit:** `236c255`

No architectural (Rule 4) changes; no auth gates.

## Known Stubs
None. The pipeline, glue, modal, and wiring are fully implemented. Real-codec end-to-end validation (a `.webm` that plays start-to-finish in Chrome/Firefox, the Safari disabled-with-tooltip path, 2x legibility on a 100+ event panel) is intentionally deferred to **Phase 5 Playwright** per `02-VALIDATION.md` — jsdom has no real MediaRecorder/codec, so unit tests mock every browser API (the plan's design).

## Threat Surface
No new surface beyond the plan's `<threat_model>`. The three documented DoS brakes (T-02-15 confirm/Stop/abort) are all implemented; the object-URL is revoked in `finally` (T-02-18); no new packages (T-02-SC — native MediaRecorder/captureStream + existing html2canvas).

## Issues Encountered
The prior executor's API socket dropped mid-plan after committing Task 1. Resolved by this continuation agent: reviewed the untracked Task-2 modal (correct, kept), implemented the missing hook + wiring, and committed Task 2 atomically. No Task 1 re-commit, no reverts.

## Verification Evidence
- `cd frontend && pnpm test record-replay RecordConfirmModal use-record-replay SessionDetails --run` → **4 files / 43 tests passed.**
- `cd frontend && pnpm tsc --noEmit` → **clean.**
- 02-07 replay tests (mocked ExportMenu/ReplayController shims) remain green — the added recording props/modal mock did not regress SessionDetails.

## Next Phase Readiness
- This is **plan 8 of 8** — Phase 02 (user-value-visualization) plans are all complete and ready for phase verification.
- EXPT-02's video tier is delivered; real-codec E2E validation is queued for Phase 5 Playwright.

## Self-Check: PASSED

Files on disk:
- FOUND: frontend/src/hooks/use-record-replay.ts
- FOUND: frontend/src/hooks/use-record-replay.test.ts
- FOUND: frontend/src/components/replay/RecordConfirmModal.tsx
- FOUND: frontend/src/components/replay/RecordConfirmModal.test.tsx
- FOUND: frontend/src/lib/record-replay.ts (modified — stop() resolves filename)
- FOUND: frontend/src/components/SessionDetails.tsx (modified — recording wiring)

Commits present:
- FOUND: cd8fc20 (Task 1 — pure pipeline, prior executor, intact)
- FOUND: 236c255 (Task 2 — hook + modal + wiring, this agent)

---
*Phase: 02-user-value-visualization*
*Completed: 2026-06-14*
