# Phase 2: User-Value Visualization - Context

**Gathered:** 2026-06-12
**Status:** Ready for planning

<domain>
## Phase Boundary

A developer can replay a captured session step-by-step (play/pause/stop/step, scrub, 0.5x–5x speed) with every panel reflecting the current event, export visualizations (PNG via html2canvas, standalone style-inlined SVG for graph views, WebM video via MediaRecorder), and compare two sessions side-by-side with event-count, duration, and thread-utilization delta highlights via `GET /api/sessions/compare?a=&b=`. Replay and export are entirely client-side (ADR-017/018); comparison reuses the existing backend `ComparisonService`. Requirements: RPLY-01..03, EXPT-01..02, CMPR-01..02.

**Brownfield note — this phase is largely "mount, complete, and harden":** `useReplay`, `ReplayController`, `EventHighlight`, `use-replay-motion`, `export-png.ts`, `ComparisonView` (347 lines), and a registered backend comparison route all exist but are unmounted/unwired. SVG and WebM export do not exist yet.

</domain>

<decisions>
## Implementation Decisions

### Replay ↔ live session interplay
- **D-01:** Replay is available for **any session, anytime** — a Replay toggle always present in SessionDetails. Entering replay freezes the view at the events loaded at that moment. (Completed-only would rarely trigger since vizcore sessions often stay "running".)
- **D-02:** While in replay, **SSE stays connected and live events buffer in the background** — panels stop reacting (no query-invalidation re-renders), and a subtle "● N new events" badge shows drift. No EventSource teardown/reconnect.
- **D-03:** Entering replay starts **at the end, paused** — panels show the same state as live; the user scrubs/steps backward from "now".
- **D-04:** Exiting replay (or clicking the new-events badge) **jumps to live and forgets the cursor position** — re-entering starts fresh at the end. All buffered events apply on exit.

### WebM recording (EXPT-02 video tier)
- **D-05:** A recording captures **the active visualization panel only** (currently selected view: tree, graph, thread lanes, timeline…), not the full multi-panel layout.
- **D-06:** **Scripted replay recording** — one click runs the pipeline: enter replay → seek to start → record while auto-playing → auto-stop at the last event → download. No manual free-form record mode this phase.
- **D-07:** The recording honors the **current replay speed setting** (0.5x–5x from the controller); no extra speed dialog.
- **D-08:** **PNG exports keep the ADR-018 info header** (session name, timestamp, event count); **video output is a clean panel capture** — the red recording indicator is visible in the UI but excluded from the captured region.

### Comparison UX & API alignment
- **D-09:** The compare endpoint is **renamed to `GET /api/sessions/compare?a=&b=`** (from the existing `GET /api/compare?sessionA=&sessionB=`) to match the roadmap success criterion, documented in the OpenAPI spec, with shared/api-types regenerated (folded todo rides along). Nothing external depends on the old path — the UI was never mounted.
- **D-10:** Comparison entry is a **dedicated `/compare` TanStack route with Session A/B pickers** (mount the existing ComparisonView), linked from the nav, with `?a=&b=` in route search params for shareable URLs.
- **D-11:** "Side-by-side with delta highlights" = **the existing diff dashboard (summary delta cards + common/unique coroutine tables) plus one synchronized side-by-side visualization pair** (e.g. two coroutine trees or thread-lane views) below it.
- **D-12 (WR-07 resolution):** **Strict 404 on reads, explicit create only** — GET/event/SSE/compare lookups against unknown session ids return 404; sessions are created only via the explicit creation endpoints. The frontend gets a clear "session not found" state instead of a phantom empty view.

### Replay controls placement & follow behavior
- **D-13:** The ReplayController renders as a **sticky bar pinned below the session header, above the panels**, visible while scrolling. The existing toolbar-shaped `ReplayController.tsx` is a direct fit.
- **D-14:** EventsList **auto-scrolls + highlights the current event** during playback (use existing `EventHighlight.tsx`), but **manual scrolling pauses the follow** until the next play/step (standard log-viewer behavior).
- **D-15:** Replay mode is signaled by the **controller's presence plus a small "REPLAY" chip** near the session status badge, with the "● N new events" badge alongside. No theme-wide tinting.
- **D-16:** **Snap on scrub, animate on play/step** — dragging the scrubber re-renders panels instantly at the target state (no animation storms); normal play and single steps keep framer-motion animations per ADR-011/027 (RPLY-03).

### Claude's Discretion
- DOM→canvas frame-capture mechanism for recording the panel (html2canvas-per-frame vs intermediate canvas mirror) — pick what sustains acceptable fps.
- Inter-event delay handling in `useReplay`: the existing hook plays raw tsNanos gaps (min-clamp 10ms only); ADR-017 specifies a 50ms–2000ms clamp — reconcile toward the ADR unless research finds a better feel.
- Which visualization pair ships as the comparison side-by-side panel (tree vs thread lanes), exact delta-highlight styling, scrubber implementation details, keyboard-shortcut conflict handling (ADR-017 set is locked), large-session scrub performance tactics, SVG style-inlining approach.
- Where the JSON export menu item (ADR-018 "bonus") lands — include if cheap, not a requirement.

### Folded Todos
- **WR-07 getOrCreateSession silent substitution** (`.planning/todos/pending/wr-07-getorcreatesession-silent-substitution.md`) — unknown session ids silently create fresh sessions, masking races and corrupting comparisons. Resolved here as D-12 (strict 404 on reads); comparison work makes this phase the natural home.
- **shared/api-types regeneration** (`.planning/todos/pending/shared-api-types-regeneration.md`) — the compare endpoint rename (D-09) touches the OpenAPI spec, so syncing the local openapi.json copy and regenerating TS types rides along in the same coordinated pass.
- **backend events/ + checksystem/ FQCN fork reconciliation** (`.planning/todos/pending/backend-events-package-fork.md`) — same hazard class FND-01 eliminated for session/ and wrappers/; reconcile both packages into `coroutine-viz-core` and extend ForkDeletionTest to guard `events/` and `checksystem/` (the ns→ms TimingAnalyzer fix must end up in core).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Locked architecture decisions
- `docs/adr/017-replay-engine-design.md` — client-side replay engine: controller layout, state machine, `visibleEvents` contract, 50–2000ms delay clamp, keyboard shortcuts (Space/arrows/Home/End/1-4), panel synchronization
- `docs/adr/018-export-system-design.md` — three-tier export: html2canvas PNG (scale 2, header), style-inlined SVG for SVG-native panels, MediaRecorder WebM (30fps, vp9, 2.5Mbps), ExportMenu + per-panel buttons, toast/error feedback
- `docs/adr/011-animation-system-design.md` — animation system that must respect replay speed (RPLY-03)
- `docs/adr/027-animation-performance-replay.md` — replay-aware animation performance rules (informs D-16 snap-vs-animate)

### Requirements
- `.planning/REQUIREMENTS.md` — RPLY-01..03, EXPT-01..02, CMPR-01..02 definitions
- `.planning/ROADMAP.md` §Phase 2 — success criteria (incl. the `GET /api/sessions/compare?a=&b=` contract per D-09)

### Folded todo sources
- `.planning/todos/pending/wr-07-getorcreatesession-silent-substitution.md` — WR-07 problem statement (resolved by D-12)
- `.planning/todos/pending/backend-events-package-fork.md` — events/ + checksystem/ fork inventory incl. the TimingAnalyzer ns→ms hazard
- `.planning/todos/pending/shared-api-types-regeneration.md` — openapi.json copy sync + type-generation procedure

### Codebase maps
- `.planning/codebase/TESTING.md` — test conventions for regression tests
- `.planning/codebase/ARCHITECTURE.md` — event/session layer relationships, SSE pipeline

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `frontend/src/hooks/use-replay.ts` (197 lines) — complete play/pause/step/seek/speed hook returning `visibleEvents`; deviates from ADR-017 on delay clamping (see Claude's Discretion) and uses useState rather than the ADR's useReducer state machine.
- `frontend/src/components/replay/ReplayController.tsx` (147 lines) + `EventHighlight.tsx` (162 lines) + `frontend/src/hooks/use-replay-motion.ts` — built but never mounted anywhere.
- `frontend/src/lib/export-png.ts` (+ test) — html2canvas PNG export utility already exists; `html2canvas@1.4.1` already a dependency.
- `frontend/src/components/comparison/ComparisonView.tsx` (347 lines) — Session A/B HeroUI Selects, SummaryStats/DiffStat delta cards, UniqueCoroutines/CommonCoroutines tables, StateBadge, EventCountDiff — unmounted, no route.
- `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/ComparisonService.kt` — compare logic in core; `backend/.../routes/ComparisonRoutes.kt` registered in `Routing.kt` at the old path (rename per D-09).

### Established Patterns
- Phase 1's SSE machinery: first-connect comment-frame flush, bounded-backoff fatal retry, `maxSeqRef` replay dedup, debounced query invalidation with max-wait caps — replay-mode buffering (D-02) must suspend the invalidation re-renders without breaking these.
- `normalizeEvent()` maps backend PascalCase `type` → frontend camelCase `kind`; replay consumes already-normalized `VizEvent[]` sorted by seq.
- TanStack Router file-based routes under `frontend/src/routes/` (gallery, scenarios, sessions) — `/compare` follows the same pattern.
- ms-units contract for timing (Phase 1 TimingAnalyzer fix); `tsNanos` on events drives replay delays.
- Frontend tests colocated (`*.test.tsx`, Vitest + Testing Library + MSW serving the backend's exact wire shapes); backend JUnit 5 + Ktor Test Host (live-stream reads under `withContext(Dispatchers.Default)` per MetricsWiringTest convention).

### Integration Points
- `SessionDetails` — hosts the Replay toggle, sticky ReplayController bar, REPLAY chip + new-events badge, and the ExportMenu; panels switch from live data to `visibleEvents` when replay is active.
- `use-event-stream.ts` / query invalidation hooks — gate invalidation behind "not in replay" while keeping the EventSource open (D-02).
- `frontend/src/routes/` — new `/compare` route mounting ComparisonView with `?a=&b=` search params.
- `backend/.../Routing.kt` + `openapi/documentation.yaml` + `shared/api-types` — compare path rename, spec documentation, type regeneration (one coordinated pass).
- Session read routes (`getOrCreateSession` call sites) — strict-404 change (D-12) plus frontend "session not found" state.
- `ForkDeletionTest` — extend to guard `events/` and `checksystem/` after fork reconciliation.

</code_context>

<specifics>
## Specific Ideas

- Replay should feel like "how did I get here?" debugging: enter at the end paused, scrub backward — not a video player that resets your view.
- One-click shareable clips: "Record replay" produces a deterministic WebM of the whole session at the chosen speed with zero manual driving.
- Comparison URLs are shareable: `/compare?a=<id>&b=<id>` reproduces the exact comparison.

</specifics>

<deferred>
## Deferred Ideas

- Manual free-form record mode (start/stop capturing whatever the user does) — revisit if scripted recordings prove insufficient.
- Gallery multi-select → Compare shortcut pre-filling the pickers — nice-to-have on top of the `/compare` route.
- Remembering replay cursor position across exit/re-enter — rejected for now (D-04); revisit only if back-and-forth debugging demands it.
- Deep-linkable replay position (cursor index in URL) — not discussed as scope; possible future polish.

### Reviewed Todos (not folded)
- "Retire standalone vizcor-be & vizcor-fe, redirect to monorepo" — repo housekeeping, unrelated to Phase 2 scope; stays a standalone todo (also reviewed-not-folded in Phase 1).

</deferred>

---

*Phase: 02-user-value-visualization*
*Context gathered: 2026-06-12*
