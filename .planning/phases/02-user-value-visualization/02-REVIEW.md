---
phase: 02-user-value-visualization
reviewed: 2026-06-14T18:47:48Z
depth: standard
files_reviewed: 22
files_reviewed_list:
  - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/ComparisonService.kt
  - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/checksystem/TimingAnalyzer.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ComparisonRoutes.kt
  - backend/src/main/resources/openapi/documentation.yaml
  - frontend/src/lib/toast.ts
  - frontend/src/lib/export-png.ts
  - frontend/src/lib/export-svg.ts
  - frontend/src/lib/export-json.ts
  - frontend/src/lib/export-filename.ts
  - frontend/src/lib/record-replay.ts
  - frontend/src/lib/projections/project-coroutines.ts
  - frontend/src/lib/projections/project-thread-activity.ts
  - frontend/src/lib/api-client.ts
  - frontend/src/hooks/use-replay.ts
  - frontend/src/hooks/use-event-stream.ts
  - frontend/src/hooks/use-record-replay.ts
  - frontend/src/components/export/ExportMenu.tsx
  - frontend/src/components/comparison/SyncedTreePair.tsx
  - frontend/src/components/comparison/ComparisonView.tsx
  - frontend/src/components/replay/ReplayController.tsx
  - frontend/src/components/replay/RecordConfirmModal.tsx
  - frontend/src/components/replay/LiveDataNotice.tsx
  - frontend/src/components/SessionDetails.tsx
  - frontend/src/components/Layout.tsx
  - frontend/src/routes/compare/index.tsx
  - frontend/src/types/api.ts
findings:
  critical: 2
  warning: 9
  info: 6
  total: 17
status: issues_found
---

# Phase 02: Code Review Report

**Reviewed:** 2026-06-14T18:47:48Z
**Depth:** standard
**Files Reviewed:** 22 (+ openapi contract)
**Status:** issues_found

## Summary

Reviewed the Phase 02 replay/time-travel, export (PNG/SVG/WebM/JSON), and session-comparison
surface plus the backend compare-route rename, thread-utilization delta, and the events/checksystem
de-fork (ns→ms TimingAnalyzer).

The backend changes are clean and correct — the compare route uses strict 404, the duration/thread
math is sound, and the ns→ms conversion is consistent. The frontend export and comparison UI are
mostly solid. The concentration of real defects is in the **recording pipeline** and the
**replay/recording wiring**, where the scripted record flow is only unit-tested against a fully
mocked replay stub, so two genuine logic races (the D-03 end-seek vs. record-seek-0 conflict, and the
first-frame capture gap) are not exercised. There is also a correctness gap in the client coroutine
projection (duplicate `CoroutineCreated` first-write-wins diverges from the server's last-write-wins
LinkedHashMap, contrary to its own oracle claim) and a stale-closure bug in the record auto-stop
effect that can drop the final frame and double-trigger stop.

Two BLOCKER findings concern the recording pipeline producing a wrong/empty video; the rest are
WARNING-level robustness/correctness gaps and INFO-level quality items.

## Narrative Findings (AI reviewer)

## Critical Issues

### CR-01: Record flow seeks to 0 then the D-03 effect immediately seeks to the end — recording captures only the final frame

**File:** `frontend/src/hooks/use-record-replay.ts:162-184`, `frontend/src/components/SessionDetails.tsx:145-155`
**Issue:** `beginRecording()` calls `enterReplay()` (which sets `replaySnapshot` + `replayActive=true`)
and then `currentReplay.seekTo(0)` to start the scripted record from the first event. But `SessionDetails`
has an independent effect (D-03, lines 146-155) that fires on `replaySnapshot` identity change and calls
`replaySeekTo(replaySnapshot.length - 1)` — jumping to the END. Both react to the same snapshot-set, and
their ordering is not coordinated:

- `enterReplay` sets a NEW snapshot array → `useReplay`'s own effect resets `currentIndex` to 0
  (use-replay.ts:130-133), then the D-03 effect seeks to `length - 1`.
- `beginRecording`'s `seekTo(0)` runs synchronously inside the same click handler, BEFORE those effects
  flush.

Net result after effects settle: `currentIndex` is driven to the last index by the D-03 effect, not 0.
The auto-stop effect (`currentIndex >= totalEvents - 1`) then fires on the very first capture, so the
recorder records a single frame of the final state and stops. A one-click "record replay" produces a
~0-duration video of the end frame instead of the full timeline.

This race is invisible to the unit tests because `use-record-replay.test.ts` injects a hand-rolled
`makeReplay()` stub whose `seekTo`/`play` are `vi.fn()` no-ops (use-record-replay.test.ts:41-58) — the
real `useReplay` + the D-03 SessionDetails effect are never in the loop.
**Fix:** Gate the D-03 auto-seek-to-end so it does not run when a recording is starting, e.g. have
`useRecordReplay` own the post-enter seek, or pass a `forRecording` flag through `enterReplay` that
suppresses the end-seek effect:
```ts
// SessionDetails.tsx — skip the end-seek while a recording is being set up
useEffect(() => {
  if (!replayActive || recordReplay.isRecording || recordingArming) return
  // ... existing seek-to-end
}, [replayActive, replaySnapshot, replaySeekTo, recordReplay.isRecording])
```
and have `beginRecording` perform `seekTo(0)` only after replay entry has settled (e.g. drive the
seek from an effect keyed on the snapshot, not inline in the click handler).

### CR-02: Record auto-stop effect captures stale `recorder`/cancel state — final frame dropped and `stop()` can fire twice

**File:** `frontend/src/hooks/use-record-replay.ts:206-232`
**Issue:** The per-frame capture effect depends on `[isRecording, currentIndex, totalEvents, resetRecordingState]`.
Each `currentIndex` change tears down the previous effect (setting `cancelled = true`) and starts a new
async IIFE that `await recorder.captureFrame()`. Two defects:

1. **Dropped final frame / no stop:** During fast playback, `currentIndex` can advance again while the
   previous `await recorder.captureFrame()` is still pending. The cleanup sets `cancelled = true`, so when
   that await resolves the `if (cancelled) return` bails BEFORE the auto-stop check — but the *new* effect
   for the final index races: if `useReplay` reaches the last index and stops playback, only the last
   scheduled effect runs the stop branch. If the last `currentIndex` update and the `isRecording` flip
   interleave (e.g. the auto-stop’s `resetRecordingState()` sets `isRecording=false`), the effect can
   early-return at `if (!isRecording) return` and never call `recorder.stop()`, leaving the recorder
   running and the `.webm` never downloaded.
2. **Double stop:** `resetRecordingState()` sets `isRecording=false` and clears `recorderRef`, but the
   effect that called `stop()` already captured `recorder` in a local. If a trailing `currentIndex`/
   `totalEvents` change re-runs the effect after `stop()` resolved but before React commits the
   `isRecording=false` state, the auto-stop branch can be entered a second time and call `recorder.stop()`
   on an already-inactive recorder. `record-replay.ts:227-231` handles `state==='inactive'` by manually
   invoking `recorder.onstop`, but `discard()` nulls `onstop`, so the interaction is fragile and order-dependent.
**Fix:** Make stop idempotent with a guard ref and drive auto-stop off a single committed signal rather
than the capture effect:
```ts
const stoppingRef = useRef(false)
// in the auto-stop branch:
if (totalEvents > 0 && currentIndex >= totalEvents - 1 && !stoppingRef.current) {
  stoppingRef.current = true
  const filename = await recorder.stop()
  recorderRef.current = null
  resetRecordingState()
  toastSuccess(`Saved ${filename}`)
}
```
and reset `stoppingRef` in `resetRecordingState`. Capturing `recorderRef.current` once and null-guarding
prevents the double-stop path.

## Warnings

### WR-01: `projectCoroutines` duplicate-`CoroutineCreated` handling diverges from the server snapshot it claims to oracle-match

**File:** `frontend/src/lib/projections/project-coroutines.ts:63-78`
**Issue:** The doc comment and SUMMARY assert the output "deep-equals the server's `RuntimeSnapshot.coroutines`"
which is a `LinkedHashMap` populated by `map[id] = node` — i.e. **last-write-wins** on the node fields
(a second `CoroutineCreated` for the same id would overwrite `parentId`/`scopeId`/`label`), while
preserving the *original* insertion position. This reducer instead does **first-write-wins** (`if
(!nodes.has(event.coroutineId))` skips the whole node, including any changed fields). For a stream with
a re-emitted `CoroutineCreated` carrying updated metadata, the client projection and the server snapshot
would differ — silently breaking the very deep-equal invariant the layer exists to guarantee (T-02-05).
The inline comment even acknowledges "the backend map would overwrite" but ships the opposite behavior.
**Fix:** Mirror the backend exactly — update fields on re-creation while keeping insertion order
(Map preserves position on re-set of an existing key only if you mutate the existing value, so update in
place):
```ts
if (event.kind === 'coroutine.created') {
  const existing = nodes.get(event.coroutineId)
  const node = { id: event.coroutineId, jobId: event.jobId, parentId: event.parentCoroutineId,
                 scopeId: event.scopeId, label: event.label, state: existing?.state ?? CoroutineState.CREATED }
  nodes.set(event.coroutineId, node) // last-write-wins fields, position preserved for existing keys
  continue
}
```
(or document explicitly that re-creation is impossible and assert it, rather than claiming oracle-equality.)

### WR-02: SVG export inlines styles by positional child index — clone/source tree mismatch corrupts output

**File:** `frontend/src/lib/export-svg.ts:50-76`
**Issue:** `inlineStyles` walks `source.children` and `target.children` by index assuming the cloned tree
is structurally identical to the live one. `svg.cloneNode(true)` does produce an identical tree at clone
time, but `getComputedStyle(source)` is read against the LIVE element while iterating. If any live SVG
child is added/removed/reordered between `cloneNode` (line 88) and the recursive walk (e.g. an animation
frame, a React re-render committing during the synchronous export, or a `<use>`/`<defs>` that resolves
lazily), `sourceChildren[i]` and `targetChildren[i]` desynchronize and styles are inlined onto the wrong
elements. The guard `if (sc && tc)` only catches length mismatches at the tail, not reordering.
**Fix:** Inline styles BEFORE cloning (walk the source once, write inline styles into a detached clone
built from the already-frozen subtree), or snapshot the source subtree into an array first so the walk
is over a stable structure. Minimal safer approach: clone first, then walk only the clone, reading
computed styles via a parallel `querySelectorAll` index captured atomically.

### WR-03: SVG/WebM SVG-rasterization path serializes untrusted live DOM without the export whitelist

**File:** `frontend/src/lib/record-replay.ts:177-196`
**Issue:** The WebM SVG fast path does `new XMLSerializer().serializeToString(svgRoot)` on the LIVE panel
SVG and rasterizes it via `img.src = objectURL`. Unlike `export-svg.ts`, this path performs NO style
inlining and NO sanitization — it serializes whatever is currently in the SVG subtree, including any
`<script>`, `<foreignObject>`, or event-handler attributes that may have been injected via coroutine
labels / event payloads rendered into the panel. While `Image`-based SVG rasterization does not execute
scripts (the SVG is loaded as an image, not a document), `<foreignObject>` content and external
references (`xlink:href` to remote URLs) can still trigger network fetches during rasterization, and the
serialized blob inherits any attacker-controlled text from event labels verbatim. The whitelist that the
SVG *export* path deliberately applies (Pattern 4 / T-02-08) is bypassed here.
**Fix:** Route the recording SVG path through the same whitelisted clone+inline used by `exportToSvg`
(extract the clone/inline into a shared helper), strip `<foreignObject>`/`<script>` and external
`href`/`xlink:href` before serialization, and prefer `html2canvas` (which already sanitizes) for any
panel whose SVG contains untrusted text.

### WR-04: `findSvgRoot` uses `el.matches('svg')` which can throw on non-Element / detached nodes

**File:** `frontend/src/lib/export-svg.ts:39-44`
**Issue:** `findSvgRoot(el: HTMLElement)` calls `el.matches('svg')`. The `getPanelEl()` getter
(`SessionDetails.tsx:135`, `ExportMenu.tsx:80`) returns `panelRef.current` which is typed `HTMLDivElement
| null` but is dereferenced after a `null` check only in some call paths. More importantly, the cast
`return el as unknown as SVGSVGElement` when `el.matches('svg')` is true is unreachable for the real
`<div>` panel wrapper (a div never matches `svg`), so the only branch that fires is the
`querySelector`. That is fine for the current panel, but the double-cast `el as unknown as SVGSVGElement`
defeats type safety: if a future caller passes a genuine `<svg>` HTMLElement the typing lies. Low blast
radius today, but the `as unknown as` cast is exactly the kind the project's "no `any`/avoid casts"
convention discourages.
**Fix:** Narrow with `instanceof SVGSVGElement` instead of `matches` + double-cast:
```ts
export function findSvgRoot(el: Element): SVGSVGElement | null {
  if (el instanceof SVGSVGElement) return el
  return el.querySelector<SVGSVGElement>('svg[viewBox], svg[width]')
}
```

### WR-05: ExportMenu computes `getPanelEl()`/`findSvgRoot` during render every cycle — stale SVG availability

**File:** `frontend/src/components/export/ExportMenu.tsx:80-82`
**Issue:** `const panelEl = getPanelEl()` and `const svgRoot = panelEl ? findSvgRoot(panelEl) : null` run on
every render, calling `getComputedStyle`-free but DOM-querying `querySelector` synchronously in render.
The SVG menu-item visibility (`...(svgRoot ? [...] : [])`) is therefore derived from whatever the DOM
looked like at the last render, not when the menu opens. In replay mode the panel content changes as the
cursor moves, so the "Export graph as SVG" item can appear/disappear out of sync with the actual panel,
and `handleSvg` re-runs `findSvgRoot` at click time (line 113-115) and silently returns if it now finds
no root — a click that does nothing with no toast. Calling DOM queries in the render body is also a
React anti-pattern (non-deterministic render).
**Fix:** Compute `svgRoot` lazily on dropdown open (e.g. `onOpenChange`) into state, or memoize against a
stable signal. At minimum, when `handleSvg` finds no root, surface `toastError(...)` instead of a silent
no-op so the user is not left clicking a dead item.

### WR-06: `useReplay` `currentEvent` reads `events[currentIndex]` without re-clamping after the events array shrinks

**File:** `frontend/src/hooks/use-replay.ts:173,180-182`
**Issue:** `currentEvent = events.length > 0 ? (events[currentIndex] ?? null) : null` and
`visibleEvents = events.slice(0, currentIndex + 1)` read `currentIndex` against the CURRENT `events`
prop. The reset-to-0 effect (lines 130-133) runs on `events` identity change, but effects run AFTER
render. On the render where a shorter `events` array arrives but `currentIndex` still holds the old
(now out-of-range) value, `events[currentIndex]` is `undefined` (handled by `?? null`) but
`visibleEvents` returns the entire array (slice clamps high), and `progress = currentIndex/(length-1)`
can exceed 1. For one render the panels can briefly show a progress >100% / a null current event before
the reset effect corrects it. Mostly cosmetic, but `progress` feeding a Slider `maxValue` can momentarily
produce an out-of-range value.
**Fix:** Clamp at read time:
```ts
const safeIndex = Math.min(currentIndex, Math.max(events.length - 1, 0))
const currentEvent = events.length > 0 ? (events[safeIndex] ?? null) : null
const visibleEvents = useMemo(() => events.slice(0, safeIndex + 1), [events, safeIndex])
```

### WR-07: `discardRecording` early-returns when `recorderRef` is null, so a hidden-tab abort during the stop() await leaks the recorder

**File:** `frontend/src/hooks/use-record-replay.ts:138-147,206-232`
**Issue:** `discardRecording` bails if `recorderRef.current` is null (line 141). The auto-stop branch
(line 222) calls `await recorder.stop()` and only `resetRecordingState()` (which nulls `recorderRef`)
AFTER it resolves. If the tab is backgrounded during that await, the `visibilitychange` handler fires
`discardRecording(ABORT_COPY)` while `recorderRef` is still set, calling `recorder.discard()` on a
recorder that is simultaneously being stopped+downloaded — `discard()` sets `onstop = null`
(record-replay.ts:237) which can cancel the in-flight download, OR the stop's `onstop` already fired and
the file downloads anyway despite the user-visible "cancelled" toast. The two paths race with no mutual
exclusion.
**Fix:** Use the same `stoppingRef` guard from CR-02 so `discardRecording` is a no-op once stop has begun,
and clear `recorderRef` synchronously when entering either terminal path.

### WR-08: `enterReplay` snapshot can be stale — reads `liveEvents`/`storedEvents` from closure, not latest

**File:** `frontend/src/components/SessionDetails.tsx:110-114,129-140`
**Issue:** `enterReplay` is a `useCallback` over `[streamEnabled, liveEvents, storedEvents]`, so it closes
over the values at the render that produced it. `useRecordReplay` then captures `enterReplay` and calls
it from `beginRecording`. Because `beginRecording` reads `replayRef.current`/`eventsRef.current` (live
refs) for the recorder events but `enterReplay` uses closure values for the FROZEN snapshot, the
`replaySnapshot` set by `enterReplay` and the `recordEvents` passed to the recorder can diverge by any
events that arrived between the `enterReplay` callback's creation and the record click. Result: the
recorder's `totalEvents`/auto-stop boundary (driven by `replay` over `replaySnapshot`) may not match the
event list the estimate was computed from, producing an off-by-N auto-stop.
**Fix:** Compute the snapshot once at click time from a ref and pass it explicitly to both `enterReplay`
and the recorder, rather than relying on two independently-closed sources of "the events to freeze."

### WR-09: 400/404 error bodies use ad-hoc `mapOf("error" ...)` not the documented `ErrorResponse` schema shape

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ComparisonRoutes.kt:15-39`
**Issue:** The OpenAPI contract (documentation.yaml:1503-1514) declares both 400 and 404 responses as
`$ref: #/components/schemas/ErrorResponse`. The route returns `mapOf("error" to "...")`. This happens to
serialize to `{"error":"..."}`, and `ComparisonView.isSessionNotFound` parses on substring "not found",
so it works today — but the response is not type-checked against `ErrorResponse`, so any future field
rename (e.g. `message`) silently breaks the contract and the FE 404 detection. The two other routes in
the file use the same ad-hoc map.
**Fix:** Return the actual `ErrorResponse` serializable type so the compiler enforces the documented
shape:
```kotlin
call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Session not found: $sessionAId"))
```

## Info

### IN-01: `ComparisonTrees` fetches full session snapshots but ignores them in favor of `coroutines` only — extra network for unused fields

**File:** `frontend/src/components/comparison/ComparisonView.tsx:241-262`
**Issue:** `ComparisonTrees` calls `useSession(sessionAId)`/`useSession(sessionBId)` to obtain
`snapshot.coroutines`, but `compareSessions` already returned the diff. The full snapshot fetch is only
for the tree coroutine lists. Not a bug, but two extra round-trips that could be folded into the compare
payload. (Out of perf scope per v1; noted for maintainability.)
**Fix:** Consider returning the coroutine node lists in the compare response, or document why the
separate fetch is intentional.

### IN-02: `isVideoRecordingSupported` duplicates the codec cascade already in `pickMimeType`

**File:** `frontend/src/components/export/ExportMenu.tsx:41-50` vs `frontend/src/lib/record-replay.ts:30-74`
**Issue:** The vp9→vp8→webm cascade and the `MediaRecorder.isTypeSupported` guard are implemented twice
with subtly different fallbacks (`ExportMenu` returns `true` when `isTypeSupported` is not a function;
`pickMimeType` returns `null`). Divergent "supported?" answers between the two could let ExportMenu enable
the Record item while `pickMimeType()` returns null (the hook would then no-op). The SUMMARY notes both
are intentional "double guards," but the inconsistent fallback is a latent mismatch.
**Fix:** Export a single `isRecordingSupported()` from `record-replay.ts` (`pickMimeType() !== null`) and
have ExportMenu consume it.

### IN-03: `normalizeEvent` / `normalizeEvents` typed `any` parameters

**File:** `frontend/src/lib/utils.ts:45,65`
**Issue:** `export function normalizeEvent(event: any)` and `normalizeEvents(events: any[])` use `any`,
against the project "no `any` where avoidable" convention. The SSE listener and api-client both flow
untrusted JSON through here; `unknown` + narrowing would be safer.
**Fix:** Type as `unknown` and narrow, or define a `RawBackendEvent` interface.

### IN-04: `seekTo` clamps with `Math.min(index, eventsRef.current.length - 1)` — negative length-0 yields -1 floor handled, but NaN passes through

**File:** `frontend/src/hooks/use-replay.ts:163-167`
**Issue:** `seekTo` is also called from the Slider `onChange` (ReplayController.tsx:176-179). If a Slider
value ever arrives as `NaN` (HeroUI edge), `Math.max(0, Math.min(NaN, ...))` is `NaN`, and `setCurrentIndex(NaN)`
would render `events[NaN]` → undefined. Defensive only.
**Fix:** `const safe = Number.isFinite(index) ? index : 0` before clamping.

### IN-05: `buildExportFilename` does not sanitize `sessionId` — odd characters land in the download filename

**File:** `frontend/src/lib/export-filename.ts:45-52`
**Issue:** `sessionId` is interpolated raw into the filename. Session ids are server-generated UUIDs today,
so this is safe, but a `/`, `\`, or control char in an id would produce a malformed/path-bearing download
name. Browsers sanitize the `download` attribute, so not exploitable, but worth a guard for robustness.
**Fix:** Strip non-`[A-Za-z0-9_-]` from `sessionId`/`panel` before composing.

### IN-06: Recording mirror-canvas dimensions read once at recorder creation — panel resize mid-record distorts frames

**File:** `frontend/src/lib/record-replay.ts:152-154`
**Issue:** `canvas.width = panelEl.clientWidth * CAPTURE_SCALE` is captured once. If the panel resizes
during recording (window resize, tab content reflow), subsequent `drawImage(rendered, 0,0,canvas.width,canvas.height)`
stretches frames to the original dimensions. Acceptable for a short scripted record, noted for the
Phase 5 real-codec validation.
**Fix:** Recompute target dimensions per frame, or document the fixed-size assumption.

---

_Reviewed: 2026-06-14T18:47:48Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
