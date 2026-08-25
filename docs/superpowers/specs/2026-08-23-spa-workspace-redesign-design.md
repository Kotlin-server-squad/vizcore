# SPA Workspace Redesign — fidelity ladder + problems-first workspace

**Date:** 2026-08-23
**Status:** Design — pending review
**Scope:** Sub-project 1 of 5 (product identity & information architecture). Sub-projects 2–5 are
scoped here but specified separately.
**Primary surface:** the React SPA (`frontend/`). The IntelliJ plugin is secondary and follows.

## Problem

vizcore has grown two identities in one codebase, and the frontend is organized around the wrong one.

**Identity A — coroutine teaching lab.** 25+ scenario endpoints (`/api/scenarios/*`, `/api/sync/*`,
`/api/scenarios/patterns/*`), an 839-line `ScenarioRunner`, plus Gallery, Compare, patterns,
anti-patterns, actors and select demos.

**Identity B — real-app coroutine debugger.** `VizcoreClient` / agent → ingest → live session →
source attribution, leak detection, jump-to-source.

The SPA's navigation serves Identity A — `Layout.tsx` is a navbar of Home / Sessions / Scenarios /
Gallery / Compare, and `routes/index.tsx` opens with a 5xl hero headline and three feature cards.
Meanwhile every phase since Phase 6 (all of v1.1 and v1.2) has invested in Identity B. That mismatch
produces four concrete defects:

1. **The shell reads as a marketing site**, not a dev tool, because it was designed to showcase demos.
2. **The workspace is organized by backend event package.** `SessionDetails.tsx` is 920 lines and
   eight tabs — Coroutines / Events / Threads / Channels / Flow / Sync / Jobs / Validation — mirroring
   the six event packages under `coroutine-viz-core/events/` and one hook per family
   (`use-flow-events`, `use-channel-events`, `use-sync-events`, `use-select-events`,
   `use-actor-events`, `use-job-events`). That taxonomy is the implementer's. No developer opens a
   debugger thinking "let me check the Channels tab."
3. **The validated design system was never implemented.** `tailwind.config.js` sets primary to
   `#6366f1` indigo against a validated `#006fee`; `darkMode: "class"` is configured and nothing in
   `main.tsx` or `index.html` ever sets the class, so the "dark-first dev tool" ships light-only;
   `index.css` is 25 lines with no token layer and no JetBrains Mono. The plugin is a third palette
   (JBUI theme colors).
4. **The same domain is computed twice.** `ProblemDerivation.kt`, `RoundGrouping.kt` and
   `RoundTreeModel.kt` exist only in the plugin; `project-coroutines.ts` and
   `project-thread-activity.ts` exist only in the SPA. Nothing binds them, and the plugin has drifted
   ahead with better thinking the SPA never received.

## The spine: one product, three rungs of fidelity

The two identities are not two products. They are two positions on a **fidelity ladder**, and the
rung is set by how much of their own code the developer was willing to change.

| Rung | Setup cost | What is emitted |
|---|---|---|
| **Demo** | zero | all 68 event classes, canned |
| **Attached** | zero code change (agent / DebugProbes) | coroutine lifecycle + source attribution, on the user's app |
| **Instrumented** | swap types (`Mutex` → `VizMutex`, wrapped flows) | contention, backpressure, select, actors, on the user's app |

This is a property of the code, not a product opinion: the Channel, Flow, Mutex, Select, Actor and
Semaphore event families are emitted **only** by the wrappers in
`coroutine-viz-core/wrappers/`. The DebugProbes path (`session/source/debugprobes/`) synthesizes
coroutine lifecycle plus vanish-outcome mapping and source attribution.

**The defect this exposes:** today the ladder is invisible. Tabs are capability-gated on
`eventCategories`, so attaching to a real app silently *shrinks the tab bar*. Nothing tells the user
that a richer view exists or what one-line change would unlock it. The product's entire growth path
is hidden behind a conditional render.

## Goal

One workspace, organized around the questions a developer actually asks, that stays legible at all
three rungs and makes the next rung visible rather than hiding it.

### Non-goals

- No new event types and no instrumentation changes. The data required already exists.
- No rewrite of the visualization internals — `CoroutineTree`, `CoroutineTreeGraph`,
  `CoroutineTimelineView`, `ThreadLanesView` are re-hosted, not re-implemented.
- No deletion of scenario content. The `ScenarioRunner` and its 25+ endpoints are retained; only
  their placement in the IA changes.
- No plugin work in this sub-project. The plugin aligns in sub-project 5.
- No auth, persistence, sharing, or replay behavior changes.

## Decisions

**D-1 — Sessions is home; the workspace is the product.**
`/` currently renders a hero and feature cards. It becomes the sessions list: live and demo sessions,
badged by rung, newest first. Rationale: the first screen should be the way into the work, not an
advertisement for it.

**D-2 — Scenarios stops being a navigation destination.**
It becomes "New demo session" initiated from the sessions list, with Gallery re-hosted as the picker
inside that flow. Rationale: `/scenarios` and `/gallery` are two destinations for one action.

**D-3 — Compare becomes an action, not a destination.**
Nouns are destinations; verbs are actions. Compare is selection-driven — pick two sessions in the
list, open `ComparisonView` (457 lines) as an overlay. Re-hosted, not rewritten.

**D-4 — The workspace opens on a state bar, and the state bar is the filter.**
A single horizontal bar of chips under the metric tiles:
`Running 47 · Suspended 31 · Completed 1.2k · Failed 2 · ⚠ 3 leaks`. Clicking a chip filters the
tree below it. Problems are not a separate surface — they are the chips that turn amber or red when
non-zero.

*Alternative rejected:* a dedicated **problems** strip (the plugin's validated 004-D shape). It opens
on four zeroes for a demo session, and demo is the rung the SPA uniquely serves. Widening it to a
state bar preserves the triage behavior while staying populated and useful at every rung.

*Alternative rejected:* a **question rail** — left-hand navigation by literal question ("what's
stuck?", "who's blocking whom?"). Strongest concept for making the ladder legible, but it commits to
roughly seven bespoke views and real analysis logic behind each, and no dev tool the audience already
trusts navigates by sentences. Its transplantable idea is adopted in D-5 instead.

**D-5 — Empty and locked states name the next rung.**
Where a panel would be empty *because of the current rung* — not because of the current filter — it
states what would appear there and the single change that unlocks it (e.g. "Lock contention appears
here once a `Mutex` is swapped for `VizMutex`"). This is the ladder made visible, and it does the
teaching work that a separate tutorial screen would otherwise need.

**D-6 — One frame for all three rungs.**
Demo, attached and instrumented sessions open the same workspace. The rung changes what is populated
and what is locked, not the layout. Rationale: the demo user is the same person at an earlier moment,
not a different audience; a second screen doubles the design surface for one user.

**D-7 — Detail lives in a right-hand inspector, ordered most-diagnostic-first.**
Timing → suspended-at (with jump-to-source on user frames) → runs-on → identity → events. This
ordering is carried over from validated sketch 005 rather than re-derived.

**D-8 — Shared projections move to the backend.**
The plugin is Kotlin and the SPA is TypeScript; the only place one definition of *problem*, *round*
and *suspension-site* can live without being authored twice is `coroutine-viz-core`, shipped over the
session API. First concrete move: `ProblemDerivation.kt` (109 LOC, currently plugin-only, unreachable
from the SPA) relocates into core and is exposed on the session API. Specified in sub-project 4.

## Workspace layout

```
┌──────────────────────────────────────────────────────────────────────┐
│ checkout-service   [ATTACHED]  pid 48213        ~150 ms   [● LIVE]   │  session header
├──────────────┬──────────────┬──────────────┬─────────────────────────┤
│ Active 47    │ Suspended 31 │ Throughput   │ Dispatcher load 68%     │  metric tiles
│ peak 120     │ 5 over 30 s  │ 310/s        │ IO · 42 of 64           │  (validated 001-B)
├──────────────┴──────────────┴──────────────┴─────────────────────────┤
│ Running 47 · Suspended 31 · Completed 1.2k · Failed 2 · ⚠ 3 leaks    │  state bar = filter (D-4)
│                                          [ Live | All · 12 rounds ]  │
├────────────────────────────────────┬─────────────────────────────────┤
│ tree / graph canvas                │ inspector                       │
│ (filtered by the active chip)      │ timing                          │
│                                    │ suspended at  → jump-to-source  │
│ ● OrderController.placeOrder       │ runs on                         │
│   ⚠ NotificationJob.send  ~31 s    │ identity                        │
│   ● PaymentClient.charge  ~4.2 s   │ events                          │
│                                    │                                 │  (validated 005 order)
└────────────────────────────────────┴─────────────────────────────────┘
```

Retained from validated sketches: metric tiles in a header strip rather than under a tab (001-B/C);
LIVE pill with the ~150 ms poll indicator, and `~`-prefixed durations because sampling is
poll-bounded (001-C); compact source chips expanding to a full stack with user frames bold and
library frames dimmed, every user frame a jump target (002-B); LIVE/DEMO badging on session rows
(003-C); `Live | All · n rounds` segmented control (006-A). A potential leak is amber, never red.

### Where the eight tabs go

| Today | Becomes |
|---|---|
| Coroutines | the canvas — the workspace's default body |
| Events | a drawer on the canvas, filtered by current selection |
| Threads | metric tiles (aggregate) + inspector "runs on" (per-coroutine) |
| Channels / Flow / Sync / Jobs | evidence panels beneath the inspector, shown when the selected coroutine has them; locked-state copy per D-5 when the rung can't produce them |
| Validation | folded into the state bar as problem chips; the rules list moves to session settings |

## Component architecture

`SessionDetails.tsx` (920 lines) decomposes into:

- `SessionWorkspace.tsx` — layout shell and selection state only
- `SessionHeader.tsx` — identity, rung badge, LIVE pill, poll indicator, actions
- `MetricTiles.tsx` — the aggregate strip (extends the existing `SessionMetrics`)
- `StateBar.tsx` — chips, counts, active filter, `Live | All` segment
- `CoroutineCanvas.tsx` — hosts existing `CoroutineTree` / `CoroutineTreeGraph`, applies the filter
- `Inspector/` — `TimingCard`, `SuspendedAtCard`, `RunsOnCard`, `IdentityCard`, `EventsCard`
- `EvidencePanels/` — existing `ChannelPanel`, `FlowPanel`, `SyncPanel`, `JobPanel`, re-hosted
- `LockedPanel.tsx` — the D-5 rung-aware empty state

Existing visualization components are consumed unchanged. The replay, export and share affordances
move into the session header without behavior change.

## Sub-project decomposition

| # | Sub-project | Depends on | Delivers |
|---|---|---|---|
| 1 | Product identity & IA | — | **this spec** |
| 2 | Design token layer | 1 (confirmation only) | one palette/type scale, dark-first, JetBrains Mono; replaces indigo and the never-set dark class |
| 3 | SPA workspace rebuild | 1, 2 | the layout above; `SessionDetails` decomposed |
| 4 | Shared domain projections | 1 | `ProblemDerivation` and round/suspension-site definitions in core, on the API |
| 5 | Plugin alignment | 2, 4 | plugin consumes shared projections and mirrors the IA |

Recommended build order is 2 → 3 → 4 → 5. Sub-project 2 is first code because it carries no remaining
design debate and makes every later change visible in the real app rather than in mockups.

## Testing

- Component tests colocated, per project convention, for `StateBar` filter behavior, `LockedPanel`
  rung logic, and the inspector card ordering.
- The rung-degradation matrix is the critical case: each of demo / attached / instrumented renders the
  workspace with the correct chips populated and the correct panels locked.
- Existing `SessionDetails` tests are ported to `SessionWorkspace` rather than deleted; a passing
  suite before and after the decomposition is the guard against silent behavior loss.

## Risks

- **The state bar reduces to a metrics row** if problem chips are not visually distinct when non-zero.
  Mitigation: chips carry semantic color and only problems change color; zero-problem sessions read as
  a calm state row by design.
- **Leak detection is heuristic.** Placing it at eye level raises the cost of false positives.
  Mitigation: amber not red, and the inspector states the rule that fired.
- **`SessionDetails` decomposition is the highest-risk change** in the sub-project — it is where
  replay, share, export and scenario-specific views are currently entangled. Mitigation: port tests
  first, decompose behind the existing route.

## Carried into sub-project 3 (found during sub-project 2)

Two colour decisions surfaced while implementing the token layer. Both were deliberately **not**
made there, because both are component-level decisions wearing token-level clothing.

**C-1 — `secondary` has no vizcore meaning but 79 live references.** The palette defines no
secondary hue, yet `color="secondary"` is used across ~15 components, and
`src/lib/coroutine-state-colors.ts` maps `WAITING_FOR_CHILDREN` onto it. Neutralising the token
flattens that state into the same grey as `CREATED` and `CANCELLED` — a real loss of information in
the tree and graph. Retiring `secondary` means deciding, per call site, whether it should become a
neutral variant or a palette colour. Existing tests assert Tailwind class names, not resolved
colour, so they do not catch this class of regression.

**C-2 — `coroutine-state-colors.ts` is not palette-backed and returns only Tailwind classes.** It
predates the token layer and diverges from the validated direction in two places: `CANCELLED` is
grey where the direction says amber, and `WAITING_FOR_CHILDREN` is purple. It also cannot serve the
canvas or SVG, which need resolved hex rather than class names. Sub-project 3 should extend this one
module with palette-backed hex accessors rather than adding a second module beside it.

## Open questions

1. Deadline or audience event (defense, demo, Marketplace release)? Affects sequencing only.
2. Any users besides the author? Affects how much continuity the current flow is owed.
3. Does `/scenarios/builder` (the 410-line `ScenarioBuilder`) survive D-2, or move behind the demo
   session flow as an advanced option?
