# Session page as an IDE dock — the tree on top, everything else in one dock

**Date:** 2026-11-05
**Status:** Design — approved on the canvas in conversation; awaiting review of this document
**Canvas:** "vizcore session view redesign", https://claude.ai/artifact/LoVDqCRuELHtFUaQbqK1xh, page
"1 · The proposal" (4 boards). The boards are saved in [`2026-11-05-session-dock-canvas/`](2026-11-05-session-dock-canvas/README.md).
**Relation to earlier designs:**
- [2026-08-23 SPA workspace redesign](2026-08-23-spa-workspace-redesign-design.md): keeps D-1 to D-6 and D-8.
  **Revises D-7**: the inspector moves from a fixed right-hand column into the dock (same card order).
  Keeps its rule that the page is organised by the user's question, not by event package (S-4).
- [2026-10-21 session triage](2026-10-21-session-triage-design.md): keeps the honesty rules H-1 to H-4, the
  definitions, the core derivation (PR 1, merged as #248) and the backend, SDK and API work (its PRs 2 and 3).
  **Revises T-1** (the problem surface is the Problems tab, not a strip above the canvas), **T-3** (the
  inspector is a dock tab), **T-5** (evidence sections live in the Evidence tab) and **T-6** (Learn is a dock
  tab and gains story mode). Its frontend PRs 4 and 5 are replaced by Parts 3 to 5 below.
- [2026-11-05 Gallery](2026-11-05-gallery-how-it-works-design.md): G-2 kept the session view unchanged and
  left "the guide panel inside the session view" out of scope; story mode (S-9) is that panel.

## Problem

The session page has the answers but buries them (Codex's review of today's page, ranked):

1. **The tree comes last.** At 1512 × 788 it starts at 1,090 px, below the first screen. The page is
   2,646 px tall for a 7-coroutine demo and ~10,800 px for a real app (triage, Problem 1).
2. **A finished demo hides its result.** Completed coroutines are hidden by default (`showCompleted` starts
   `false`, `SessionWorkspace.tsx:94`), so the tree reads "Nothing running now".
3. **The structured-concurrency explanation is always open**, about 370 px above the data.
4. **Evidence grows without end:** one thread row per event.
5. **Width is wasted:** a narrow centre column and a fixed 320 px inspector (`WorkspaceBody.tsx`).
6. **Controls are scattered:** two cards, two LIVE badges.
7. **Nothing can be inspected during replay:** the inspector is paused.
8. **Colours and words mislead:** a failed run reads "Completed" in green, because the scenario state only
   checks that every coroutine is terminal (`ScenarioControls.tsx:52`); a held lock is red
   (`MutexStateIndicator.tsx:49`); and a coroutine waiting for a mutex, semaphore or channel is reported
   ACTIVE, because those wrappers emit no suspension events (`VizMutex.kt:89`, `VizSemaphore.kt:105`,
   `InstrumentedChannel.kt:77`).

## Goal

Two kinds of customer open this page, and each should get their answer on the first screen:

- **A learner** running a Gallery demo sees the tree at once and can follow the demo step by step.
- **A developer** attached to their own app sees within five seconds whether a problem was detected in what
  vizcore captured, and reaches it in one click (the triage goal, unchanged).

The page never claims more than vizcore observed (H-1 to H-4).

### Non-goals

- No change to the sessions home, the Gallery pages, Compare or sharing (D-1 to D-3, G-1).
- No new graph internals: `CoroutineTreeGraph` and `CoroutineTree` are re-hosted.
- No business outcomes as events (a demo's "paid" or "out of stock" stays in its guide text).
- Not in this design, listed under "Later": "who is waiting on what", comparing two runs, marking a problem
  as expected.

## The page

```
┌ E-commerce Orders  [Live · 6.1 s]  7 coroutines · 1 mutex · 1 semaphore     [Share] [⋯] ┐
├──────────────────────────────────────────────────────────────────────────────────────────┤
│ [Tree | Timeline]  All 7 · Active 2 · Suspended 3 · Completed 2      [Find…] [Maximise]  │
│                                                                                          │
│   order-1    order-2    order-3        order-4        order-5        order-6     order-7 │
│   Completed  Completed  Suspended      Suspended      Suspended      Suspended   …       │
│                         holds a permit holds a permit holds the lock waits, 1st          │
├══════════════════════════════ drag, or keyboard ════════════════════════════════════════┤
│ Problems · Inspector · Evidence · Events · Learn          Live 5 · Peak 7      [Fold]   │
│ order-6 · Suspended · waiting for inventory-lock for 6.1 s · 1st of 2 · held by order-5 │
│ created at SyncScenarios.kt:487 · last thread DefaultDispatcher-worker-2 · its events…   │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

Three layers: a **header**, a **main view** (Tree or Timeline), and a **dock** of tabs below it. The dock can
be resized, folded to its tab row, or hidden by maximising the main view.

## Decisions

| # | Decision |
|---|---|
| S-1 | **Layout.** Header, then the main view, then the dock. The main view takes the free height (at least 320 px); the dock starts at 40 % of the window and is resized with a drag handle that is a focusable `separator` (arrow keys move it). Fold collapses the dock to its tab row; Maximise hides it. Sizes are remembered per browser. This replaces the two-column grid of `WorkspaceBody` and the evidence section below it. |
| S-2 | **One selection, one moment.** The page holds one selected coroutine (or group) and one time position. Every part reads them: clicking a node, a timeline bar, a problem or an event selects it everywhere; replay and story steps move the time position, and every part shows that moment. |
| S-3 | **The header has one status.** Live (with the app connection, T-2), Finished, Failed, Replay or Story, with honest colours: a run with a failed coroutine is "Failed", never green. Actions: Run again (demos), Replay, Share, and "⋯" (Export, Checks, Reset session, Clear events). |
| S-4 | **Dock tabs are questions, not event packages.** *Problems* (what is wrong?), *Inspector* (what is this coroutine?), *Evidence* (what did the resources do?), *Events* (what exactly happened?), *Learn* (how do I read this?). Evidence holds T-5's sections, each collapsed with a one-line summary: Threads, Locks and permits, Channels, Flows, Jobs, Dispatchers. A section appears once its kind of evidence exists and then stays, so tabs and sections never jump. This keeps the 08-23 rule against package tabs (its "Where the eight tabs go") while giving each a place. |
| S-5 | **What opens first.** A demo started from the Gallery: Tree + Learn (story). A finished demo: Tree + Inspector on the last selection, or the session summary. A session with a detected problem: Tree + Problems. Your app, live: Tree grouped by name + Problems. Replay: as the user left it. After that, the user's last tab per session is remembered. |
| S-6 | **Honest labels.** Every node shows its recorded state on one line (Active, Suspended, Waiting for children, Completed, Failed, Cancelled, never "Running") and, on a second line, what it waits for or holds when the evidence shows it: "waits for inventory-lock · 1st", "holds inventory-lock", "holds a payment-gateway permit". Queue position is the latest observed queue. Permits are never numbered (a semaphore does not identify its permits); a timeline lays them out as unnumbered slots. A held lock is neutral, not red. Text that explains rather than records (a guide line, "cancelled after the failure") is visibly marked as explanation. |
| S-7 | **Core fix: waiting is recorded as suspension.** `VizMutex.lock`, `VizSemaphore.acquire` and `InstrumentedChannel` `send`/`receive` emit `CoroutineSuspended` (reason `mutex`, `semaphore`, `channel-send`, `channel-receive`, with the resource id) when the call actually has to wait, and `CoroutineResumed` when it continues. An uncontended call emits nothing new. Fixes the known follow-up and makes S-6's state line true for demos. |
| S-8 | **Problems tab presents the triage health.** It shows what `SessionHealthDerivation` (merged in #248) derives, served by triage PR 2's `GET /api/sessions/{id}/health`: failures and possibly blocked coroutines, each saying why it is listed and "as of" when, plus the "Long-lived (n)" observation. The first line of the tab is T-1's sentence ("1 problem detected", or the honest "nothing detected in the captured coroutines · as of <t>", or "not evaluated" with the reason). The tab label carries a count badge when problems exist. "Show" selects the coroutines (S-2) and opens the Inspector in T-3's order for that kind. No problem logic in the frontend (D-8). |
| S-9 | **Learn holds the legend and story mode.** The legend and the structured-concurrency explanation (today's always-open `StructuredConcurrencyInfo`, qualified as in T-6) live here. For a demo that has a guide, Learn offers the story: one chapter per guide step, each anchored to a recorded event (not to seconds); some chapters ask "Predict first" with two or three answers, and **Reveal** moves the time position to the anchor event and shows the answer. Moving on by itself is off by default. Steps after the current one stay hidden until revealed. The first version covers the 7 Basics, whose guides exist. |
| S-10 | **Timeline is the second main view.** One row per coroutine over time. The bar shows the recorded state; a thin band above it shows what the coroutine holds (the lock, a permit), because a coroutine can hold a lock while it is suspended. A mark ends each finished row. Lock-holder and permit lanes sit under the rows. Clicking a bar selects it (S-2), and the Inspector sums it up in one sentence ("order-7 waited 9.0 s for inventory-lock, the longest wait of the run"). It is built only from recorded intervals; gaps where events were dropped are drawn as gaps. The existing `CoroutineTimelineView` is reused where it fits. |
| S-11 | **Replay is the same page at a moment.** A time bar (step back, play, step forward, a slider labelled in seconds, speed, Exit) sits under the header. Every part shows the cursor's moment, including the Inspector, which today is paused: its state, events, resources and durations are folded from the events up to the cursor, never from the live session. In the timeline, the part after the cursor is drawn outlined. |
| S-12 | **Big apps stay readable.** The tree offers "Group by name" (on by default for attached apps): names that differ only in a trailing number (`order-17`, `order-18`) group as `order-*` with a count and a state mix. A Find box searches all coroutines, including those beyond the 200-node render cap, and reveals the match. When the cap or the 10,000-event live history trims what is shown, the page says so. |
| S-13 | **Run again starts a new session.** For a finished demo, Run again creates a new session with the same demo, so each recording stays whole and two runs are never mixed. Today's disabled button (`ScenarioControls.tsx:64`) is replaced. |
| S-14 | **Works narrow, works with a keyboard.** Below 1,024 px the dock tabs scroll horizontally, the Inspector's columns stack, and the timeline scrolls inside its box. Tree nodes are buttons (Tab, Enter selects); dock tabs follow the WAI-ARIA tabs pattern (arrow keys, `aria-controls`); the replay slider announces its time ("4.2 seconds of 12.0"). Light mode uses the darker link and state text colours from `palette.ts`; state is always text plus colour. |

## Building it: seven parts

Each part is its own PR, tests first, reviewed by Codex until clean, and checked in a browser on free ports.

| Part | What it delivers | Depends on |
|---|---|---|
| **1 · Quick wins** (frontend) | In today's layout: the tree panel moves above the explanation and the metrics; `StructuredConcurrencyInfo` folds into one line, "How to read the tree", with the legend, closed by default and remembered; a finished demo keeps its tree (completed coroutines shown when nothing is live); "Running" becomes "Active" and Waiting for children is its own chip; a run with a failure reads "Failed" in red; a held lock is neutral; one LIVE badge. | — |
| **2 · Core fix** (core) | S-7: suspension events for contended mutex, semaphore and channel waits, with tests per wrapper, including cancellation while queued. Also fixes `VizSemaphore.release` attributing to an arbitrary holder when its context lacks a coroutine id (`VizSemaphore.kt:134`), and sends exception stack traces, which the common emitter sends empty today (`EventContext.kt:129`). | — |
| **3 · The dock** (frontend) | S-1 to S-6 and S-12 to S-14: the layout, the shared selection and time position, header status, Inspector, Evidence and Events tabs re-hosting today's panels, the activity line on nodes, group by name and Find. Live lock and queue facts must refresh on new events (today `useSyncEvents` reads stored history that live updates do not invalidate, `use-sync-events.ts:57`). | 1 |
| **4 · Problems** (backend + frontend) | Triage PR 2 (health API, persistence, checks, shares) as specified there, then S-8. | 3; triage PR 2 (and PR 3 for "possibly blocked" on real apps) |
| **5 · Learn and story mode** (frontend + guide content) | S-9: the guide schema (`content/gallery/types.ts`) gains per-step event anchors (an event kind and a coroutine label) and optional predictions; the 7 Basics guides get them; a test checks every anchor matches a real run, like `GuideLabelsTest`. | 3 |
| **6 · Timeline** (backend + frontend) | S-10. The per-coroutine timeline response must include resume, terminal and body-completed events, which it omits today (`ProjectionService.kt:406`). | 2, 3 |
| **7 · Replay at a moment** (frontend, some backend) | S-11: cursor-based projections for the Inspector, Evidence and metrics; a timestamp-to-event mapping for the seconds slider (today seeking is by event index, `use-replay.ts:96`). | 3, 6 |

Parts 1 and 2 can be built in parallel. Part 1 ships first so the page improves within days.

## Testing

- **Part 1:** vitest for the moved and folded panels, the finished-demo tree, the Failed status and the
  labels; the existing `SessionWorkspace` tests that assert "Nothing running now" are updated.
- **Part 2:** core tests that a contended `lock`, `acquire`, `send` and `receive` emit Suspended then
  Resumed with the resource id, that an uncontended call emits neither, and that cancellation while queued
  leaves no waiter behind; the Channels and Combined demos re-measured with the pacing audit.
- **Part 3:** vitest for selection shared across tree, tabs and events; the tab and section rules of S-4;
  keyboard resizing; group by name; Find beyond the render cap. A browser check at 1440 and 900 px wide,
  dark and light.
- **Part 4:** the triage spec's test plan, plus the tab's states (problems, nothing detected, not evaluated).
- **Part 5:** a backend test that runs each Basics demo and finds every story anchor; vitest for predict,
  reveal and hidden steps.
- **Part 6:** intervals built from a recorded run match the run's events; holds and states are separate.
- **Part 7:** the Inspector at a cursor never shows a later event (a test with a future event in the store).

## Risks

- **Part 3 is the big one.** It moves every panel. Mitigation: it re-hosts components rather than rewriting
  them, and Part 1 has already moved the tree up, so the visible change is smaller.
- **Tabs could hide evidence.** A user may not open Evidence. Mitigation: the Evidence tab label shows what
  it holds ("Evidence · lock, 2 permits"), and Problems links to the section that explains a problem.
- **Story anchors drift from the demos** when a demo changes. Mitigation: Part 5's test fails the build.
- **Problems depends on triage PR 2,** which is not built yet. Until it lands, the Problems tab is hidden,
  not empty.

## Later

- Who is waiting on what: waiting coroutines linked to the lock, permit or channel and its holder.
- Compare two runs: did limiting concurrency help?
- Mark a problem as expected, or dismiss it.
- Story mode for the other demo groups as their guides arrive.
