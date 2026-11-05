# Session page as an IDE dock — the tree on top, everything else in one dock

**Date:** 2026-11-05
**Status:** Design — approved on the canvas in conversation; revised after Codex review rounds 1 and 2; awaiting
review of this document
**Canvas:** "vizcore session view redesign", https://claude.ai/artifact/LoVDqCRuELHtFUaQbqK1xh, page
"1 · The proposal" (4 boards). The boards are saved in [`2026-11-05-session-dock-canvas/`](2026-11-05-session-dock-canvas/README.md).
Where a board and this document disagree, **this document wins**; the boards' numbers are illustrative.

**Relation to earlier designs.** This design builds on two specs and changes them only where listed.

- [2026-08-23 SPA workspace redesign](2026-08-23-spa-workspace-redesign-design.md): keeps D-1, D-3, D-5,
  D-6 and D-8; D-2 as already superseded for the Gallery by G-1; D-4 as already revised by T-1. **Revises
  D-7:** the inspector moves from a fixed right-hand column into the dock, with the same card order. Keeps the
  rule that the page is organised by the user's question, not by event package (S-4).
- [2026-10-21 session triage](2026-10-21-session-triage-design.md): keeps the honesty rules H-1 to H-4, the
  definitions (what is a failure, what is possibly blocked, what is long-lived), the core derivation (its PR 1,
  merged as #248), its backend and SDK work (its PRs 2 and 3), T-2 (two statuses), T-4 (one round at a time),
  T-8 (checks are not health) and its Modes section. **Revises:** T-1 (the problem surface is the Problems
  tab, S-8), T-3 (the inspector is a dock tab), T-5 and its "no tabs" outcome (evidence sections live in an
  Evidence tab, S-4), T-6 (Learn is a dock tab and gains story mode) and T-7 (header actions, S-3). Its
  PR 2 becomes Part 5 below; its frontend PRs 4 and 5 are replaced by Parts 3, 6, 7 and 8; the table in "Where triage's frontend work goes"
  gives each of their requirements an owner.
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

- No change to the sessions home, the Gallery pages, Compare or sharing (D-1, D-3, G-1).
- No business outcomes as events (a demo's "paid" or "out of stock" stays in its guide text).
- No new problem kinds: the Problems tab shows triage's definitions only. A lock queue is evidence, not a
  problem.
- Not in this design, listed under "Later": "who is waiting on what", comparing two runs, marking a problem
  as expected or dismissing it.

## The page

```
┌ E-commerce Orders  Finished · 12.0 s   ⟳ View updating                 [Run again] [Replay] [⋯] ┐
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│ [Tree | Timeline]  Round [12:01:07 ▾]  All 7 · Active 0 · Completed 7     [Find…] [Maximise]     │
│                                                                                                  │
│   order-1    order-2    order-3    order-4    order-5    order-6    order-7                      │
│   Completed  Completed  Completed  Completed  Completed  Completed  Completed                    │
│                                                                    waited 9.0 s for the lock     │
├══════════════════════════════ drag, or keyboard ════════════════════════════════════════════════┤
│ Problems · Inspector · Evidence · Events · Learn                                         [Fold]  │
│ order-7 · Completed at 9.6 s · waited 9.0 s for inventory-lock (the longest observed wait)       │
│ created at SyncScenarios.kt:487 · last thread DefaultDispatcher-worker-3 · its events…           │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

Three layers: a **header**, a **main view** (Tree or Timeline), and a **dock** of tabs below it. The dock can
be resized, folded to its tab row, or hidden by maximising the main view.

## Decisions

| # | Decision |
|---|---|
| S-1 | **Layout.** Header, then the main view, then the dock. The main view takes the free height (at least 320 px); the dock starts at 40 % of the window and is resized with a drag handle that is a focusable `separator` (arrow keys move it). Fold collapses the dock to its tab row; Maximise hides it. Sizes are remembered per browser. This replaces the two-column grid of `WorkspaceBody` and the evidence section below it, and needs the graph to fill its box instead of its fixed `70vh` / 480 px height (`CoroutineTreeGraph.tsx:280`) and the route's width limit to be lifted for this page. |
| S-2 | **One selection, one moment.** The page holds one selection and one time position, and every part reads them. A selection is a coroutine, a group (S-12) or a resource. Clicking a node, a timeline bar or a problem selects its coroutine. Clicking an event selects the coroutine it is about: its `coroutineId`, or for lock and permit events the requester, acquirer or releaser; an event about a resource only (a lock created) selects that resource and opens its Evidence section. Replay and story steps move the time position (S-11). |
| S-3 | **The header keeps three facts apart** (T-2, extended): the **run outcome** for demos (Running, Finished, Failed — a run with any failed coroutine is Failed, in red, never green "Completed"); the **app connection** for attached apps (connected / disconnected, from the health API's ingest state, Part 5; until that lands, no connection pill is shown); and the **view** (updating, paused, replay at <t>, story, loaded at <t>). Actions: Run again (demos, S-13), Replay, Pause view, and "⋯" (Share, Export, Checks, Reset session, Clear events). This revises T-7. |
| S-4 | **Dock tabs are questions, not event packages.** *Problems* (what is wrong?), *Inspector* (what is this coroutine?), *Evidence* (what did the resources do?), *Events* (what exactly happened?), *Learn* (how do I read this?). The tab set never changes. Evidence holds T-5's sections, each collapsed with a one-line summary: Threads (the grouped table of T-5), Dispatchers, Locks and permits, Channels, Flows, Jobs. Every section is always listed: one with nothing captured says "none captured" and, for a real session, what instrumentation unlocks it (D-5, today's `LockedPanel`). The Evidence tab label names what it holds ("Evidence · 1 lock, 1 semaphore"). |
| S-5 | **What opens first.** A demo started from the Gallery: Tree + Learn (story, when the demo has a guide). A finished demo: Tree + Inspector (the session summary of T-3 when nothing is selected). A session with a detected problem: Tree + Problems. An attached app: Tree (grouped, S-12) + Problems. Replay: as the user left it. After the first visit, the last tab is remembered per session. |
| S-6 | **Honest labels.** Every node shows its recorded state on one line (Active, Suspended, Waiting for children, Completed, Failed, Cancelled — never "Running") and, on a second line, what it waits for or holds when lock and permit events show it: "waits for inventory-lock · 1st", "holds inventory-lock", "holds a payment-gateway permit". Queue position is the latest observed queue. Permits are never numbered; when a release cannot be attributed (S-7), the holder line reads "holder unknown". A held lock is neutral, not red. Durations and superlatives say "observed" whenever events were lost or trimmed ("the longest observed wait"). Text that explains rather than records (a guide line, "cancelled after the failure") is visibly marked as explanation. |
| S-7 | **Core fix: waiting is recorded as suspension.** See "Wire contract for S-7" below. Fixes the known follow-up (a channel or lock wait shows ACTIVE) and makes S-6's state line true. |
| S-8 | **The Problems tab presents triage's health, and nothing else.** It shows what `SessionHealthDerivation` (#248) derives, served by the health API of Part 5 (triage PR 2), `GET /api/sessions/{id}/health`, with triage's definitions: **failures** (failed rounds and ungrouped failures) and **possibly blocked** coroutines (an observed running phase over the threshold, `HealthThresholds.POSSIBLY_BLOCKED_NANOS`; not evaluated for demos and replay, with the reason). Below them, as observations and not problems: "Long-lived (n)" (opens the tree filter, T-5) and the checks status with its freshness (T-8). The tab's first line is T-1's sentence: "1 problem detected · as of <t>", the honest "nothing detected in the captured coroutines · as of <t>", or "not evaluated (<reason>)". The tab carries a count badge only when problems exist. "Show" selects the coroutines (S-2) and opens the Inspector in T-3's order for that kind. No problem logic in the frontend (D-8). |
| S-9 | **Learn holds the legend and story mode.** The legend and the structured-concurrency explanation (today's always-open `StructuredConcurrencyInfo`, qualified as in T-6, plus the sampling note of H-4) live here. For a demo that has a guide, Learn offers the story; see "Story anchors" below. Moving on by itself is off by default; later chapters stay hidden until revealed. The first version covers the 7 Basics, whose guides exist. |
| S-10 | **Timeline is the second main view.** One row per coroutine over time, a new multi-row view (today's `CoroutineTimelineView` is a single-coroutine event list and stays in the Inspector). The bar shows the recorded state; a thin band above it shows what the coroutine holds (the lock, a permit), because a coroutine can hold a lock while suspended. A mark ends each finished row. Lock-holder and permit lanes sit under the rows; permit lanes are unnumbered slots laid out for display, and a release with an unknown holder ends its band with an "unknown" mark. Clicking a bar selects it (S-2), and the Inspector sums it up in one sentence. When events were lost (the stream's loss count) or trimmed, the view says "some intervals may be missing" instead of drawing gaps it cannot locate. |
| S-11 | **Every part shows the same moment.** A time bar (step back, play, step forward, a slider labelled in seconds, speed, Exit) sits under the header in replay. Every part shows the cursor's moment, including the Inspector, which today is paused. This carries triage's Modes rules: reads are bound to the cursor (`atSeq`), coroutines whose creation event was trimmed come from the durable summaries, a coroutine that cannot be rebuilt opens a summary-only Inspector ("events unavailable (trimmed)"), and the shared view uses the share endpoints with the same rules. In the timeline, the part after the cursor is outlined. |
| S-12 | **Big apps stay readable.** Round picker and "find in all rounds" as in T-4; the "vizcore connection (n)" toggle as in triage's frontend section. "Group by name" (on by default for attached apps) groups **siblings only** — children of the same parent in the same round whose names differ only in a trailing number (`order-17`, `order-18` → `order-*`) — so ancestry is never hidden. A group shows its count and state mix; expanding it shows its members; selecting it opens a group summary in the Inspector (counts by state, the shared creation site if there is one). Find searches all coroutines (server-side, as T-4), clears filters that would hide the match, expands its group and renders it even past the 200-node cap. When the cap or the 10,000-event live history trims what is shown, the page says so. |
| S-13 | **Run again starts a new session.** The session records which demo it was created for (a new `demoId` on the session, set at creation and returned by the session API; today it lives only in the page address, `use-start-demo.ts:49`). Run again creates a new session for the same demo, starts the run and opens it; the old session is untouched. It replaces today's disabled Run button (`ScenarioControls.tsx:125`) and stays separate from Reset session, which today deletes the session and creates a fresh one (`ScenarioControls.tsx:83`) and keeps doing so. The guide lookup for story mode uses the same `demoId`. |
| S-14 | **Works narrow, works with a keyboard.** Below 1,024 px the dock tabs scroll horizontally, the Inspector's columns stack, and the timeline scrolls inside its box. Tree nodes are buttons (Tab, Enter selects); dock tabs follow the WAI-ARIA tabs pattern (arrow keys, `aria-controls`); the replay slider announces its time ("4.2 seconds of 12.0"). Light mode gets a darker link colour token in `palette.ts` (the light link `#4da3ff` reads 2.6:1 on white); state is always text plus colour. |

### Wire contract for S-7

- `CoroutineSuspended` gains two optional fields, `waitKind` (`MUTEX`, `SEMAPHORE`, `CHANNEL_SEND`,
  `CHANNEL_RECEIVE`) and `resourceId`. Both default to `null`, so older events and producers stay valid; the
  `reason` string carries the same kind for older readers. The frontend treats a missing field as "unknown".
- A wrapper first tries without suspending (`tryLock`, `tryAcquire`, `trySend`, `tryReceive`). Only when that
  fails does it emit `CoroutineSuspended`, then call the suspending operation, then emit `CoroutineResumed`
  when it returns. This avoids racing on `isLocked` or `availablePermits`.
- The wrapper emits `CoroutineResumed` in a `finally` block whenever the wait ends: by returning, or by an
  exception the coroutine may survive (a closed channel, a `withTimeout` around the wait). It emits no
  `CoroutineResumed` only when the tracked coroutine's own job is being cancelled: then its cancellation
  event ends the suspension. In every case the wrapper removes the waiter from its queue (as `VizMutex`
  already does). So a coroutine that goes on running is never left recorded as Suspended.
- The existing constructors and helper signatures of `CoroutineSuspended` stay callable by compiled code: the
  new fields come with a secondary constructor that keeps the old parameter list, and a test calls it.
- Channels: `send`, `receive`, `receiveCatching` and iteration (`for (x in channel)`) are covered. `select`
  clauses are not, and stay ACTIVE (listed under "Later").
- The per-coroutine timeline response and the frontend projection carry the two fields through.
- `VizSemaphore` keeps a count of permits per holding coroutine, not one entry, so a coroutine can hold
  several. A release attributes to the releasing coroutine when it holds a permit; otherwise the release is
  recorded with an unknown holder, never assigned to an arbitrary one (today `VizSemaphore.kt:172`).

### Story anchors

- A guide step gains an optional **anchor**: an event kind, a coroutine label and an occurrence number
  (the 1st, 2nd … such event for that coroutine), plus optional **expected states** at that moment
  (coroutine label → state), like `will-see.json`.
- Chapters follow the guide's step order. Reveal moves the time position to the anchor event.
- Live, before the anchor event has arrived, the chapter says "waiting for this moment" and Reveal is
  disabled. When the anchor event was trimmed, the chapter says so and Reveal is disabled.
- A step may carry a **prediction**: a question, two or three answers and the right one. The answer is
  shown on Reveal.
- A backend test runs each guided demo, resolves every anchor, and checks the states at that moment match
  the expected states, so Reveal is proven to show the intended teaching moment.

## Building it: ten parts

Each part is its own PR, tests first, reviewed by Codex until clean, and checked in a browser on free ports.

| Part | What it delivers | Depends on |
|---|---|---|
| **1 · Quick wins** (frontend) | In today's layout: the tree panel moves above the explanation and the metrics; `StructuredConcurrencyInfo` folds into one line, "How to read the tree", with the legend, closed by default and remembered; a finished demo shows its completed coroutines (when nothing is live); "Running" becomes "Active" and Waiting for children is its own chip; a run with a failure reads "Failed" in red; a held lock is neutral; one LIVE badge. | — |
| **2 · Core fix** (core) | The S-7 wire contract, including `VizSemaphore` holder counts and unknown releases. Also sends exception stack traces, which the common emitter sends empty today. | — |
| **3 · Dock shell** (frontend) | S-1, S-3 (without the connection pill), S-4, S-5 (without Problems), S-14: the layout, the graph filling its box, the header, the five tabs re-hosting today's Inspector, evidence panels (with the grouped thread table of T-5) and events drawer; lifetimes formatted by a new `lib/format-duration.ts` (triage). The Problems tab is hidden until Part 7, and today's Run button stays until Run again arrives in Part 9. | 1 |
| **4 · Shared selection and live facts** (frontend) | S-2 and S-6: one selection across tree, tabs and events; the activity line on nodes; live lock, permit and queue facts that refresh on new events (today `useSyncEvents` reads stored history that live updates do not invalidate, `use-sync-events.ts:57`). | 2, 3 |
| **5 · Health API** (backend) | Triage PR 2 exactly as specified there: ingest of the new fields, `GET …/health` (with `atSeq`), rounds and server-side search, persistence, checks, shares, app connection state. | — (triage PR 1, merged) |
| **6 · Big apps** (frontend) | S-12: rounds and "find in all rounds" (T-4), the vizcore-connection toggle, group by name with the group summary, Find past the cap. | 3, 4, 5 |
| **7 · Problems** (frontend) | S-8 and S-3's connection pill; tree cards gain triage's C-2 fields and observation badges; removes #232's frontend "may be blocked" hint and the standalone `LeakList` (T-5). | 3, 4, 5, 6 |
| **8 · One moment everywhere** (frontend, some backend) | S-11: cursor-bound projections for the Inspector, Evidence and metrics, with triage's trimmed-history fallbacks and the shared view; a timestamp-to-event mapping for the seconds slider (today seeking is by event index, `use-replay.ts:163`). | 4, 5 |
| **9 · Learn and story mode** (frontend + guide content + backend test) | S-9 and S-13: the session's `demoId`, Run again, the anchored guide schema, predictions, the 7 Basics guides, and the anchor test. | 8 |
| **10 · Timeline** (backend + frontend) | S-10: the multi-row view and lanes. The per-coroutine timeline response must include resume, terminal and body-completed events, which it omits today (`ProjectionService.kt:406`). | 2, 8 |

Parts 1, 2 and 5 can be built in parallel; Part 1 ships first so the page improves within days. Triage PR 3
(SDK: watermark, origin, provenance) is needed for "possibly blocked" and the vizcore toggle on real apps and
can be built any time after Part 5.

### Where triage's frontend work goes

| Triage requirement | Owner here |
|---|---|
| Header with two status pills, last data, start time, rung badge (T-2) | Part 3 (view pill), Part 7 (connection pill) |
| Health strip, problem list, "Show" (T-1, T-3) | Part 7 (Problems tab, S-8) |
| Inspector per problem kind: `ExceptionCard`, `ClueCard`, session summary (T-3) | Part 3 (session summary, re-hosted cards), Part 7 (`ExceptionCard`, `ClueCard`) |
| Tree cards with C-2 fields and observation badges | Part 7 |
| `lib/format-duration.ts` for lifetimes | Part 3 |
| Round picker, "find in all rounds" (T-4) | Part 6 |
| "vizcore connection (n)" toggle | Part 6 |
| State bar as the filter; leak chip → "Long-lived (n)"; validation chip removed | Part 1 (moves), Part 7 (Long-lived, removal) |
| Evidence sections collapsed with summaries; grouped thread table (T-5) | Part 3 (Evidence tab) |
| Learn panel (T-6) | Part 3 (legend and explanation), Part 9 (story) |
| Checks status with freshness (T-8) | Part 7 |
| Remove #232's frontend blocked hint and `LeakList` | Part 7 |
| Replay and shared-view rules (Modes) | Part 8 |
| Accessibility rules | S-14, every part |

## Testing

- **Part 1:** vitest for the moved and folded panels, the finished-demo tree, the Failed status and the
  labels; the existing `SessionWorkspace` tests that assert "Nothing running now" are updated.
- **Part 2:** core tests that a contended `lock`, `acquire`, `send`, `receive`, `receiveCatching` and channel
  iteration emit Suspended (with `waitKind` and `resourceId`) then Resumed; that an uncontended call emits
  neither; that a wait ended by a caught closed-channel exception or a caught `withTimeout` emits Resumed;
  that the old `CoroutineSuspended` constructor still works; concurrent handoffs between several waiters in a loop; cancellation while queued leaves no waiter
  and no Resumed; a semaphore held twice by one coroutine; a release by a coroutine that holds none. The
  Channels and Combined demos are re-measured with the pacing audit.
- **Part 3:** vitest for the tab set, the always-listed Evidence sections with their unlock text, keyboard
  resizing and folding. A browser check at 1440 and 900 px wide, dark and light.
- **Part 4:** selection shared across tree, tabs and events, including resource-only events; the activity
  line updating live.
- **Part 5:** the triage spec's backend tests.
- **Part 6:** sibling-only grouping (two parents with `order-*` children stay apart); Find beyond the cap.
- **Part 7:** the triage spec's frontend tests, plus the tab's states (problems, nothing detected, not evaluated).
- **Part 8:** the Inspector at a cursor never shows a later event (a test with a future event in the store);
  a trimmed coroutine opens summary-only.
- **Part 9:** the anchor test described above; vitest for predict, reveal, "waiting for this moment" and
  hidden chapters; Run again leaves the old session untouched.
- **Part 10:** intervals built from a recorded run match its events; holds and states are separate; the
  "may be missing" note appears after a loss.

## Risks

- **The dock moves every panel.** Mitigation: Parts 3, 4 and 6 split the shell, the data wiring and big-app
  features; panels are re-hosted, not rewritten; Part 1 has already moved the tree up.
- **Tabs could hide evidence.** Mitigation: the Evidence tab label names what it holds, sections are always
  listed, and a problem links to the section that explains it.
- **Problems depends on the health API (Part 5),** a backend project of its own. Until it lands, the Problems tab is
  hidden, not empty.
- **Story anchors drift from the demos** when a demo changes. Mitigation: the anchor test fails the build.

## Later

- Who is waiting on what: waiting coroutines linked to the lock, permit or channel and its holder.
- Suspension for `select` clauses.
- Compare two runs: did limiting concurrency help?
- Mark a problem as expected, or dismiss it.
- Story mode for the other demo groups as their guides arrive.
