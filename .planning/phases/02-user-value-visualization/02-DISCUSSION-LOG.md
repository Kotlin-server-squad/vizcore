# Phase 2: User-Value Visualization - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-06-12
**Phase:** 02-user-value-visualization
**Areas discussed:** Replay ↔ live session interplay, WebM recording scope & flow, Comparison UX & API alignment, Replay controls placement & follow behavior

---

## Todo Cross-Reference

| Todo | Folded? |
|------|---------|
| WR-07 getOrCreateSession silent substitution | ✓ |
| shared/api-types regeneration | ✓ |
| backend events/ + checksystem/ fork reconcile | ✓ |
| Retire standalone repos | — (stays standalone) |

---

## Replay ↔ live session interplay

| Option | Description | Selected |
|--------|-------------|----------|
| Any session, anytime | Replay toggle always available; entering freezes view at loaded events | ✓ |
| Completed/stopped sessions only | Strict ADR-017 reading | |
| You decide | | |

**User's choice:** Any session, anytime (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Buffer in background + indicator | SSE stays connected, panels stop reacting, "● N new events" badge | ✓ |
| Disconnect SSE during replay | Tear down EventSource, refetch on exit | |
| Keep panels live-updating | Replay only drives a highlight cursor | |

**User's choice:** Buffer in background + indicator (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| At the end, paused | Scrub backward from "now" — debugging feel | ✓ |
| At the start, paused | Video-like, view resets on entry | |
| At the start, auto-playing | Zero-click demo feel | |

**User's choice:** At the end, paused (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Jump to live, forget position | Re-entering starts fresh at the end | ✓ |
| Jump to live, remember position | Restores previous cursor index | |
| You decide | | |

**User's choice:** Jump to live, forget position (recommended)

---

## WebM recording scope & flow

| Option | Description | Selected |
|--------|-------------|----------|
| The active visualization panel | Clean single-panel video of selected view | ✓ |
| Full session view | All visible panels, heavier rasterization | |
| User picks per recording | Region selector dialog | |

**User's choice:** The active visualization panel (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Scripted replay recording | One click: replay from start, auto-stop at end, download | ✓ |
| Manual record of whatever I do | Free-form start/stop capture | |
| Both | | |

**User's choice:** Scripted replay recording (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Current replay speed setting | Honors 0.5x–5x from controller | ✓ |
| Always 1x | Deterministic real-time clips | |
| Ask in a record dialog | Pre-record speed + duration estimate | |

**User's choice:** Current replay speed setting (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Header on PNG only, clean video | ADR-018 header on PNG; video excludes overlays/indicator | ✓ |
| Header on both PNG and video | Burn metadata into frames | |
| No headers anywhere | Deviate from ADR-018 | |

**User's choice:** Header on PNG only, clean video (recommended)

---

## Comparison UX & API alignment

| Option | Description | Selected |
|--------|-------------|----------|
| Align to /api/sessions/compare?a=&b= | Rename route+params, OpenAPI doc, regen types | ✓ |
| Keep /api/compare, amend criterion | Update ROADMAP wording instead | |
| You decide | | |

**User's choice:** Align to /api/sessions/compare?a=&b= (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Dedicated /compare route with pickers | Mount existing ComparisonView, shareable ?a=&b= URL | ✓ |
| Gallery multi-select → Compare | Selection state + Compare button | |
| Both | | |

**User's choice:** Dedicated /compare route with pickers (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Diff dashboard + side-by-side panel | Existing summary cards/tables + one synced visualization pair | ✓ |
| Diff dashboard only | Ship existing view as-is | |
| Full dual SessionDetails | Split-screen everything | |

**User's choice:** Diff dashboard + side-by-side panel (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Strict 404 on reads, explicit create only | Unknown ids 404; explicit creation endpoints only | ✓ |
| 404 on reads, keep silent-create on writes | Lenient SDK ergonomics | |
| Keep silent-create everywhere | Defer the decision again | |

**User's choice:** Strict 404 on reads, explicit create only (recommended) — resolves WR-07

---

## Replay controls placement & follow behavior

| Option | Description | Selected |
|--------|-------------|----------|
| Sticky bar above the panels | Pins below session header, visible while scrolling | ✓ |
| Floating bottom bar | Video-player overlay | |
| Inside a dedicated Replay tab | Mode separation, weakens in-place scrubbing | |

**User's choice:** Sticky bar above the panels (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Auto-scroll + highlight, yield to user | Manual scroll pauses follow until next play/step | ✓ |
| Highlight only, no auto-scroll | | |
| You decide | | |

**User's choice:** Auto-scroll + highlight, yield to user (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Controller + REPLAY badge | Chip near session status badge + new-events badge | ✓ |
| Tinted border/backdrop | Theme-wide styling concern | |
| Controller presence is enough | Minimal | |

**User's choice:** Controller + REPLAY badge (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Snap on scrub, animate on play/step | Instant re-render on scrubber drags; motion on play/steps per ADR-011/027 | ✓ |
| Always animate | Animation storms on big jumps | |
| Always snap during replay | Contradicts RPLY-03 | |

**User's choice:** Snap on scrub, animate on play/step (recommended)

---

## Claude's Discretion

- DOM→canvas frame-capture mechanism for panel recording
- useReplay delay-clamp reconciliation toward ADR-017 (50–2000ms)
- Comparison side-by-side panel choice (tree vs thread lanes), delta styling
- Scrubber implementation, keyboard-shortcut conflict handling, large-session scrub performance
- SVG style-inlining approach; JSON export menu item (include if cheap)

## Deferred Ideas

- Manual free-form record mode
- Gallery multi-select → Compare shortcut
- Remembered replay cursor across exit/re-enter
- Deep-linkable replay cursor position in URL

---

# Update Session — 2026-06-12 (second pass)

**Areas discussed:** Replay panel coverage, Comparison side-by-side pair, Export UX surface, Recording robustness
**Mode:** Update of existing context — D-01..16 held locked; this pass resolved discretion-parked and undiscussed areas (now D-17..27).

---

## Replay panel coverage

| Option | Description | Selected |
|--------|-------------|----------|
| Event-derived only | Tree/graph/lanes/timeline + EventsList replay from visibleEvents; projection-backed tabs show "live data — not replayed" notice | ✓ |
| Everything time-travels | Client-side re-derivation of all projections — biggest work item of the phase | |
| Core views + best-effort tabs | Per-tab decision during planning | |

**User's choice:** Event-derived only (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Dim future, keep visible | Full list/timeline rendered; past-cursor events dimmed; timeline playhead | ✓ |
| Hide future entirely | Panels show only up-to-cursor events | |
| Per-panel split | Timeline full-with-playhead, EventsList hides future | |

**User's choice:** Dim future, keep visible (recommended)

---

## Comparison side-by-side pair

| Option | Description | Selected |
|--------|-------------|----------|
| Coroutine trees | Pairs with existing common/unique tables; nodes carry delta badges | ✓ |
| Thread lanes | Visualizes thread-utilization delta directly | |
| Switchable (trees + lanes) | Two synchronized-pair implementations — roughly doubles the slice | |

**User's choice:** Coroutine trees (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Selection sync + badges | Click in A highlights counterpart in B; A-only/B-only outline colors; no scroll/zoom coupling | ✓ |
| Scroll/zoom lock only | Spatial comparison; tables carry diff detail | |
| Full sync | Scroll/zoom lock + selection sync + badges | |

**User's choice:** Selection sync + badges (recommended)

---

## Export UX surface

| Option | Description | Selected |
|--------|-------------|----------|
| Auto-detect SVG-native | SVG option wherever panel root is an <svg>; graph view guaranteed | ✓ |
| Graph view only | Literal EXPT-02 minimum | |
| Explicit allowlist | Curated panel list | |

**User's choice:** Auto-detect SVG-native (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Include in ExportMenu | "Export events (JSON)" — normalized event array as .json download | ✓ |
| Drop it this phase | PNG/SVG/WebM only | |
| Claude's discretion | Include only if trivially cheap | |

**User's choice:** Include in ExportMenu (recommended) — JSON export is now committed, no longer discretionary

---

## Recording robustness

| Option | Description | Selected |
|--------|-------------|----------|
| Controller doubles as progress | ReplayController gains recording state: red dot + elapsed + Stop (discards) | ✓ |
| Progress overlay + cancel | Dedicated overlay with percent/elapsed | |
| Minimal dot only | Cancel in ExportMenu | |

**User's choice:** Controller doubles as progress (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Abort with toast | visibilitychange→hidden stops + discards; toast explains | ✓ |
| Pause and resume | Seamless output, trickier state handling | |
| Best effort | Accept frozen frames | |

**User's choice:** Abort with toast (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Feature-detect cascade | isTypeSupported: vp9 → vp8 → default; disabled + tooltip if MediaRecorder absent | ✓ |
| Strict vp9 only | Video disabled on Safari | |
| Claude's discretion | Decide during implementation | |

**User's choice:** Feature-detect cascade (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| Estimate + confirm | Estimate duration from gaps ÷ speed; confirm dialog above ~2 min, suggest higher speed | ✓ |
| Hard cap auto-stop | Fixed cap with truncation toast | |
| No guard | Stop button only | |

**User's choice:** Estimate + confirm (recommended)

| Option | Description | Selected |
|--------|-------------|----------|
| 2x like PNG | Scale-2 capture matching ADR-018 PNG tier | ✓ |
| On-screen size (1x) | Cheapest encode, soft text on hi-DPI | |
| Claude's discretion | Benchmark, fall back if fps suffers | |

**User's choice:** 2x like PNG (recommended)

---

## Claude's Discretion (updated)

- Frame-capture mechanism (now at 2x resolution per D-27)
- useReplay delay-clamp reconciliation toward ADR-017
- Scrubber implementation, keyboard-shortcut conflicts, large-session scrub performance, SVG style-inlining
- Counterpart matching rule for selection sync (name vs name+path); delta-badge styling
- Export file naming; "live data — not replayed" notice wording; confirm-dialog threshold fine-tuning

## Deferred Ideas (added this session)

- Switchable comparison pair (thread lanes alongside trees)
- Client-side projection re-derivation for full-tab time travel
- Pause-and-resume recording across tab visibility changes
