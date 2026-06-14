---
phase: 02-user-value-visualization
plan: 05
subsystem: frontend-export
tags: [export, png, svg, json, heroui, dropdown, adr-018, toast]
requires:
  - "toastSuccess/toastError helpers (plan 02-04, frontend/src/lib/toast.ts)"
  - "html2canvas + export-png.ts object-URL download idiom"
provides:
  - "export-svg.ts: findSvgRoot (D-21 auto-detect) + exportToSvg (whitelisted style inlining)"
  - "export-json.ts: exportEventsToJson (normalized event-array download, D-22)"
  - "export-filename.ts: buildExportFilename ({sessionId}-{panel}-{yyyyMMdd-HHmm}.{ext})"
  - "export-png.ts: ADR-018 info-header compositing (D-08)"
  - "ExportMenu.tsx: PNG/SVG(conditional)/JSON/Record dropdown with toast feedback"
affects:
  - "02-07 (SessionDetails mounts ExportMenu in the toolbar)"
  - "02-08 (WebM recording wires ExportMenu's onRecord prop)"
tech-stack:
  added: []
  patterns:
    - "SVG export by computed-style WHITELIST inlining (10 props) — no full computed dump (Pattern 4 / T-02-08)"
    - "D-21 findSvgRoot auto-detect: SVG option only on genuine <svg viewBox> roots, PNG fallback elsewhere (OQ-2)"
    - "Object-URL + temp-anchor + revokeObjectURL-in-finally download idiom reused across PNG/SVG/JSON (T-02-09)"
    - "ExportMenu export handlers route success/error through the 02-04 toast helpers (ADR-018 copy)"
key-files:
  created:
    - frontend/src/lib/export-svg.ts
    - frontend/src/lib/export-svg.test.ts
    - frontend/src/lib/export-json.ts
    - frontend/src/lib/export-json.test.ts
    - frontend/src/lib/export-filename.ts
    - frontend/src/lib/export-filename.test.ts
    - frontend/src/components/export/ExportMenu.tsx
    - frontend/src/components/export/ExportMenu.test.tsx
  modified:
    - frontend/src/lib/export-png.ts
    - frontend/src/lib/export-png.test.ts
decisions:
  - "Replaced the pre-existing foreignObject exportToSvg(HTMLElement) with the plan's D-21 findSvgRoot + exportToSvg(SVGSVGElement) whitelist approach — no production code imported the old version, only its test"
  - "Record item kept enabled-but-no-op (onRecord placeholder) when supported; disabled+tooltip only when MediaRecorder/codec unsupported (D-25) — 02-08 wires the real pipeline"
metrics:
  duration: "~12 min"
  tasks_completed: 2
  files_changed: 10
  completed_date: "2026-06-14"
---

# Phase 02 Plan 05: Export Surface (PNG/SVG/JSON + ExportMenu) Summary

Delivered the ADR-018 export surface: the PNG export now composites the D-08 info header (session name / ISO timestamp / event count); a new standalone style-inlined SVG export auto-detects genuine `<svg>` panels (D-21); a JSON event export downloads the normalized event array (D-22); and an `ExportMenu` HeroUI dropdown wires PNG / conditional-SVG / JSON / disabled-Record items with success/error toasts — ready for SessionDetails (02-07) to mount and 02-08 to wire recording.

## What Was Built

- **PNG info header (D-08)** (`frontend/src/lib/export-png.ts`): added an optional `PngHeaderInfo` param. When provided, `buildPngHeader` prepends a styled header band (session name + ISO timestamp + event count) to the captured element before the html2canvas pass and removes it in a `finally` so the live DOM is untouched. Existing call sites (`ExportButton`) keep working — the header is opt-in.
- **SVG export (EXPT-02 / D-21)** (`frontend/src/lib/export-svg.ts`): `findSvgRoot(el)` returns the element if it is an `<svg>`, else the first `svg[viewBox], svg[width]` descendant, else `null` (OQ-2: the HTML/CSS coroutine graph has none → PNG fallback). `exportToSvg(svg, filename?)` deep-clones, inlines a **10-prop WHITELIST** of computed styles (fill/stroke/stroke-width/stroke-dasharray/color/opacity/font-family/font-size/font-weight/transform — Pattern 4, avoids T-02-08 multi-MB bloat), sets `xmlns` + explicit dimensions from `getBoundingClientRect`, adds the ADR-018 metadata comment, and downloads `image/svg+xml` via the reused object-URL idiom.
- **JSON export (D-22)** (`frontend/src/lib/export-json.ts`): `exportEventsToJson(events, filename?)` pretty-prints the event array and downloads an `application/json` blob.
- **Filename builder** (`frontend/src/lib/export-filename.ts`): `buildExportFilename(sessionId, panel, ext, date?)` returns the UI-SPEC §4 locked `{sessionId}-{panel}-{yyyyMMdd-HHmm}.{ext}` (local-time stamp, zero-padded), typed to `ExportPanel`/`ExportExt`.
- **ExportMenu (ADR-018)** (`frontend/src/components/export/ExportMenu.tsx`): HeroUI `Dropdown` mirroring the ReplayController idiom. Trigger `Button size="sm" variant="flat"` + FiDownload "Export". Items top→bottom: "Export view as PNG" (FiImage, always), "Export graph as SVG" (FiCode, **only when `findSvgRoot` non-null**), "Export events as JSON" (FiFileText, always), divider, "Record replay as video" (FiVideo, always — **disabled with the D-25 tooltip** when `MediaRecorder`/codec unsupported via the vp9→vp8→webm cascade). Each handler builds the locked filename, runs the lib export, and surfaces `toastSuccess('Exported {filename}')` / `toastError('Export failed — {reason}…')`. Items disabled while an export runs. An `onRecord?` prop is exposed for plan 02-08 to wire without restructuring.

## Tasks Completed

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | PNG info header, SVG export, JSON export, filename builder (lib layer) | 0261d49 |
| 2 | ExportMenu dropdown PNG/SVG(conditional)/JSON + disabled Record + toasts | 5463a47 |

## Verification

- `cd frontend && pnpm test export-png export-svg export-json export-filename ExportMenu --run` — **33 tests green** (5 files).
  - export-png: header composited before capture + removed after (D-08); no header when omitted.
  - export-svg: `findSvgRoot` returns null on the HTML/CSS coroutine graph (OQ-2), the element on `<svg>`, a descendant on a wrapped panel; `exportToSvg` inlines whitelisted styles, sets xmlns + dimensions, emits the ADR-018 comment, downloads `image/svg+xml`, revokes the URL.
  - export-json: downloads `application/json` with the round-tripped array (D-22).
  - export-filename: matches the locked pattern with zero-padding for all panel/ext combos.
  - ExportMenu: PNG/JSON/Record always render; SVG conditional on `findSvgRoot`; Record disabled+tooltip when `MediaRecorder` undefined; PNG click → exportToPng + toastSuccess; rejection → toastError; JSON click → exportEventsToJson + toastSuccess; `onRecord` fires on Record press.
- `cd frontend && pnpm tsc --noEmit` — **clean** (no `any` in source; `any` only in test mock plumbing with eslint-disable).
- `pnpm eslint` on all created/modified files — **clean**.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Replaced the stale foreignObject `exportToSvg` with the plan's D-21 contract**
- **Found during:** Task 1
- **Issue:** A pre-existing `frontend/src/lib/export-svg.ts` exported `exportToSvg(element: HTMLElement)` using a `<foreignObject>` HTML-wrap approach with a **full computed-style dump** (every ~300 props) — directly violating threat T-02-08 (style-inlining bloat) and lacking the `findSvgRoot` auto-detect the plan and D-21 require. No production code imported it (only its own test).
- **Fix:** Replaced the module with the plan's spec — `findSvgRoot` (D-21 auto-detect) + `exportToSvg(svg: SVGSVGElement)` with the 10-prop whitelist (Pattern 4). Rewrote `export-svg.test.ts` to the new `SVGSVGElement` contract (real `createElementNS` SVG, stubbed `getBoundingClientRect`, Blob-capture for serialized output + null-root assertion for the coroutine graph).
- **Files modified:** frontend/src/lib/export-svg.ts, frontend/src/lib/export-svg.test.ts
- **Commit:** 0261d49

## Authentication Gates

None.

## Threat Surface

All threat-register mitigations from the plan's `<threat_model>` are honored: T-02-07 (tainted-canvas SecurityError → ExportMenu catches and routes to the ADR-018 error toast); T-02-08 (whitelist inlining, 10 props, no full dump); T-02-09 (object-URL revoked in `finally` across all three exporters). No new security surface introduced — exports are fully client-side, no server attack surface, no new packages (T-02-SC accept holds).

## Notes

- The "graph view" SVG question (OQ-2) is resolved exactly as the research prescribed: `findSvgRoot` returns null for `CoroutineTreeGraph` (HTML/CSS), so the SVG option hides there and PNG is the fallback; SVG export attaches only to genuine `<svg viewBox>` panels (DeadlockVisualization/FlowParticlePath/SemaphoreGauge/JobStatusDisplay). A unit test pins this so the verifier does not fail on a literal "graph as SVG" reading.
- `ExportMenu` is not yet mounted — plan 02-07 (SessionDetails) mounts it in the toolbar and supplies `getPanelEl`/`sessionId`/`sessionName`/`events`/`panel`. Plan 02-08 wires `onRecord` to the WebM recording pipeline.

## Self-Check: PASSED

All 10 created/modified files exist on disk; Task commits 0261d49 and 5463a47 confirmed present in git history.
