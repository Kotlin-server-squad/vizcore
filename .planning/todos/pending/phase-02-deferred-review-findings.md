---
created: 2026-06-15
source: 02-REVIEW.md
phase: 02-user-value-visualization
status: pending
priority: low
---

# Phase 02 deferred code-review findings

The Phase 02 code review (`.planning/phases/02-user-value-visualization/02-REVIEW.md`)
found 2 blockers + 9 warnings + 6 info. The 2 blockers (CR-01, CR-02) and the
load-bearing warnings (WR-01, WR-07, WR-08, WR-09) were fixed during execution
(commits `7d1df2d`, `e2ce371`, `81851a9`). The following robustness/quality items
were intentionally deferred — none block the phase goal:

## Warnings (deferred)
- **WR-02** `export-svg.ts:50-76` — SVG style inlining by positional child index can
  desync clone vs source if the live tree mutates mid-export. Snapshot the subtree
  or walk only the clone.
- **WR-03** `record-replay.ts:177-196` — WebM SVG rasterization serializes live DOM
  with no whitelist/sanitization (bypasses the T-02-08 mitigation the SVG *export*
  path applies). Route through a shared whitelisted clone+inline helper; strip
  `<foreignObject>`/`<script>`/external hrefs.
- **WR-04** `export-svg.ts:39-44` — `findSvgRoot` uses `el.matches('svg')` + double
  `as unknown as` cast; narrow with `instanceof SVGSVGElement` instead.
- **WR-05** `ExportMenu.tsx:80-82` — `getPanelEl()`/`findSvgRoot` run in render body
  (React anti-pattern, stale SVG availability). Compute on dropdown open; surface a
  `toastError` when `handleSvg` finds no root instead of a silent no-op.
- **WR-06** `use-replay.ts:173-182` — `currentEvent`/`visibleEvents`/`progress` read
  `currentIndex` against a possibly-shrunk events array before the reset effect runs;
  clamp `safeIndex` at read time.

## Info (deferred)
- **IN-01** `ComparisonView.tsx:241-262` — comparison trees fetch full session
  snapshots for coroutine lists only (2 extra round-trips); consider folding into the
  compare payload.
- **IN-02** `ExportMenu.tsx:41-50` vs `record-replay.ts:30-74` — codec-support cascade
  duplicated with divergent fallbacks; export a single `isRecordingSupported()`.
- **IN-03** `utils.ts:45,65` — `normalizeEvent`/`normalizeEvents` typed `any`; use
  `unknown` + narrowing.
- **IN-04** `use-replay.ts:163-167` — `seekTo` passes `NaN` through; guard with
  `Number.isFinite`.
- **IN-05** `export-filename.ts:45-52` — `sessionId` interpolated raw; strip
  non-`[A-Za-z0-9_-]`.
- **IN-06** `record-replay.ts:152-154` — mirror-canvas dimensions captured once;
  panel resize mid-record distorts frames (noted for Phase 5 real-codec validation).
