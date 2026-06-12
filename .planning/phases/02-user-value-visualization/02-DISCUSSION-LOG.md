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
