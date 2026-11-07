# Part 10 · Timeline — Plan

**Spec:** [2026-11-05 session dock](../specs/2026-11-05-session-dock-design.md), S-10 (with S-2, S-6, S-7, S-11, S-14).
**Branches:** `feat/part10a-timeline-events` (backend) and `feat/part10-timeline` (the view), both from `main`, worktree
`../vizcore-part10`, ports 13951 (UI) / 18951 (API).
**Goal:** a second main view next to the tree, Graph / List / **Timeline**: one row per coroutine over time, the
recorded state as a bar, what it holds as a thin band above, a mark at each finished row's end, lock and permit lanes
under the rows. It follows Part 8's one-moment rules, and says "some intervals may be missing" when it cannot know.

## What is on main today (the starting point)

- The main view (`CoroutineCanvas`) switches Graph / List; its rows are `renderedCoroutines` (the state filter, the
  active-only default, "Show completed" and the 200-node cap, all decided in `SessionWorkspace`).
- The page's events: live/paused `allEvents`; in replay the frozen `replay.events`, the cursor `replay.currentIndex`,
  the tree projected from `replay.visibleEvents` (+ M-9 summary nodes when the replay starts after seq 1); in a
  shared link the loaded cut's events. The replay clock (`replayClock`, running max of `tsNanos` in seq order) is the
  one clock of every replay time on the page (M-2).
- Locks and permits: `projectSync` / `projectActivity` (Part 2, Part 4) give the state *at the end* of an event list;
  `SemaphorePermitReleased.holderUnknown` marks a release whose holder is unknown (S-7).
- The stream's loss count is `droppedCount` (the `dropped` control frame). Trimmed history shows as a first seq after 1.
- `CoroutineTimelineView` is the single-coroutine event list; it stays as it is.
- Backend: `ProjectionService.toSummary` maps only created, started and suspended events into the per-coroutine
  timeline response (`…/coroutines/{id}/timeline`, also at `atSeq`/`asOfSeq` and on the shared routes).

## Decisions

| # | Decision |
|---|---|
| T-1 | **Backend: the timeline response lists every lifecycle event.** `toSummary` also maps `CoroutineResumed` (`coroutine.resumed`), `CoroutineBodyCompleted` (`coroutine.body-completed`), `CoroutineCompleted`, `CoroutineCancelled` (`reason` = its `cause`) and `CoroutineFailed` (`reason` = its exception type, else its message). No new field, so the OpenAPI schema and the plugin's DTO are unchanged; the plugin's Inspector lists more events, and its "suspended at" already reads the last suspension. The Inspector's cards already handle these kinds (`CoroutineTimelineView` colours them; `TimingCard`'s span gets more exact). **`CoroutineBodyCompleted` also closes the Active phase** in `computeDurations` (no phase is open while it waits for its children), so the Timing card's Active time and the Inspector sentence (T-10) agree; a test covers a parent that waits for its children before completing. Own PR (10a). |
| T-2 | **The view is built from the page's events, never from per-coroutine reads.** One pure function, `buildTimeline(events)` (`lib/timeline/build-timeline.ts`), folds the events in seq order into rows (state spans, hold spans, end mark) and lanes. Every time is on the replay clock's rule (running max of `tsNanos` in seq order, from the first event given), so the axis, the cursor and the slider agree. Live and paused: the events the page shows; shared link: the loaded cut's; replay: **all** the frozen replay events, with the cursor's index (T-8). No request. |
| T-3 | **Rows are the coroutines the tree shows** (`renderedCoroutines`, in its order): the same state filter, active-only default, "Show completed" and 200-node cap, so the state bar and "N more" stay true in both views. The view takes the row list from the caller; Part 6 (rounds, group by name) changes how that list is made, and a group row can later map to its members' rows (noted in the PR). A row whose coroutine has no events in the given list (an M-9 summary node) shows its name and state with "no recorded intervals in these events". |
| T-4 | **State spans** follow `projectCoroutines`' transitions exactly: created → Created, started/resumed → Active, suspended → Suspended (with the wait's kind and resource when the wrapper recorded them, S-7), body-completed → Waiting for children, completed/cancelled/failed → the row ends there with an **end mark** (✓ Completed, ✕ Failed, ⊘ Cancelled: glyph, colour and text). Lifecycle events of a coroutine whose creation is not in the events are ignored, as the tree does: **such a row draws no state spans** (its state comes from the tree — the server snapshot live, Part 8's summaries in replay, possibly "State unknown") and reads "created before these events: its states are not drawn"; T-9's note says why. So a bar never shows a state the tree does not. An unfinished row runs to the last event's time (live: the newest event the page holds; never the browser's clock). |
| T-5 | **Hold spans (the thin band above the bar) are `projectSync`'s facts over time.** The fold runs `projectSync`'s rules event by event (one shared reducer step, extracted from `project-sync.ts` without changing its result) and records, after each lock or permit event, who holds what. Lock: a band runs while the coroutine is the lock's holder (from `MutexLockAcquired` to its own `MutexUnlocked`, or to the next acquisition when VizMutex records the release after the lock has passed on; a displaced holder's late release changes nothing). Permit: a band runs while the coroutine is a listed holder (`activeHolders` of the latest snapshot, plus an acquirer listed since), one band per permit it holds when the counts are settled; a band is **certain** only while the semaphore is settled and has no unknown-holder permits, and **"may hold"** (hatched, "may hold · holder unknown") otherwise — exactly when the Evidence tab and the node's line say "may hold". **Membership and certainty come only from the fold's state after each event**: a band begins where its holder becomes listed (or its count grows) and ends where it stops being listed (or its count shrinks, oldest first) — so an attributed release does not end a band before the snapshot that drops it (the band is "may hold" in between, as Evidence says), and a release that frees every permit ends every band at once. A release only **annotates** how a band ended: "released" when its holder's own attributed release came since, else "unknown" (T-6's mark). **A hold is not ended by its coroutine's end**: a lock still held after its holder finished stays drawn past the end mark ("still held after it ended"), as the lane and the Evidence tab show it. At every cursor, each row's certain and may-hold bands equal `projectActivity`'s "holds" / "may hold" for it (tested). |
| T-6 | **Unknown holders (S-7), the honest rule.** A permit release whose holder is unknown — `holderUnknown`, or a releaser listed as holding none — is not assigned to anybody. At its event, every band of that semaphore still open **ends its certain part with an "unknown" mark** and continues as "may hold" (the release made the holders unsettled, as `projectSync` says) — or ends there, with the mark, when the release freed every permit; a band whose holder later stops being listed without an attributed release of its own ends there with the "unknown" mark too (e.g. when every permit is free, or VizSemaphore retires an ended holder). The lanes show the same marks. A lock unlocked by a coroutine that is neither its holder nor a displaced holder frees it, as `projectSync` does: the open band ends with the "unknown" mark. |
| T-7 | **Lanes under the rows.** One lane per lock, titled with its name, its bands labelled with the holder. Per semaphore, as many lanes as the most bands it had at once; the lanes are unnumbered slots laid out for display (a band takes the lowest free slot) and the group is titled "<name> permits". While holders are unsettled there can be more "may hold" candidates than permits (two listed, an unknown release, a third acquires before the next snapshot): a slot beyond the semaphore's permits only ever carries "may hold" bands, and the lane group says "more listed holders than permits: who holds which is unknown" whenever that happens, so no extra permit is implied. Resources come from the same events; at most 12 lanes are drawn, then "N more lanes". |
| T-8 | **Replay: the part after the cursor is outlined (S-11).** The view draws the whole replay; the cursor is a vertical line at the cursor's time. **Spans, bands and marks keep the index of the events that start and end them; what is filled is decided by index, time only places it.** A piece that starts at or before the cursor's event is filled up to the cursor's time and hollow after it (`data-after-cursor`); a piece that starts after the cursor's event is hollow, even when it is drawn at the same time (two events at one running-max time, or a zero-length replay); an end mark is filled only when its event is at or before the cursor. An outlined region (`timeline-future`) covers the axis from the cursor to the end. At the cursor, each row's filled span is the state the tree shows for it (same events, same transitions), and the row label says it in words, so a zero-width state is still read. Rows are still the tree's rows at the cursor (T-3). |
| T-9 | **"Some intervals may be missing."** When the stream reported lost events (`droppedCount > 0`), the events start after the session's first event (trimmed history, the 10,000-event live buffer, Clear events; the session's first event is the server's `oldestRetainedSeq` when it never trimmed the session — seqs also number ids, so a complete demo starts at seq 3 or so —, else seq 1, as Part 9's `sessionFirstSeq` in #315), or a row's creation is not in the events, the view shows one note above the rows (`trimmed-note`, styled by every look already): "Some intervals may be missing: <reasons>." It does not draw gaps it cannot locate. The Inspector sentence then says "observed" (S-6). |
| T-10 | **Selection (S-2) and the Inspector sentence.** Clicking a row's bar selects its coroutine and opens the Inspector, as a tree node does. While the Timeline is the main view, the Inspector sums the selected row up in one sentence above its cards (`timeline-sentence`), from the same spans: e.g. "order-7 was active for 0.6 s and suspended for 9.0 s (9.0 s of it waiting for inventory-lock), held inventory-lock for 0.1 s, and completed at 9.6 s." In replay it is told up to the cursor ("By 4.2 s, … and is suspended, waiting for inventory-lock."); with T-9's losses, "In the observed events, …". A row without spans (T-4) says only what is known ("order-2 was created before these events; its states are not drawn."), and a hold kept after the end is said ("still holds inventory-lock after it completed"). Positions ("at 9.6 s") are seconds on the view's clock (the running max, as the slider); **durations are differences of the span's own events' raw `tsNanos`** (never below 0), the basis `computeDurations` uses for the Timing card, so a timestamp regression elsewhere in the session does not change them. Graph and List leave the Inspector unchanged. |
| T-11 | **Text plus colour, keyboard, narrow (S-14).** Every span's state is in its accessible name and tooltip, and written inside it when it is wide enough; each row label carries the row's state at the moment as text; a legend names the colours; the end marks are glyphs plus text. Rows are buttons with a roving tab stop: Tab enters the view once, ↑/↓ (Home/End) move between rows, Enter or Space selects. The rows and lanes sit in a box that scrolls inside the main view (the row names stay put); the drawing keeps a minimum width, so below 1,024 px it scrolls horizontally inside its box, never the page. |
| T-12 | **Four looks.** One markup with `data-look-part` hooks: `timeline`, `timeline-axis`, `timeline-legend`, `timeline-row`, `timeline-label`, `timeline-bar` (with `data-state`), `timeline-hold` (with `data-uncertain`), `timeline-end` (with `data-state`), `timeline-lanes`, `timeline-lane`, `timeline-lane-band`, `timeline-unknown`, `timeline-cursor`, `timeline-future`, and `timeline-sentence` in the Inspector; after-cursor pieces carry `data-after-cursor='true'`. Classic is styled with Tailwind classes from `coroutine-state-colors` like today's page. A, B and C style every hook in their own `tokens.css`, keyed `[data-look='x'] [data-look-part='…']` like their existing rules, with their own state colours. No Classic pixel changes outside the Timeline button, the view and the Inspector sentence. |
| T-13 | **Small shared-file changes.** `CoroutineCanvas` gets a third view button and a `timeline` node it renders in that mode; `SessionHeader`'s `viewMode` type widens (Export names the panel "timeline", which `ExportMenu` already knows); `Inspector` gets one optional `sentence` prop; `SessionWorkspace` gets the wiring only, through `hooks/use-session-timeline.ts`. |

## Files

PR 10a (backend):
- `ProjectionService.kt`: `toSummary` (T-1). Test: `CoroutineTimelineLifecycleTest.kt` (resumed, body-completed and each
  terminal kind are listed in seq order with their reasons; an `atSeq` read lists only those up to it).
- CHANGELOG.

PR 10b (frontend), new files, each small:
- `lib/timeline/build-timeline.ts` (+ test): T-2, T-4 to T-7.
- `lib/timeline/at-cursor.ts` (+ test): T-8 splitting, state at the cursor.
- `lib/timeline/row-sentence.ts` (+ test): T-10.
- `lib/timeline/missing-note.ts` (+ test): T-9.
- `hooks/use-session-timeline.ts`: which events, cursor and note feed the view; the selected row's sentence.
- `components/workspace/timeline/TimelineView.tsx`, `TimelineRows.tsx`, `TimelineLanes.tsx` (+ tests).
- `looks/timeline-parts.test.ts`: every hook styled in A, B and C.

Changed: `lib/projections/project-sync.ts` (its loop body becomes an exported step, `syncFold()`, that `projectSync`
and the timeline both run; `projectSync`'s result and tests unchanged), `CoroutineCanvas.tsx`, `SessionHeader.tsx` (type), `Inspector.tsx` (prop), `SessionWorkspace.tsx` (wiring),
`looks/{a-compact,b-calm,c-bold}/tokens.css`, CHANGELOG.

## Tests first

Backend (JUnit): T-1 as above; the existing timeline tests keep passing (`TimelineRouteAssembledTest`,
`CoroutineTimelineWaitTest`, binary compatibility).

Frontend (vitest):
1. `build-timeline`: spans from a recorded run (a Combined demo's events, saved as a fixture) match its events (seq,
   time, state); waits carry kind and resource; an end mark per terminal kind; an unfinished row runs to the last
   event; events before creation ignored; a row whose creation is missing has no spans and is flagged; times use the
   running max.
2. Holds: a lock held while suspended (hold band and Suspended span overlap); a displaced holder's late release; a
   holder that ends without unlocking keeps its band past the end mark; permits held twice by one coroutine (two
   bands); an unknown release → every open band ends its certain part with an unknown mark and continues as "may
   hold" (or ends there when it frees every permit); an attributed release before its snapshot keeps the band, as
   "may hold", until the snapshot drops it ("released"); two listed holders, an unknown release and a third acquirer
   before the next snapshot (a third, "may hold" slot and the lane note); a holder dropped from a snapshot without its own release ends with the unknown
   mark; snapshots interleaved with operations; a trimmed acquisition revealed by a snapshot; a non-holder unlock;
   lanes: one per lock, permit slots = most in use, lowest free slot; the 12-lane cap.
3. **Holds and states are separate**: a row's hold spans never change its state spans.
4. `at-cursor`: at **every cursor index** of the recorded run and of hand-made runs (equal times, a time regression,
   a zero-length replay), each row's filled state equals `projectCoroutines` over the events up to it; its certain and
   may-hold bands equal `projectActivity`'s "holds" / "may hold"; nothing starting after the cursor's event is filled;
   an end mark after the cursor's event is hollow.
5. `missing-note`: shown after a loss (`droppedCount`), a first seq after 1, a trimmed creation; absent otherwise.
6. `row-sentence`: finished, unfinished, at a cursor, with "observed", with waits and holds, a summary-only row;
   its durations equal raw `tsNanos` differences when another coroutine's event regresses the clock.
7. `TimelineView`: rows in the given order; clicking a bar calls `onSelect`; ↑/↓/Home/End move focus, Enter
   selects; legend and state text present; the note appears; replay draws the cursor and hollow after-cursor pieces.
8. `CoroutineCanvas`: the Timeline button shows the given node; Graph/List unchanged.
9. `Inspector`: the sentence renders only when given.
10. `SessionWorkspace`: switching to Timeline shows rows; a bar click selects and the Inspector shows the sentence.
11. Looks: `timeline-parts.test.ts`; `looks-css.test.ts` keeps passing.

## Browser check (ports 13951/18951)

Mutex, Semaphore and Combined (e-commerce) demos, live (`?scenarioId=…&live=true`) and in replay: bars match the tree's
states at a moment; a lock held while suspended; permit lanes; an unknown-holder release (a test session posted with
`holderUnknown`, if no demo produces one); replay outlines the part after the cursor; a bar click opens the Inspector
sentence; at 1,000 px wide the timeline scrolls inside its box. Screenshots in Classic, A, B and C, dark and light.

## Delivery

Two PRs from `main`, independent (the view does not need 10a, so CI runs on both): **10a** backend timeline events,
**10b** the view. CHANGELOG entries. Codex plan review (≤3 rounds) and code review (≤3 + confirmation).

## Risks and gaps

- Permit bands follow `projectSync`'s snapshot rules, so between an operation and the snapshot after it every band of
  that semaphore is briefly "may hold" (usually one event long, drawn as a sliver).
- The lanes cap at 12; a real app with more locks says "N more lanes" rather than draw them all.
- Part 6 changes how the rows are chosen; the view takes its rows from the caller, so rounds and groups plug in there.
- Long sessions: building is one pass over at most 10,000 events, recomputed only while the Timeline is shown.
