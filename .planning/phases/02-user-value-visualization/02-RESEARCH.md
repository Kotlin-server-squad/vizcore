# Phase 2: User-Value Visualization — Research

**Researched:** 2026-06-13
**Domain:** Client-side replay/time-travel, three-tier export (PNG/SVG/WebM/JSON), side-by-side session comparison — React 19 + HeroUI frontend, with a small Ktor backend slice (compare-route rename, strict-404, FQCN fork reconciliation).
**Confidence:** HIGH on codebase facts (all file:line verified by direct read); MEDIUM on browser-API recording performance (verified against MDN + practitioner sources, not benchmarked on this hardware).

> **Note on provenance:** This RESEARCH.md was produced inline by the orchestrator after two `gsd-phase-researcher` subagent spawns died on transient socket errors (0 tokens written). Every codebase claim below is grep/read-verified; web-sourced claims are tagged and listed under Sources.

---

<user_constraints>
## User Constraints (from CONTEXT.md — D-01..D-27 LOCKED)

### Locked Decisions (carried into planning verbatim — do NOT re-litigate)

**Replay ↔ live interplay**
- **D-01:** Replay available for **any session, anytime** — a Replay toggle always present in SessionDetails. Entering replay freezes the view at the events loaded at that moment.
- **D-02:** While in replay, **SSE stays connected and live events buffer in the background** — panels stop reacting (no query-invalidation re-renders), a subtle "● N new events" badge shows drift. No EventSource teardown/reconnect.
- **D-03:** Entering replay starts **at the end, paused** — panels show the same state as live; user scrubs/steps backward from "now".
- **D-04:** Exiting replay (or clicking the new-events badge) **jumps to live and forgets the cursor** — re-entering starts fresh at the end; all buffered events apply on exit.

**WebM recording (EXPT-02 video tier)**
- **D-05:** A recording captures **the active visualization panel only** (current view), not the full layout.
- **D-06:** **Scripted replay recording** — one click: enter replay → seek to start → record while auto-playing → auto-stop at last event → download. No manual free-form record mode this phase.
- **D-07:** Recording honors the **current replay speed** (0.5x–5x); no extra speed dialog.
- **D-08:** **PNG exports keep the ADR-018 info header** (session name, timestamp, event count); **video output is a clean panel capture** — the red recording indicator is in the UI but excluded from the captured region.

**Comparison UX & API**
- **D-09:** Compare endpoint **renamed to `GET /api/sessions/compare?a=&b=`** (from `GET /api/compare?sessionA=&sessionB=`), documented in OpenAPI, shared/api-types regenerated. Nothing external depends on the old path (UI never mounted).
- **D-10:** Comparison entry is a **dedicated `/compare` TanStack route with Session A/B pickers** (mount existing ComparisonView), linked from nav, `?a=&b=` in route search params for shareable URLs.
- **D-11:** "Side-by-side with delta highlights" = **the existing diff dashboard (summary delta cards + common/unique coroutine tables) PLUS one synchronized side-by-side visualization pair** below it.
- **D-12 (WR-07 resolution):** **Strict 404 on reads, explicit create only** — GET/event/SSE/compare lookups against unknown session ids return 404; sessions created only via explicit creation endpoints.

**Replay controls placement & follow**
- **D-13:** ReplayController renders as a **sticky bar below the session header, above the panels** (`sticky top-16` per UI-SPEC), visible while scrolling.
- **D-14:** EventsList **auto-scrolls + highlights the current event** during playback (existing `EventHighlight.tsx`); **manual scroll pauses the follow** until next play/step.
- **D-15:** Replay signaled by **controller presence + a small "REPLAY" chip** near the status badge, with the "● N new events" badge alongside. No theme-wide tinting.
- **D-16:** **Snap on scrub, animate on play/step** — scrubber drags re-render instantly (no animation storms); play/step keep framer-motion animations per ADR-011/027 (RPLY-03).

**Replay panel coverage (2nd pass)**
- **D-17:** **Event-derived panels replay; projection-backed tabs don't.** Tree, graph, thread lanes, timeline, EventsList render from `visibleEvents`. Flow/Channels/Sync/Jobs/Validation/Dispatchers show a subtle "live data — not replayed" notice. (See **OQ-1** — this is the dominant implementation gap.)
- **D-18:** **Dim future, keep visible** — full list/timeline rendered; past-cursor events dimmed; timeline gets a playhead line.

**Comparison side-by-side pair (2nd pass)**
- **D-19:** Synchronized pair is **two coroutine trees**; thread-lane pairing deferred.
- **D-20:** **Selection sync + delta badges, no scroll/zoom coupling** — clicking a coroutine in tree A highlights its counterpart in B (matched by name/path); A-only nodes `warning`, B-only nodes `secondary`, common nodes neutral.

**Export UX**
- **D-21:** **SVG export auto-detects SVG-native panels** — option appears wherever the panel's root render is an `<svg>`; graph view guaranteed per EXPT-02 (see **OQ-2** — the graph view is NOT actually `<svg>`).
- **D-22:** **JSON event export committed** — "Export events (JSON)" in ExportMenu downloads the normalized event array as `.json`.

**Recording robustness**
- **D-23:** **ReplayController doubles as recording progress** — red dot + elapsed + Stop (discards). No separate overlay.
- **D-24:** **Backgrounded tab aborts the recording** — `visibilitychange`→hidden stops + discards, toast: "Recording cancelled — keep the tab visible while recording."
- **D-25:** **Codec feature-detect cascade** — `MediaRecorder.isTypeSupported` vp9 → vp8 → browser-default webm; disable + tooltip if no supported type (Safari).
- **D-26:** **Duration estimate + confirm** — estimate from event gaps ÷ speed; above ~2 min show a confirm dialog with the estimate + suggest a higher speed. No hard cap.
- **D-27:** **2x capture resolution** — frames captured at scale-2 matching the ADR-018 PNG tier.

### Claude's Discretion (planner picks, grounded by this research)
- Frame-capture mechanism for recording (see §EXPT-02 — recommendation: `captureStream(0)`+`requestFrame()` mirror canvas; SVG→Image fast path for SVG-native panels, html2canvas for DOM panels).
- `useReplay` delay-clamp reconciliation toward ADR-017's 50–2000ms (see §RPLY-02 — exact one-line change given).
- Counterpart-matching rule for D-20 selection sync (recommend: match by `label` then `coroutineId`).
- Delta-badge styling, export file-naming, "live data — not replayed" notice wording, D-26 threshold fine-tuning.
- Whether to refactor `useReplay` to the ADR-017 `useReducer` state machine (recommend: NO — current `useState` impl satisfies every RPLY requirement).

### Deferred Ideas (OUT OF SCOPE — do not plan)
- Manual free-form record mode; gallery multi-select → Compare shortcut; remembered replay cursor across exit/re-enter; deep-linkable replay cursor in URL; switchable comparison pair (thread lanes); client-side projection re-derivation for full-tab time travel (Flow/Channels/Sync/Jobs); pause-and-resume recording across tab visibility.
- "Retire standalone vizcor-be/vizcor-fe" todo — reviewed, not folded (repo housekeeping).
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| RPLY-01 | Play/pause/stop/step-forward/step-back from a replay controller; **all panels reflect current event index** | §RPLY-01: `useReplay` exists & complete; ReplayController exists but **unmounted** and **missing Stop + FastForward + keyboard shortcuts**; **OQ-1**: most panels render from server snapshots, not `visibleEvents` — client-side projection layer is the dominant gap |
| RPLY-02 | Speed 0.5x–5x + scrub to any position via progress bar | §RPLY-02: SPEED_OPTIONS + Slider already in ReplayController; delay-clamp reconciliation (10ms→50-2000ms) one-line change documented |
| RPLY-03 | Animations respect active replay speed and current event (ADR-011/027) | §RPLY-03: `use-replay-motion.ts` (spring MotionValue) exists; D-16 snap-on-scrub vs animate-on-play strategy |
| EXPT-01 | Export any visualization as PNG, download in-browser | §EXPT-01: `export-png.ts` exists (scale 2); needs D-08 info-header compositing + ExportMenu wiring |
| EXPT-02 | Export graph view as standalone SVG + replay as WebM (MediaRecorder) | §EXPT-02: SVG inlining + WebM pipeline are NEW; **OQ-2**: the "graph view" (`CoroutineTreeGraph`) is HTML/CSS not `<svg>` — SVG export attaches to genuinely-SVG panels; codec cascade + Safari handling documented |
| CMPR-01 | `ComparisonService.compare(a,b)` via `GET /api/sessions/compare?a=&b=` returns count/duration/thread-utilization deltas | §CMPR-01: `ComparisonService` (core) + `ComparisonRoutes` (backend) exist; **route rename + thread-utilization delta is NOT yet computed** (current response has no thread metric) |
| CMPR-02 | View two sessions side-by-side with delta highlights | §CMPR-02: `ComparisonView` (347 lines, tables) exists unmounted; D-10 `/compare` route + D-19 synced tree pair are NEW; needs both session snapshots fetched |
</phase_requirements>

---

## Summary

Phase 2 is **~60% "mount and wire", ~40% genuinely new code**, with three planner-critical surprises that the brownfield "just mount it" framing in CONTEXT.md understates:

1. **OQ-1 — Replay panel coverage needs a client-side projection layer that mostly doesn't exist.** D-17 says "tree, graph, thread lanes, timeline + EventsList replay from `visibleEvents`." But today only `EventsList` consumes an events array. The Coroutines graph (`CoroutineTreeGraph`) renders from `session.coroutines` (a **server snapshot** fetched by `useSession`), the Threads tab renders from `threadActivity` (a **server projection** from `useThreadActivity`), and the Dispatcher/Channel/Flow/Sync/Job panels each fetch their own projection by `sessionId`. To make those panels reflect the replay cursor, the frontend needs **reducers that derive `CoroutineNode[]` and thread activity from `VizEvent[]`** — these exist server-side (`ProjectionService`/`RuntimeSnapshot`, Kotlin) but **not on the client**. This client-side projection is the single largest work item in the phase and should be its own plan/wave.

2. **OQ-2 — The "graph view" is not SVG.** ADR-018 and EXPT-02 assume the coroutine graph is SVG-native and can be serialized to standalone SVG. In reality `CoroutineTreeGraph.tsx` is an HTML/CSS pan-zoom layout (`react-zoom-pan-pinch` `TransformWrapper` + `motion.div` nodes with CSS connector lines, `CoroutineTreeGraph.tsx:39`). The only components with a true `<svg viewBox>` visualization root are `DeadlockVisualization` (`:104`), `FlowParticlePath` (`:73`), `SemaphoreGauge`, and `JobStatusDisplay`. D-21's "auto-detect `<svg>` root" rule therefore attaches SVG export to those panels; the main graph falls back to PNG (which ADR-018 explicitly permits: "DOM-based panels fall back to PNG"). **Verification must not expect SVG export on the coroutine graph** — see OQ-2 for the recommended resolution.

3. **WebM recording at 30fps on DOM panels is not free.** `html2canvas` takes 1–2s for large panels (ADR-018's own note), so html2canvas-per-frame cannot sustain 30fps. The recommended pipeline decouples capture rate from wall clock with `canvas.captureStream(0)` + `track.requestFrame()` after each rendered frame, using a fast SVG→`Image`→`drawImage` path for SVG-native panels and html2canvas for DOM panels (accepting sub-30fps on large DOM panels). MediaRecorder still timestamps in real time, so playback honors replay speed (D-07).

The rest is genuinely "mount and harden": `useReplay` (197 lines) is complete and correct; `ReplayController` (147 lines) needs Stop/FastForward/keyboard-shortcuts added and to be mounted in `SessionDetails`; `export-png.ts` is reusable; `ComparisonView` (347 lines) and the backend `ComparisonService`/`ComparisonRoutes` exist and need a route rename + a `/compare` route + the D-19 tree pair. A small backend slice reconciles the `events/` + `checksystem/` FQCN forks into `coroutine-viz-core` (folded todo, same hazard class as Phase 1's FND-01) and rides the OpenAPI/type-regeneration pass triggered by the compare-route rename.

**Primary recommendation — wave order:**
- **Wave 1 (backend, parallelizable):** compare-route rename + thread-utilization delta (CMPR-01) → OpenAPI update → shared/api-types regen; fork reconciliation (`events/`, `checksystem/`) + ForkDeletionTest extension; strict-404 audit (D-12).
- **Wave 2 (frontend foundation):** client-side event→snapshot projection layer (OQ-1); HeroUI 2.7 toast upgrade + ToastProvider.
- **Wave 3 (frontend features, parallel):** replay mounting + controller completion (RPLY-01..03, D-01..18); export menu PNG/SVG/JSON (EXPT-01, EXPT-02 SVG, D-21/D-22); `/compare` route + tree pair (CMPR-02, D-10/D-19/D-20).
- **Wave 4:** WebM recording pipeline (EXPT-02 video, D-05..08, D-23..27) — depends on replay (Wave 3) and projection (Wave 2).

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Replay playback engine | Frontend (`useReplay` hook) | — | Already complete; client-side per ADR-017 |
| Event→snapshot projection (replay) | Frontend (new `lib/projections/*`) | — | Panels need `VizEvent[]`→`CoroutineNode[]`/thread-activity reducers (OQ-1) |
| Replay controls + mounting | Frontend (`ReplayController`, `SessionDetails`) | — | Sticky bar (D-13), keyboard shortcuts, Stop/FastForward |
| SSE replay buffering | Frontend (`use-event-stream`, `SessionDetails`) | — | Gate query-invalidation while replay active (D-02) |
| PNG export | Frontend (`lib/export-png.ts`) | — | Exists; add info header (D-08) |
| SVG export | Frontend (new `lib/export-svg.ts`) | — | Computed-style inlining of `<svg>`-root panels (D-21) |
| WebM recording | Frontend (new `lib/record-replay.ts`) | — | MediaRecorder + captureStream pipeline (D-05..08, D-23..27) |
| JSON export | Frontend (ExportMenu) | — | Serialize normalized event array (D-22) |
| Toast feedback | Frontend (HeroUI 2.7 `addToast` + `ToastProvider`) | — | ADR-018 feedback; new dep bump |
| Comparison compute + route | Backend (`ComparisonService`, `ComparisonRoutes`) | Core | Rename (D-09) + thread-utilization delta (CMPR-01) |
| Strict-404 reads | Backend (route layer) | — | D-12; reads already use `getSession` (404), audit create-only callers |
| Comparison UI + tree pair | Frontend (`ComparisonView`, new `/compare` route) | — | D-10/D-11/D-19/D-20 |
| FQCN fork reconciliation | Backend (`coroutine-viz-core`) | Test (`ForkDeletionTest`) | Folded todo; rides OpenAPI/type regen |
| API contract regen | Shared (`shared/api-types`) | — | `openapi.json` sync + `openapi-typescript` regen |

---

## Standard Stack

### Already present (verified in `frontend/package.json` / `backend/build.gradle.kts`) [VERIFIED: codebase read]

| Library | Version | Purpose | Notes |
|---------|---------|---------|-------|
| `@heroui/react` | ^2.6.8 | Component library | **Toast not available until 2.7** — see new dependency |
| `framer-motion` | ^11.14.4 | Animation (ADR-011/027) | `use-replay-motion.ts` already uses `useMotionValue/animate` |
| `html2canvas` | ^1.4.1 | PNG capture (`export-png.ts`) | Reused for PNG + DOM-panel video frames |
| `@tanstack/react-router` | ^1.84.4 | File-based routing | `/compare` route follows existing `routes/` pattern |
| `@tanstack/react-query` | ^5.62.7 | Server state | Replay must gate `invalidateQueries` (D-02) |
| `react` / `react-dom` | ^19.0.0 | UI runtime | — |
| `react-icons` | ^5.3.0 | Icons (`Fi*`) | ReplayController + ExportMenu icons |
| `vite` | ^6.0.3 | Build/dev | — |
| `openapi-typescript` | ^7.0.0 | Type generation (`shared/api-types`) | `pnpm generate` → `generated.ts` |
| Ktor / kotlinx-serialization / JUnit 5 | 3.3.2 / — / 5.x | Backend + tests | Unchanged from Phase 1 |

### New dependency (one) [ASSUMED — npm registry, not benchmarked]

| Library | Version | Purpose | Why |
|---------|---------|---------|-----|
| `@heroui/react` bump | ^2.6.8 → **^2.7.x** | Adds `Toast` (`addToast` + `ToastProvider`) and `NumberInput` | ADR-018 mandates toast feedback; **no toast system exists anywhere in `frontend/src`** (grep-verified). v2.7.0 introduced Toast. [WEB: HeroUI v2.7 release] |

**Upgrade risk:** HeroUI 2.7 also **upgraded its React Aria packages** [WEB]. This is the only non-trivial regression risk in the phase. Mitigation: bump as its own atomic task in Wave 2, run the **full** frontend suite (`pnpm test`) + a manual smoke of existing HeroUI-heavy panels (Tabs, Select, Slider, Dropdown, Table) before building on it. The `framer-motion ^11.14.4` peer already satisfies 2.7. There is a known setup-ordering footgun (issue #5086 "addToast isn't working") — `ToastProvider` must be mounted at the app root **before** any `addToast` call.

**Alternative considered:** `sonner` (standalone toast, zero HeroUI coupling). Rejected as default — the app is uniformly HeroUI; staying in-ecosystem keeps theming consistent. Keep `sonner` as the fallback if the React Aria bump destabilizes the suite.

### No other new packages

WebM recording uses **only browser-native APIs** (`MediaRecorder`, `HTMLCanvasElement.captureStream`, `CanvasCaptureMediaStreamTrack.requestFrame`). SVG export uses **only** `getComputedStyle` + `XMLSerializer` + `Blob`. JSON export is `JSON.stringify` + `Blob`. No `save-svg-as-png`, no `ccapture.js`, no `fix-webm-duration` — all hand-rolled against native APIs (see Don't Hand-Roll for why the duration-fix gotcha is contained).

---

## Package Legitimacy Audit

| Package | Registry | Verdict | Disposition |
|---------|----------|---------|-------------|
| `@heroui/react@^2.7` | npm | [APPROVED] — same publisher as the pinned 2.6.8, official Toast component | Bump existing dep; no new publisher trust surface |

**Packages flagged [SUS]:** none. **No new third-party packages introduced** (recording/SVG/JSON are native-API only).

---

## Architecture Patterns

### Pattern 1: Replay mounting in `SessionDetails` (D-01, D-13, D-17)

`SessionDetails.tsx:70` currently picks the panel data source: `const allEvents = streamEnabled ? liveEvents : storedEvents || []`. Replay adds a third mode. Recommended shape:

```tsx
const replay = useReplay(allEventsForReplay)        // frozen snapshot at entry (D-01)
const [replayActive, setReplayActive] = useState(false)
// Panels that are event-derived consume replay.visibleEvents when replayActive (D-17):
const panelEvents = replayActive ? replay.visibleEvents : allEvents
// Coroutine snapshot for the graph: live = session.coroutines (server);
//   replay = projectCoroutines(replay.visibleEvents)  ← NEW (OQ-1)
const panelCoroutines = replayActive ? projectCoroutines(replay.visibleEvents) : session.coroutines
```

- **D-03:** on entering replay, `replay.seekTo(totalEvents - 1)` then ensure paused (default).
- **D-13:** render `<ReplayController>` in a `sticky top-16 z-…` wrapper above `<Tabs>` (currently `SessionDetails.tsx:389`).
- **D-15:** REPLAY chip + "● N new events" badge near the status chip; new-events count = live events appended to the stream since replay entry.
- **D-17 notice:** projection-backed tabs (`ChannelPanel`/`FlowPanel`/`SyncPanel`/`JobPanel`/`ValidationPanel`/`DispatcherOverview`, each called with `sessionId`) render a small "live data — not replayed" banner when `replayActive`.

### Pattern 2: Client-side event→snapshot projection (OQ-1 — the dominant new module)

The Coroutines graph needs `CoroutineNode[]`; the Threads tab needs thread activity; both currently come from the server. Build pure reducers in e.g. `frontend/src/lib/projections/`:

- `projectCoroutines(events: VizEvent[]): CoroutineNode[]` — fold coroutine lifecycle events (`CoroutineCreated/Started/Suspended/Resumed/Completed/Cancelled/Failed`, `ThreadAssigned`) into the same `CoroutineNode` shape `buildCoroutineTree` already consumes. The terminal-state and parent/scope logic mirrors the backend `RuntimeSnapshot`/`ProjectionService` (Kotlin) — read those for the authoritative state machine, but reimplement in TS.
- `projectThreadActivity(events): ThreadActivity` — fold `ThreadAssigned` events into the `Map<threadId, ThreadEvent[]>` shape `buildThreadLanes` (in `lib/thread-lanes.ts`) consumes.

This is the riskiest correctness work in the phase (concurrency state machine). Plan it as its own task with heavy unit tests (a captured session's `/events` response → expected projected snapshot, asserted against the server's snapshot for the same session as the oracle).

### Pattern 3: SSE replay buffering (D-02)

`use-event-stream.ts` always appends to `events` AND fires debounced `queryClient.invalidateQueries` (`:234-237`) + `SessionDetails` does a debounced `refetch()` (`:101-138`). For replay:
- Keep the EventSource open and keep **appending** to `events` (needed for the "● N new events" badge).
- **Suspend the invalidation/refetch side effects** while `replayActive` — pass a `paused` flag into `useEventStream` (or gate the `invalidateQueries`/`refetch` calls behind `!replayActive`). Do not tear down the EventSource (D-02 explicit). Preserve the Phase-1 machinery (max-seq dedup, bounded backoff, max-wait cap) — only the cache-invalidation effect is gated.
- **D-04:** on exit, `setReplayActive(false)` and let the buffered events apply (a single invalidation flush); drop the replay cursor.

### Pattern 4: SVG export by computed-style inlining (EXPT-02, D-21)

Standalone SVG must not depend on Tailwind classes (ADR-018 + [WEB: confirmed Tailwind classes aren't portable in standalone SVG]). Approach:
1. Find the panel's `<svg>` root (D-21 auto-detect; `null` → fall back to PNG).
2. Deep-clone the node.
3. Walk every element; for each, read `getComputedStyle` on the original and write a **whitelist** of properties inline (`fill`, `stroke`, `stroke-width`, `stroke-dasharray`, `color`, `opacity`, `font-family`, `font-size`, `font-weight`, `transform`) — whitelisting avoids the multi-MB bloat of inlining all ~300 computed properties.
4. Set `xmlns="http://www.w3.org/2000/svg"`, explicit `width`/`height` from `getBoundingClientRect`, add metadata comment (session id, timestamp, event range per ADR-018).
5. `new XMLSerializer().serializeToString(clone)` → `Blob([...], {type:'image/svg+xml'})` → object-URL download.

### Pattern 5: WebM recording pipeline (EXPT-02 video, D-05..08, D-23..27)

Build `frontend/src/lib/record-replay.ts` as a **pure, testable pipeline** separate from React wiring:
```
1. Pick mimeType via cascade (D-25):
   ['video/webm;codecs=vp9','video/webm;codecs=vp8','video/webm']
     .find(MediaRecorder.isTypeSupported)  → null ⇒ disable + tooltip (Safari)
2. Create a mirror <canvas> sized panel.clientWidth*2 × clientHeight*2 (D-27).
3. stream = canvas.captureStream(0); recorder = new MediaRecorder(stream, {mimeType, videoBitsPerSecond: 2_500_000})
4. Drive the existing replay: seekTo(0) → play() at current speed (D-06/D-07).
   Per replay frame: draw current panel state to the mirror canvas, then track.requestFrame().
     - SVG-native panel: serialize <svg> → Image → ctx.drawImage  (fast)
     - DOM panel: html2canvas(panel) → ctx.drawImage  (slow; sub-30fps on large panels — acceptable)
5. On last event: recorder.stop(); assemble Blob from chunks; download {session}-replay-{ts}.webm
6. visibilitychange→hidden (D-24): recorder.stop() + discard + toast.
7. Stop button (D-23): same discard path.
```
- **D-08:** the captured region is the mirror canvas (panel only) — the red indicator lives in the controller chrome, never drawn to the canvas.
- **D-26 duration estimate:** `sum over events of clamp(gap_ms, 50, 2000) / speed` (the same clamp as RPLY-02). `> 120_000ms` ⇒ confirm modal suggesting a higher speed.
- Why `captureStream(0)`+`requestFrame()` over `captureStream(30)`: it ties captured frames to *rendered* frames, so a slow html2canvas frame produces one held frame instead of dropped/duplicated frames [WEB: MDN captureStream + practitioner write-ups]. MediaRecorder still timestamps in real time, so total video length ≈ wall-clock replay length (honoring speed).

### Pattern 6: Comparison route rename + tree pair (CMPR-01/02, D-09/D-10/D-11/D-19/D-20)

- **Backend (D-09):** `ComparisonRoutes.kt:11` `get("/api/compare")` → `get("/api/sessions/compare")`; query params `sessionA/sessionB` → `a/b`. Keep the existing `SessionManager.getSession` null→404 (already strict — satisfies D-12 for this route).
- **CMPR-01 thread-utilization delta:** the current `SessionComparison` (`ComparisonService.kt:21`) has count + duration deltas but **no thread metric**. CMPR-01 requires "thread-utilization deltas" — add a field (e.g. `threadUtilizationDiff`/`distinctThreadsDiff`) computed from `ThreadAssigned` events or `snapshot` thread data. This is a real backend addition, not just a rename.
- **Contract regen:** update `backend/src/main/resources/openapi/documentation.yaml`, sync `shared/api-types/openapi.json`, run `pnpm --filter @.../api-types generate` (`openapi-typescript openapi.json -o generated.ts`). Update `api-client.ts:134` `compareSessions` URL + the `SessionComparison` TS type consumers.
- **Frontend (D-10):** new `frontend/src/routes/compare.tsx` (TanStack file route) with `?a=&b=` `validateSearch`, mount `<ComparisonView>` seeded from search params; add nav link in `__root.tsx`/Layout.
- **Tree pair (D-19/D-20):** below `ComparisonView`'s existing `<CommonCoroutines>` table, render two trees. The compare response gives only id lists + per-coroutine state, **not full trees** — fetch each session's snapshot (`GET /api/sessions/{a}` and `{b}` via `useSession`) to render two `CoroutineTreeGraph`/`CoroutineTree` instances, then overlay delta badges from the compare result (`coroutinesOnlyInA`→`warning`, `coroutinesOnlyInB`→`secondary`, reusing `ComparisonView.tsx:234,248` colors). Selection sync: lift a `selectedCoroutineId` state, match counterpart by `label` then `coroutineId` (D-20), highlight in both.

### Pattern 7: FQCN fork reconciliation (folded todo)

Same hazard class as Phase 1 FND-01. `backend/src/main/kotlin/com/jh/proj/coroutineviz/events/` (channel, coroutine, deferred, dispatcher, flow, job subdirs + `DeadlockEvents.kt`, `MutexEvents.kt`, `SemaphoreEvents.kt`, `SuspensionPoint.kt`) and `.../checksystem/` (`TimingAnalyzer.kt` + 8 others) duplicate core packages on one classpath. Reconcile into `coroutine-viz-core`, **ensure the `checksystem/TimingAnalyzer.kt` ns→ms fix lands in core** (it currently lives only in the backend copy — fat-jar classloader ordering could silently undo it), and extend `ForkDeletionTest.kt` (`backend/src/test/.../ForkDeletionTest.kt`) to assert zero classes under `events/` and `checksystem/` in `backend/src/main`. Do this in Wave 1 behind the compare rename so the type-regen pass is coordinated.

---

## Don't Hand-Roll

| Need | Use this | Not this |
|------|----------|----------|
| Replay playback timer/seek/speed | existing `useReplay` (`hooks/use-replay.ts`) | a fresh reducer state machine — current hook already satisfies RPLY-01/02 |
| Smooth scrub progress | existing `use-replay-motion.ts` (spring MotionValue) | manual rAF interpolation |
| PNG raster capture | existing `export-png.ts` + `html2canvas` | a second canvas-capture path |
| Toast feedback | HeroUI 2.7 `addToast` + `ToastProvider` | hand-rolled portal/timeout toast |
| Comparison compute | existing core `ComparisonService` | recomputing diffs in the frontend |
| Coroutine tree build | existing `buildCoroutineTree` / thread lanes `buildThreadLanes` | re-deriving tree layout — only the **events→nodes projection** is new |

**Known native-API gotcha (contained, no library):** Chrome's `MediaRecorder` writes WebM with a missing/`-1` duration in the container header for `captureStream`-sourced recordings, so some players show no seek bar. The blob still plays start-to-finish. Acceptable for Phase 2 (ADR-018 itself notes WebM portability caveats). If it becomes a UAT complaint, the fix is a tiny header patch, not a dependency — keep it deferred unless raised.

---

## Common Pitfalls

1. **Assuming the graph view is SVG (OQ-2).** `CoroutineTreeGraph` is HTML/CSS — SVG export must auto-detect `<svg>` roots and fall back to PNG for the graph. Don't write a "serialize CoroutineTreeGraph to SVG" task; it has no SVG to serialize.
2. **Assuming panels already consume events (OQ-1).** Only `EventsList` does. The Coroutines/Threads panels read server snapshots; replay needs new client projections. Budget for it.
3. **html2canvas-per-frame at 30fps.** Will stutter on large DOM panels. Use `captureStream(0)`+`requestFrame()` so frames are held, not dropped; prefer the SVG→Image fast path where a panel is SVG-native.
4. **Safari "cascade to webm" still fails.** Safari supports `MediaRecorder` but **no WebM codec at all** [WEB]. The vp9→vp8→bare-webm cascade returns nothing on Safari → disable the video option with the tooltip (D-25). Do **not** assume `MediaRecorder` presence implies WebM support.
5. **Forgetting to gate query invalidation in replay (D-02).** If `use-event-stream`'s `invalidateQueries` keeps firing, panels re-render from live data and the frozen replay view jitters. Gate the effect, not the EventSource.
6. **Breaking Phase-1 SSE hardening.** `use-event-stream.ts` carries dedup (`seenSeqsRef`), bounded fatal-retry, and max-wait-capped debounce. The replay gate must wrap only the invalidation side effect; don't refactor the connect/retry/dedup logic.
7. **HeroUI 2.7 React Aria bump regressions.** Bump in isolation, run the full suite, smoke existing Tabs/Select/Slider/Dropdown/Table before building on Toast. Mount `ToastProvider` before any `addToast` (issue #5086).
8. **CMPR-01 thread-utilization delta is missing.** The current `SessionComparison` has no thread metric; adding it is real backend work, not covered by the rename.
9. **Type drift after the rename.** `api-client.ts:134` URL, `SessionComparison` TS type, and `shared/api-types/generated.ts` must all be regenerated in one pass or the frontend compiles against a stale contract.
10. **Mocking browser APIs in Vitest/jsdom.** `MediaRecorder`, `captureStream`, `requestFrame`, `html2canvas` are absent in jsdom. Split recording into a pure pipeline module and mock these (mirror `export-png.test.ts`'s `vi.mock('html2canvas')` pattern).

---

## Code Examples

### `useReplay` delay-clamp reconciliation (RPLY-02, ADR-017) [VERIFIED: use-replay.ts:97-98]

Current (`use-replay.ts:97-98`):
```ts
const deltaNanos = next.tsNanos - current.tsNanos
const delayMs = Math.max(deltaNanos / 1_000_000 / speedRef.current, 10)   // 10ms min only
```
ADR-017-aligned (clamp the BASE 50–2000ms, then divide by speed):
```ts
const baseMs = Math.min(Math.max((next.tsNanos - current.tsNanos) / 1_000_000, 50), 2000)
const delayMs = baseMs / speedRef.current
```

### Codec cascade (D-25) [WEB-derived, native API]
```ts
const TYPES = ['video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm']
const mimeType = TYPES.find(t => MediaRecorder.isTypeSupported?.(t)) ?? null
// mimeType === null  → disable video export, show Safari tooltip (D-25)
```

### SVG-native detection (D-21) [VERIFIED: panel roots]
```ts
const svg = panelEl.matches('svg') ? panelEl : panelEl.querySelector('svg[viewBox], svg[width]')
// svg === null → SVG option hidden for this panel; PNG remains
```

### Strict-404 audit (D-12) [VERIFIED: SessionRoutes.kt:60 / FlowScenarioRoutes.kt:86]
Read endpoints already 404 (`SessionManager.getSession(...) == null` at `SessionRoutes.kt:60,109,126,145,169,191`). `getOrCreateSession` (`FlowScenarioRoutes.kt:86`) is called only by **scenario-run/pattern** routes (`ScenarioRunnerRoutes.kt`, `PatternRoutes.kt`, `FlowScenarioRoutes.kt`) — all explicit-create paths. D-12 ⇒ confirm no **read/SSE/compare** route ever calls `getOrCreateSession`; add a frontend "session not found" state on 404.

---

## Validation Architecture

### Test Framework [VERIFIED: codebase + Phase-1 RESEARCH]

| Property | Value |
|----------|-------|
| Frontend | Vitest 4.1 + jsdom + @testing-library/react 16 + MSW (wire-shape mocks) |
| Frontend quick / full | `cd frontend && pnpm test` / `pnpm test:coverage` |
| Backend | JUnit 5 (Jupiter) + Ktor Test Host + kotlinx-coroutines-test |
| Backend quick / full | `cd backend && ./gradlew test --tests "*.X*"` / `./gradlew test` |
| Browser-API mocking | `vi.mock` + manual globals (jsdom lacks `MediaRecorder`/`captureStream`/`requestFrame`); precedent: `frontend/src/lib/export-png.test.ts` mocks `html2canvas` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Command | Exists? |
|--------|----------|-----------|---------|---------|
| RPLY-01 | play/pause/step/stop/seek transitions update `currentIndex`/`visibleEvents` | unit (hook) | `pnpm test use-replay` | ❌ new (hook untested) |
| RPLY-01 | event→snapshot projection equals server snapshot for same session (OQ-1 oracle) | unit | `pnpm test projections` | ❌ new |
| RPLY-01 | ReplayController renders Stop/FastForward; keyboard Space/←/→/Home/End/1-4 fire controls | component | `pnpm test ReplayController` | ❌ new |
| RPLY-02 | speed select + scrub seek; delay clamp 50–2000ms | unit | `pnpm test use-replay` | ❌ new |
| RPLY-03 | future events dimmed (D-18); snap-on-scrub vs animate-on-play (D-16) | component | `pnpm test` | ❌ new |
| EXPT-01 | PNG export triggers download with info header (D-08) | unit (mock html2canvas) | `pnpm test export-png` | ✅ exists (extend for header) |
| EXPT-02 | SVG export inlines computed styles, downloads `image/svg+xml`; hidden when no `<svg>` root | unit | `pnpm test export-svg` | ❌ new |
| EXPT-02 | codec cascade picks first supported; null⇒disabled (D-25) | unit (mock MediaRecorder) | `pnpm test record-replay` | ❌ new |
| EXPT-02 | duration estimate + abort-on-hidden (D-24/D-26) | unit (mock) | `pnpm test record-replay` | ❌ new |
| CMPR-01 | `GET /api/sessions/compare?a=&b=` returns 200 + count/duration/thread deltas; unknown id ⇒ 404 | integration (Ktor testApplication) | `./gradlew test --tests "*Comparison*"` | ⚠ exists for old path — rename + thread-delta + 404 cases |
| CMPR-02 | `/compare` route mounts; tree pair renders with delta badges + selection sync | component | `pnpm test compare` | ❌ new |
| (fork) | zero classes under `backend/src/main/.../events/` and `.../checksystem/` | static | `./gradlew test --tests "*ForkDeletionTest*"` | ✅ exists — extend to two packages |
| (fork) | TimingAnalyzer ns→ms fix present in core | unit | `./gradlew test --tests "*TimingAnalyzer*"` | ⚠ verify lands in core |

### Sampling Rate
- **Per task commit:** `cd frontend && pnpm test` (changed area) or `cd backend && ./gradlew test`.
- **Per wave merge:** both suites green.
- **Phase gate:** `pnpm test:coverage` + `./gradlew test` green; manual smoke of replay/export/compare in the running app (Playwright E2E for these flows is **FETEST-02, Phase 5** — not this phase).

### Validation Architecture note (Nyquist)
Recording and projection are the high-risk surfaces. **Recording** is browser-API-bound and cannot run in jsdom — so isolate it into `lib/record-replay.ts` (pure pipeline, mock-tested) with the React component a thin shell; real-codec validation defers to Playwright in Phase 5. **Projection** (OQ-1) is the correctness risk — test it against the server snapshot as an oracle (same session → `projectCoroutines(events)` deep-equals `GET /api/sessions/{id}` coroutines).

---

## Security Domain

Phase 2 adds no auth routes (Auth is Phase 3). ASVS L1 considerations for the changes in scope:

| ASVS | Applies | Control |
|------|---------|---------|
| V5 Input Validation | yes (minor) | `/compare?a=&b=` params and `/compare` route `validateSearch` — reject missing/blank/`a===b`; 404 on unknown ids (D-12) instead of silent create |
| V1 Architecture | yes (minor) | Export/recording are fully client-side — no new server attack surface; blobs are object-URLs revoked after download (`export-png.ts:42` precedent) |
| V12 Files/Resources | yes (minor) | Downloads use `Blob` + object-URL + `revokeObjectURL`; no server file write |

| Threat | STRIDE | Mitigation |
|--------|--------|-----------|
| Unbounded WebM blob in memory on a huge/slow replay | DoS (client) | D-26 duration-estimate confirm; Stop button (D-23); abort-on-hidden (D-24) |
| Tainted-canvas `SecurityError` on export (cross-origin images) | — | ADR-018 toast error path; `useCORS:true` already set (`export-png.ts:19`) |
| `getOrCreateSession` silent create masks races (WR-07) | Tampering/Integrity | D-12 strict-404 on reads |

---

## Open Questions (flagged for the planner)

- **OQ-1 (HIGH — scope) — Replay panel coverage requires a new client-side projection layer.** D-17 lists "tree, graph, thread lanes, timeline" as replaying from `visibleEvents`, but only `EventsList` consumes events today; the rest read server snapshots. **Recommendation:** plan an explicit "client-side event→snapshot projection" task/plan (Wave 2) that builds `projectCoroutines` + `projectThreadActivity`, tested against the server snapshot as oracle. If that proves too large for this phase, the fallback (still D-17-compatible) is to narrow replay's event-derived set to **EventsList + timeline** and show the "live data — not replayed" notice on the Coroutines/Threads tabs too — but confirm with the user before narrowing, since D-17 names the tree/graph explicitly.
- **OQ-2 (HIGH — requirement interpretation) — "graph view as standalone SVG" (EXPT-02) vs reality.** `CoroutineTreeGraph` is HTML/CSS, not `<svg>`. **Recommendation:** honor D-21 literally — SVG export auto-attaches wherever an `<svg viewBox>` root exists (`DeadlockVisualization`, `FlowParticlePath`, `SemaphoreGauge`, `JobStatusDisplay`), satisfying "an SVG-rendered graph as standalone SVG"; the coroutine graph gets PNG (ADR-018-sanctioned). Verification should assert SVG export on a genuinely-SVG panel, **not** on `CoroutineTreeGraph`. Surface this so the verifier doesn't fail the phase on a literal reading.
- **OQ-3 (MED) — CMPR-01 thread-utilization delta is unimplemented.** The current `SessionComparison` has no thread metric. The planner must add one (field + compute + OpenAPI + type regen + UI). Define the exact metric (distinct-thread count diff vs utilization-ratio diff) in planning.

---

## Sources

### Primary (HIGH — direct codebase read, file:line verified)
- `frontend/src/hooks/use-replay.ts` (197 lines — complete playback engine; delay clamp `:97-98`)
- `frontend/src/components/replay/ReplayController.tsx` (147 lines — unmounted; no Stop/FastForward/keyboard)
- `frontend/src/hooks/use-replay-motion.ts`, `components/replay/EventHighlight.tsx`
- `frontend/src/hooks/use-event-stream.ts` (SSE dedup/backoff/debounced-invalidation `:234-237`)
- `frontend/src/components/SessionDetails.tsx` (data-source `:70`, Tabs `:389`, projection-panel calls `:433-468`)
- `frontend/src/components/CoroutineTreeGraph.tsx` (HTML/CSS pan-zoom, NOT svg — `:39`); `CoroutineTree.tsx` (icon-only `<svg>` `:152`)
- SVG-root panels: `sync/DeadlockVisualization.tsx:104`, `flow/FlowParticlePath.tsx:73`, `sync/SemaphoreGauge.tsx`, `JobStatusDisplay.tsx`
- `frontend/src/components/comparison/ComparisonView.tsx` (347 lines — tables only; colors `:234,248`)
- `frontend/src/lib/export-png.ts` (scale 2, bg `#18181b`); `export-png.test.ts` (mock pattern)
- `frontend/src/lib/api-client.ts:134` (`compareSessions` URL), `:67` (`createEventSource`); `hooks/use-comparison.ts`
- `backend/.../routes/ComparisonRoutes.kt` (`get("/api/compare")` `:11`), `Routing.kt:34`
- `backend/coroutine-viz-core/.../session/ComparisonService.kt` (`SessionComparison` `:21` — no thread metric)
- `backend/.../routes/SessionRoutes.kt:60` (getSession→404), `FlowScenarioRoutes.kt:86` (`getOrCreateSession`)
- `backend/src/main/.../events/` + `.../checksystem/` (FQCN forks); `backend/src/test/.../ForkDeletionTest.kt`
- `shared/api-types/package.json` (`openapi-typescript openapi.json -o generated.ts`), `shared/api-types/openapi.json`
- `frontend/package.json` (HeroUI ^2.6.8, framer-motion ^11.14.4, html2canvas ^1.4.1)
- ADR-017 (replay), ADR-018 (export — note "DOM panels fall back to PNG"), ADR-011/027 (animation/replay-motion)
- `.planning/todos/pending/{wr-07,backend-events-package-fork,shared-api-types-regeneration}.md`
- `02-CONTEXT.md` (D-01..27), `02-UI-SPEC.md` (toast finding, Stop=seek-0, Safari tooltip)

### Secondary (MEDIUM — web, June 2026)
- MDN: `HTMLCanvasElement.captureStream()` / `CanvasCaptureMediaStreamTrack.requestFrame()` (frame-rate 0 + manual `requestFrame`)
- MDN: `MediaRecorder.isTypeSupported()` (per-browser codec gating)
- media-codings.com "Recording cross browser compatible media" + TestMu "MediaRecorder Browser Support" (Safari = mp4/H.264 only, no WebM)
- HeroUI v2.7.0 release (Toast/`addToast`/`ToastProvider` introduced; React Aria upgraded); HeroUI issue #5086 (ToastProvider ordering)
- Practitioner write-ups (Theodo, Medium/de Charentenay) on canvas→MediaRecorder real-time-clock behavior

### Tertiary (LOW — standard-API knowledge, not re-verified)
- `XMLSerializer` / `getComputedStyle` SVG-inlining technique; `Blob` + object-URL download idiom

## Metadata
**Confidence breakdown:** Codebase facts HIGH (all file:line verified). Stack HIGH (package.json read). Recording performance MEDIUM (MDN + practitioner sources; not benchmarked on target hardware). OQ-1/OQ-2 HIGH (direct contradiction between ADR/CONTEXT wording and verified component implementations).
**Research date:** 2026-06-13
**Valid until:** 2026-09-13 (stable stack; revisit if HeroUI majors or MediaRecorder codec landscape shifts)
