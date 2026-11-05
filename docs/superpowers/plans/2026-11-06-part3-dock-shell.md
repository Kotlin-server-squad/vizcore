# Part 3 · Dock shell — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the session page into the IDE dock of the design: a header that keeps the run outcome and the view apart, a main view whose graph fills its box, and a resizable, foldable dock of question tabs (Inspector, Evidence, Events, Learn) that re-host today's panels.

**Architecture:** Frontend only. The page becomes a fixed-height column: header → main view (`CoroutineCanvas`, re-hosted as the "Main view" section) → dock (`SessionDock`, sized by `useDockLayout`). Today's panels are moved, not rewritten: the Inspector cards, the evidence panels, the event list and the structured-concurrency text keep their components and gain new frames (tabs, collapsed sections, a session summary). New logic lives in small pure modules under `src/lib/` with table tests.

**Tech Stack:** React 19, TypeScript, HeroUI 2.7, Tailwind (literal classes only, IN-12), Vitest + Testing Library + user-event. Every command runs from `frontend/`.

**Spec:** [`docs/superpowers/specs/2026-11-05-session-dock-design.md`](../specs/2026-11-05-session-dock-design.md) — Part 3 row ("S-1, S-3 (without the connection pill), S-4, S-5 (without Problems), S-14 … lifetimes formatted by a new `lib/format-duration.ts` … Problems tab hidden until Part 7 … today's Run button stays until Part 9"), the Part 3 Testing bullet, Risks, and "Where triage's frontend work goes". It builds on the 2026-08-23 workspace spec (D-5, D-7 revised by S-1) and the 2026-10-21 triage spec (T-2 view pill, T-3 session summary, T-5 evidence sections and grouped thread table, T-6 Learn text, H-1…H-4). Boards: `2026-11-05-session-dock-canvas/G0-Map`, `G2-Story`, `G1-App` (illustrative only; the spec wins).

**Branch:** stacked on Part 1 (PR #290, `feat/session-quick-wins`), which is not merged yet:

```bash
cd /Users/jirihermann/Documents/workspace-vizcore/vizcore-session-dock
git fetch origin
git worktree add -b feat/session-dock-shell ../vizcore-dock origin/feat/session-quick-wins
cd ../vizcore-dock && git branch --unset-upstream && cd frontend && pnpm install
```

Gates (run from `frontend/` before every PR): `pnpm exec tsc --noEmit && pnpm lint && pnpm test && pnpm build`.

---

## Split: two stacked PRs

Part 3 moves every panel on the page and rewrites most of the workspace tests, so it ships as two PRs, each a working page:

| PR | Branch | Delivers | Page after it |
|---|---|---|---|
| **3a · Frame** | `feat/session-dock-shell` (from `feat/session-quick-wins`) | `lib/format-duration.ts`; the run outcome; the header (S-3 without the connection pill); full-width, full-height page; the main view whose graph fills its box; the dock with a keyboard/mouse separator, Fold and Maximise, sizes remembered (S-1) | Header, main view, and a dock holding today's Inspector, Events, metrics, explanation and Evidence stacked in one scrolling body |
| **3b · Tabs** | `feat/session-dock-tabs` (from 3a) | The four tabs (Problems hidden) re-hosting the Inspector with T-3's session summary, the Evidence sections always listed with summaries and the grouped thread table (T-5), Events, Learn (T-6); what opens first and the remembered tab (S-5); narrow widths and keyboard (S-14); lifetimes through `format-duration` | The page of the design, minus Problems, Timeline, story mode and Run again |

Both PRs target `main`; 3a says it is stacked on #290 and 3b says it is stacked on 3a.

## Decisions taken while planning

- **Export stays its own menu next to "⋯".** S-3 lists Export inside "⋯", but `ExportMenu` is itself a menu (PNG / SVG / JSON / Record) with its own logic (SVG detection on open, video support, toasts), and HeroUI menus do not nest. "⋯" holds Share, Checks, Refresh, Reset session and Clear events.
- **Today's Run button keeps its labels and states** ("Run Scenario", "Scenario Running", "Scenario Finished", "Scenario Failed") and moves into the header; Reset and Clear move into "⋯" as "Reset session" and "Clear events". Part 9 replaces the button with Run again.
- **The view pill** (T-2 / S-3): "Updating" (stream on and connected), "Connecting…" or the stream error with Reconnect (stream on, not connected), "View paused" (stream off), "Replay at 4.2 s" (replay; time from the first to the cursor's event), "Loaded at 14:02:31" (shared view). The stream toggle becomes "Pause view" / "Resume view".
- **The dock's size and fold are remembered per browser** (`localStorage` `vizcore.dock`); Maximise is per visit, so nobody comes back to a page with no dock.
- **Separator keys** follow the WAI-ARIA window-splitter pattern with the dock as the pane it sizes (`aria-controls="session-dock"`): ↑/↓ move it 24 px, PageUp/PageDown 96 px, Home gives the dock its smallest size and End its largest, Enter folds or unfolds; folded reads as collapsed (`aria-valuenow` 0). The replay shortcuts no longer fire while a tab or the separator has focus, and Space/Enter on a focused button, link or menu item press it instead of playing (so an Evidence disclosure opens).
- **Nothing in the dock is unmounted by folding, maximising, switching tabs or folding an Evidence section — it is hidden.** The panels read `useSessionEvents` (30 s stale time): an observer mounted later would refetch the history, and in the shared view that read is protected (#124, T-03-22). Hiding keeps today's request profile and keeps every `aria-controls` target in the DOM. The one exception is the Events list, which needs no fetch and is the heaviest render: it mounts only while its tab is shown. A hidden element drops its `flex`/`grid` class, because Tailwind's preflight `[hidden]` rule loses to a display utility.
- **The run's duration** in the outcome pill ("Finished · 12.9 s") is the span of every event the page holds (the history loaded with it plus the events streamed since), shown only once every coroutine is terminal, and reads "at least" when the backend dropped events.
- **The workspace is keyed by session id** in the route, so nothing (selection, dock tab, stream state) carries over when Reset navigates to a fresh session.
- **What opens first (S-5 without Problems):** a demo opened with its scenario (from the Gallery or the new-demo dialog) that has not finished → Learn; anything else (a finished or failed demo, an attached app) → Inspector, whose empty state is the session summary. Replay keeps the tab the user is on. A tab the user picks is remembered per session (`vizcore.dockTabs`, the 50 most recent sessions); an opening tab that was only chosen by the rule is not stored, so a demo opened again after it finished opens on the Inspector.
- **Evidence sections:** Threads and Dispatchers are always disclosures; Locks and permits, Channels, Flows and Jobs are disclosures when captured and otherwise a static row "none captured", plus, for a real session, today's `LockedPanel` unlock line (D-5). The tab label names what was captured ("Evidence · 1 lock, 1 semaphore").
- **The thread table** (T-5) groups thread activity by thread name: JVM threads, coroutines seen, assignments observed, last assignment as time into the capture (since the first retained event), ten rows then "Show all (N)".
- **Learn** hosts today's "How to read the tree" content opened up, with T-6's qualified failure text and H-4's sampling note, marked as explanation; the order-processing and registration pipeline views move there too. The folded `StructuredConcurrencyInfo` and its `vizcore.howToReadTree` key are retired.
- **Light link colour (S-14):** the light theme already uses `primary` `#006fee` for links (4.7:1 on white) and no component uses `#4da3ff`; nothing to change, checked in the browser.
- **The replay slider announces its time (S-14)**: today's slider still seeks by event index (seconds arrive with Part 8), but its accessible value reads "4.2 seconds of 12.0" from the frozen replay snapshot (3b, Task 25).
- **"Last data" and the start time of T-2 wait for the health API.** The session payload carries no wall-clock observation time (only app-clock `tsNanos`), and the browser's response time must not pass for the app's observation time. The ownership table gives Part 3 the view pill; last data and start time join the connection pill in Part 7, from the health payload's `asOf`.
- **Export stays its own menu next to "⋯"** (see above) — a deviation from S-3 for the user to confirm; flattening its four items into "⋯" is possible if preferred.

## File structure

### 3a · Frame

| File | Change | Responsibility |
|---|---|---|
| `src/lib/format-duration.ts` (+ test) | create | lifetimes in words: "200 ms", "2.5 s", "4 m 12 s", "12 h 46 m", "so far", "at least" |
| `src/lib/run-outcome.ts` (+ test) | create | a demo run's outcome (Not started / Running / Finished / Failed) and its recorded span |
| `src/lib/dock-layout.ts` (+ test) | create | dock size limits, clamping, remembered prefs |
| `src/hooks/use-dock-layout.ts` (+ test) | create | dock height, fold, maximise; measures the space it may use |
| `src/components/workspace/DockSeparator.tsx` (+ test) | create | the focusable `separator`: drag and keys |
| `src/components/workspace/SessionDock.tsx` (+ test) | create | the dock frame: separator, bar with Fold, body |
| `src/components/replay/ReplayController.tsx` (+ test) | modify | no replay shortcut while a tab or the separator has focus |
| `src/components/CoroutineTreeGraph.tsx` (+ test) | modify | fills its box instead of `70vh` / 480 px |
| `src/components/CoroutineTree.tsx` | modify | — (scrolls inside the main view's body; no change needed) |
| `src/components/StateBar.tsx` (+ test) | modify | `bare` variant for the main view toolbar |
| `src/components/workspace/CoroutineCanvas.tsx` (+ new test) | modify | becomes the "Main view" section: toolbar, Maximise, a body the graph fills |
| `src/components/workspace/use-scenario-actions.ts` (+ test) | create | Run / Reset / Clear, moved out of `ScenarioControls` |
| `src/components/workspace/RunScenarioButton.tsx` | create | today's Run button, unchanged labels |
| `src/components/workspace/ScenarioControls.tsx` (+ test) | delete | replaced by the two files above and the header |
| `src/components/workspace/SessionHeader.tsx` (+ new test) | rewrite | title, rung, counts, run outcome pill, view pill, actions, "⋯" |
| `src/components/workspace/WorkspaceBody.tsx` (+ test) | delete | replaced by the main view + dock |
| `src/components/SessionWorkspace.tsx` (+ tests) | modify | the three layers; dock body holds today's panels |
| `src/routes/sessions/$sessionId.tsx`, `src/routes/shared.$token.tsx` | modify | full width, full height |

### 3b · Tabs

| File | Change | Responsibility |
|---|---|---|
| `src/components/workspace/Inspector/TimingCard.tsx`, `src/components/LeakList.tsx` | modify | lifetimes through `formatDuration` |
| `src/components/workspace/DockTabs.tsx` (+ test) | create | WAI-ARIA tablist with roving focus |
| `src/lib/evidence-summary.ts` (+ test) | create | what was captured, for the tab label and section summaries |
| `src/lib/thread-groups.ts` (+ test) | create | thread activity grouped by thread name |
| `src/components/ThreadTable.tsx` (+ test) | create | the grouped thread table, capped |
| `src/components/ThreadTimeline.tsx` | delete | replaced by `ThreadTable` |
| `src/components/workspace/EvidencePanels.tsx` (+ test) | rewrite | six sections, always listed, collapsed with summaries |
| `src/components/workspace/LearnPanel.tsx` (+ test) | create | legend + explanation + sampling note + pipeline views |
| `src/components/StructuredConcurrencyInfo.tsx` (+ test) | delete | content moves into `LearnPanel` |
| `src/components/workspace/SessionSummary.tsx` (+ test) | create | T-3's empty Inspector: metric tiles and long-lived list |
| `src/components/workspace/Inspector/Inspector.tsx` (+ test) | modify | heading, two columns on wide screens, `emptyState` |
| `src/components/workspace/EventsPanel.tsx` (+ test) | create (from `EventsDrawer`) | the event list as a tab, scoped to the selection |
| `src/components/workspace/EventsDrawer.tsx` (+ test) | delete | replaced by `EventsPanel` |
| `src/lib/dock-tab-memory.ts` (+ test) | create | the opening tab and the remembered tab |
| `src/components/SessionWorkspace.tsx` (+ tests) | modify | tabs in the dock |

---

# PR 3a · Frame

### Task 1: `formatDuration`

**Files:**
- Create: `src/lib/format-duration.ts`
- Test: `src/lib/format-duration.test.ts`

- [ ] **Step 1: Write the failing test** — `src/lib/format-duration.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { formatDuration, formatDurationMs } from './format-duration'

const MS = 1_000_000
const S = 1_000 * MS

describe('formatDuration', () => {
  it.each([
    [0, '0 ms'],
    [0.04 * MS, 'under 0.1 ms'],
    [2.5 * MS, '2.5 ms'],
    [200 * MS, '200 ms'],
    [999.6 * MS, '1.0 s'],
    [2.5 * S, '2.5 s'],
    [12 * S, '12.0 s'],
    [59.96 * S, '1 m 0 s'],
    [252 * S, '4 m 12 s'],
    [3_599.6 * S, '1 h 0 m'],
    [(12 * 3600 + 46 * 60 + 20) * S, '12 h 46 m'],
    [(3 * 86400 + 4 * 3600) * S, '3 d 4 h'],
  ])('%d ns reads %s', (nanos, text) => {
    expect(formatDuration(nanos)).toBe(text)
  })

  it('says "so far" for a coroutine that is still running', () => {
    expect(formatDuration(2.5 * S, { soFar: true })).toBe('2.5 s so far')
  })

  it('says "at least" when events were lost or trimmed', () => {
    expect(formatDuration(12 * S, { atLeast: true })).toBe('at least 12.0 s')
  })

  it('never shows a negative duration', () => {
    expect(formatDuration(-5 * S)).toBe('0 ms')
  })

  it('reads "unavailable" for a value it cannot know (H-3)', () => {
    expect(formatDuration(Number.NaN)).toBe('unavailable')
    expect(formatDuration(Number.POSITIVE_INFINITY)).toBe('unavailable')
  })

  it('takes milliseconds too', () => {
    expect(formatDurationMs(46_000)).toBe('46.0 s')
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/lib/format-duration.test.ts`
Expected: FAIL — cannot resolve `./format-duration`.

- [ ] **Step 3: Implement** — `src/lib/format-duration.ts`:

```ts
/**
 * Lifetimes in words (triage: "2.5 s", "12 h 46 m", "so far", "at least").
 *
 * Two units at most, rounded once at the smallest unit shown, so 59.96 s
 * reads "1 m 0 s" and never "60.0 s". Under a minute the seconds keep one
 * decimal, because the demos' steps are a few hundred milliseconds apart.
 * A value that cannot be known reads "unavailable" (H-3), never "0".
 */
export interface DurationOptions {
  /** Still running: the figure grows ("2.5 s so far"). */
  soFar?: boolean
  /** Events were lost or trimmed: the true figure may be larger. */
  atLeast?: boolean
}

const NANOS_PER_MS = 1_000_000
const MS_PER_S = 1_000

export function formatDuration(nanos: number, options: DurationOptions = {}): string {
  if (!Number.isFinite(nanos)) return 'unavailable'
  const text = words(Math.max(0, nanos) / NANOS_PER_MS)
  const withFloor = options.atLeast ? `at least ${text}` : text
  return options.soFar ? `${withFloor} so far` : withFloor
}

export function formatDurationMs(ms: number, options: DurationOptions = {}): string {
  return formatDuration(ms * NANOS_PER_MS, options)
}

function words(ms: number): string {
  if (ms === 0) return '0 ms'
  if (ms < 0.1) return 'under 0.1 ms'
  if (ms < 10) return `${ms.toFixed(1)} ms`
  if (Math.round(ms) < MS_PER_S) return `${Math.round(ms)} ms`

  const tenths = Math.round(ms / 100)
  if (tenths < 600) return `${(tenths / 10).toFixed(1)} s`

  const seconds = Math.round(ms / MS_PER_S)
  if (seconds < 3_600) return `${Math.floor(seconds / 60)} m ${seconds % 60} s`

  const minutes = Math.round(ms / 60_000)
  if (minutes < 1_440) return `${Math.floor(minutes / 60)} h ${minutes % 60} m`

  const hours = Math.round(ms / 3_600_000)
  return `${Math.floor(hours / 24)} d ${hours % 24} h`
}
```

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/lib/format-duration.test.ts`
Expected: PASS (18 tests).

- [ ] **Step 5: Commit**

```bash
git add src/lib/format-duration.ts src/lib/format-duration.test.ts
git commit -m "feat(frontend): format lifetimes in words with format-duration"
```

---

### Task 2: The run outcome as a pure function

`ScenarioControls` derives Running / Finished / Failed inline; the header needs the same answer, so it moves to `src/lib`.

**Files:**
- Create: `src/lib/run-outcome.ts`
- Test: `src/lib/run-outcome.test.ts`

- [ ] **Step 1: Write the failing test** — `src/lib/run-outcome.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { deriveRunOutcome, recordedSpanNanos } from './run-outcome'
import type { SessionSnapshot, VizEvent } from '@/types/api'

const session = (...states: string[]) =>
  ({
    sessionId: 's-1',
    coroutineCount: states.length,
    eventCount: 0,
    coroutines: states.map((state, i) => ({
      id: `c-${i}`, jobId: `j-${i}`, parentId: null, scopeId: 'scope', label: null, state,
    })),
  }) as unknown as SessionSnapshot

describe('deriveRunOutcome', () => {
  it('is not started before any coroutine is seen', () => {
    expect(deriveRunOutcome(session())).toEqual({ outcome: 'notStarted', failedCount: 0, ended: false })
  })

  it('is running while any coroutine is not terminal', () => {
    expect(deriveRunOutcome(session('COMPLETED', 'SUSPENDED')).outcome).toBe('running')
  })

  it('is finished when every coroutine completed or was cancelled', () => {
    expect(deriveRunOutcome(session('COMPLETED', 'CANCELLED'))).toEqual({ outcome: 'finished', failedCount: 0, ended: true })
  })

  it('is failed as soon as one coroutine failed, even while others run (S-3)', () => {
    expect(deriveRunOutcome(session('FAILED', 'ACTIVE'))).toEqual({ outcome: 'failed', failedCount: 1, ended: false })
    expect(deriveRunOutcome(session('FAILED', 'FAILED', 'CANCELLED'))).toEqual({ outcome: 'failed', failedCount: 2, ended: true })
  })
})

describe('recordedSpanNanos', () => {
  const ev = (seq: number, tsNanos: number) => ({ seq, tsNanos, kind: 'CoroutineCreated' }) as unknown as VizEvent

  it('is the time from the first to the last recorded event', () => {
    expect(recordedSpanNanos([ev(1, 5_000), ev(2, 12_000), ev(3, 9_000)])).toBe(7_000)
  })

  it('spans every list it is given: the history loaded with the page and the events streamed since', () => {
    expect(recordedSpanNanos([ev(1, 5_000), ev(2, 6_000)], [ev(3, 9_000)])).toBe(4_000)
  })

  it('is unknown with fewer than two events', () => {
    expect(recordedSpanNanos([])).toBeNull()
    expect(recordedSpanNanos([ev(1, 5_000)])).toBeNull()
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/lib/run-outcome.test.ts`
Expected: FAIL — cannot resolve `./run-outcome`.

- [ ] **Step 3: Implement** — `src/lib/run-outcome.ts`:

```ts
import { CoroutineState, type SessionSnapshot, type VizEvent } from '@/types/api'

/** Terminal coroutine states — no further transitions expected. */
const TERMINAL_STATES = new Set<string>([
  CoroutineState.COMPLETED,
  CoroutineState.CANCELLED,
  CoroutineState.FAILED,
])

/**
 * A demo run's outcome (spec S-3):
 *   notStarted — no coroutines seen yet
 *   failed     — at least one coroutine FAILED, whether or not others still
 *                run. Never green "completed": a run that failed something did
 *                not succeed, and a supervisor's surviving children do not undo it.
 *   running    — none failed, at least one is not terminal
 *   finished   — all terminal, none failed
 */
export type RunOutcome = 'notStarted' | 'running' | 'finished' | 'failed'

export interface RunOutcomeFacts {
  outcome: RunOutcome
  failedCount: number
  /** Every coroutine is terminal: nothing more will happen in this run. */
  ended: boolean
}

export function deriveRunOutcome(session: SessionSnapshot): RunOutcomeFacts {
  if (session.coroutineCount === 0 || session.coroutines.length === 0) {
    return { outcome: 'notStarted', failedCount: 0, ended: false }
  }
  const failedCount = session.coroutines.filter(c => c.state === CoroutineState.FAILED).length
  const ended = session.coroutines.every(c => TERMINAL_STATES.has(c.state))
  if (failedCount > 0) return { outcome: 'failed', failedCount, ended }
  return { outcome: ended ? 'finished' : 'running', failedCount: 0, ended }
}

/**
 * From the first to the last event the page holds, across every list given
 * (the history loaded with the page and the events streamed since), on the
 * app's own clock. Null when there is nothing to measure. Event timestamps are
 * compared with each other only, never with the browser's clock.
 */
export function recordedSpanNanos(...lists: ReadonlyArray<readonly VizEvent[]>): number | null {
  let first = Infinity
  let last = -Infinity
  for (const events of lists) {
    for (const e of events) {
      if (!Number.isFinite(e.tsNanos)) continue
      if (e.tsNanos < first) first = e.tsNanos
      if (e.tsNanos > last) last = e.tsNanos
    }
  }
  return last > first ? last - first : null
}
```

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/lib/run-outcome.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/lib/run-outcome.ts src/lib/run-outcome.test.ts
git commit -m "refactor(frontend): derive a demo run's outcome in one place"
```

---

### Task 3: Dock layout rules

**Files:**
- Create: `src/lib/dock-layout.ts`
- Test: `src/lib/dock-layout.test.ts`

- [ ] **Step 1: Write the failing test** — `src/lib/dock-layout.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest'
import {
  DOCK_MIN_PX,
  DOCK_STORAGE_KEY,
  MAIN_MIN_PX,
  clampDockHeight,
  dockMaxHeight,
  initialDockHeight,
  readDockPrefs,
  writeDockPrefs,
} from './dock-layout'

beforeEach(() => localStorage.clear())

describe('dock size', () => {
  it('starts at 40 % of the window (S-1)', () => {
    expect(initialDockHeight(800)).toBe(320)
  })

  it('leaves the main view at least 320 px of the space it shares with the dock', () => {
    expect(dockMaxHeight(900)).toBe(900 - MAIN_MIN_PX - 12)
  })

  it('has no upper limit until the space is measured', () => {
    expect(dockMaxHeight(0)).toBe(Number.POSITIVE_INFINITY)
  })

  it('never goes below the smallest useful dock, even in a tiny window', () => {
    expect(dockMaxHeight(300)).toBe(DOCK_MIN_PX)
    expect(clampDockHeight(10, 600)).toBe(DOCK_MIN_PX)
  })

  it('clamps a height to the space available', () => {
    expect(clampDockHeight(1_000, 900)).toBe(900 - MAIN_MIN_PX - 12)
    expect(clampDockHeight(300, 900)).toBe(300)
    expect(clampDockHeight(300.6, 0)).toBe(301)
  })
})

describe('remembered dock prefs', () => {
  it('reads nothing when nothing was stored', () => {
    expect(readDockPrefs()).toEqual({ height: null, folded: false })
  })

  it('round-trips the height and the fold', () => {
    writeDockPrefs({ height: 280, folded: true })
    expect(readDockPrefs()).toEqual({ height: 280, folded: true })
  })

  it('ignores a stored value it cannot read', () => {
    localStorage.setItem(DOCK_STORAGE_KEY, '{not json')
    expect(readDockPrefs()).toEqual({ height: null, folded: false })
    localStorage.setItem(DOCK_STORAGE_KEY, JSON.stringify({ height: 'tall', folded: 'yes' }))
    expect(readDockPrefs()).toEqual({ height: null, folded: false })
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/lib/dock-layout.test.ts`
Expected: FAIL — cannot resolve `./dock-layout`.

- [ ] **Step 3: Implement** — `src/lib/dock-layout.ts`:

```ts
/**
 * The dock's size rules (spec S-1): it starts at 40 % of the window, the main
 * view keeps at least 320 px, and the size and the fold are remembered per
 * browser. Maximise is not remembered: coming back to a page with no dock
 * would hide the Inspector with no hint of where it went.
 */

/** The main view's smallest height (S-1). */
export const MAIN_MIN_PX = 320
/** The smallest dock that still shows a line of its content under the tab row. */
export const DOCK_MIN_PX = 120
/** The gap between the main view and the dock (`gap-3`). */
const GAP_PX = 12
/** One arrow-key step of the separator; PageUp/PageDown move four. */
export const DOCK_STEP_PX = 24

export const DOCK_STORAGE_KEY = 'vizcore.dock'

export interface DockPrefs {
  /** Null until the user has resized the dock. */
  height: number | null
  folded: boolean
}

export function initialDockHeight(windowHeight: number): number {
  return Math.round(windowHeight * 0.4)
}

/**
 * The largest dock that leaves the main view its minimum, given the height the
 * two share. Zero means "not measured yet" (first render, or jsdom): no limit.
 */
export function dockMaxHeight(sharedHeight: number): number {
  if (sharedHeight <= 0) return Number.POSITIVE_INFINITY
  return Math.max(DOCK_MIN_PX, sharedHeight - MAIN_MIN_PX - GAP_PX)
}

export function clampDockHeight(height: number, sharedHeight: number): number {
  return Math.round(Math.min(dockMaxHeight(sharedHeight), Math.max(DOCK_MIN_PX, height)))
}

export function readDockPrefs(): DockPrefs {
  try {
    const raw = localStorage.getItem(DOCK_STORAGE_KEY)
    if (!raw) return { height: null, folded: false }
    const parsed = JSON.parse(raw) as Partial<Record<keyof DockPrefs, unknown>>
    return {
      height: typeof parsed.height === 'number' && Number.isFinite(parsed.height) ? parsed.height : null,
      folded: parsed.folded === true,
    }
  } catch {
    return { height: null, folded: false }
  }
}

export function writeDockPrefs(prefs: DockPrefs): void {
  try {
    localStorage.setItem(DOCK_STORAGE_KEY, JSON.stringify(prefs))
  } catch {
    // Private mode or storage full: the dock still works for this visit.
  }
}
```

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/lib/dock-layout.test.ts`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add src/lib/dock-layout.ts src/lib/dock-layout.test.ts
git commit -m "feat(frontend): dock size rules, remembered per browser"
```

---

### Task 4: `useDockLayout`

**Files:**
- Create: `src/hooks/use-dock-layout.ts`
- Test: `src/hooks/use-dock-layout.test.ts`

- [ ] **Step 1: Write the failing test** — `src/hooks/use-dock-layout.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useDockLayout } from './use-dock-layout'
import { DOCK_MIN_PX, readDockPrefs, writeDockPrefs } from '@/lib/dock-layout'

beforeEach(() => localStorage.clear())

describe('useDockLayout', () => {
  it('opens at 40 % of the window, unfolded and not maximised', () => {
    const { result } = renderHook(() => useDockLayout())
    expect(result.current.height).toBe(Math.round(window.innerHeight * 0.4))
    expect(result.current.folded).toBe(false)
    expect(result.current.maximised).toBe(false)
  })

  it('opens at the remembered size and fold', () => {
    writeDockPrefs({ height: 260, folded: true })
    const { result } = renderHook(() => useDockLayout())
    expect(result.current.height).toBe(260)
    expect(result.current.folded).toBe(true)
  })

  it('remembers a resize, and resizing unfolds the dock', () => {
    writeDockPrefs({ height: 260, folded: true })
    const { result } = renderHook(() => useDockLayout())
    act(() => result.current.resize(400))
    expect(result.current.height).toBe(400)
    expect(result.current.folded).toBe(false)
    expect(readDockPrefs()).toEqual({ height: 400, folded: false })
  })

  it('never resizes below the smallest dock', () => {
    const { result } = renderHook(() => useDockLayout())
    act(() => result.current.resize(10))
    expect(result.current.height).toBe(DOCK_MIN_PX)
  })

  it('remembers the fold', () => {
    const { result } = renderHook(() => useDockLayout())
    act(() => result.current.toggleFold())
    expect(result.current.folded).toBe(true)
    expect(readDockPrefs().folded).toBe(true)
  })

  it('maximises for this visit only', () => {
    const { result } = renderHook(() => useDockLayout())
    act(() => result.current.toggleMaximise())
    expect(result.current.maximised).toBe(true)
    expect(localStorage.getItem('vizcore.dock')).toBeNull()
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/hooks/use-dock-layout.test.ts`
Expected: FAIL — cannot resolve `./use-dock-layout`.

- [ ] **Step 3: Implement** — `src/hooks/use-dock-layout.ts`:

```ts
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  clampDockHeight,
  dockMaxHeight,
  initialDockHeight,
  readDockPrefs,
  writeDockPrefs,
} from '@/lib/dock-layout'

export interface DockLayout {
  /** The dock's height in px, already clamped to the space measured. */
  height: number
  /** The largest height the space allows; Infinity until measured. */
  maxHeight: number
  folded: boolean
  maximised: boolean
  resize: (height: number) => void
  toggleFold: () => void
  toggleMaximise: () => void
  /** Put on the element the main view and the dock share. */
  sharedRef: (el: HTMLElement | null) => void
}

/**
 * The dock's size, fold and maximise (spec S-1). The height the user asked for
 * is kept as asked; what is shown is clamped to the space the main view and
 * the dock share, so a window that grows back gives the dock its size back.
 */
export function useDockLayout(): DockLayout {
  const [prefs] = useState(readDockPrefs)
  const [wanted, setWanted] = useState(() => prefs.height ?? initialDockHeight(window.innerHeight))
  const [folded, setFolded] = useState(prefs.folded)
  const [maximised, setMaximised] = useState(false)
  const [shared, setShared] = useState(0)

  // Remember size and fold whenever either changes after the first render.
  const first = useRef(true)
  useEffect(() => {
    if (first.current) {
      first.current = false
      return
    }
    writeDockPrefs({ height: wanted, folded })
  }, [wanted, folded])

  const observer = useRef<ResizeObserver | null>(null)
  const sharedRef = useCallback((el: HTMLElement | null) => {
    observer.current?.disconnect()
    observer.current = null
    if (!el || typeof ResizeObserver === 'undefined') return
    observer.current = new ResizeObserver(([entry]) => {
      if (entry) setShared(Math.round(entry.contentRect.height))
    })
    observer.current.observe(el)
  }, [])

  const resize = useCallback(
    (height: number) => {
      setWanted(clampDockHeight(height, shared))
      setFolded(false)
    },
    [shared],
  )

  return {
    height: clampDockHeight(wanted, shared),
    maxHeight: dockMaxHeight(shared),
    folded,
    maximised,
    resize,
    toggleFold: useCallback(() => setFolded(f => !f), []),
    toggleMaximise: useCallback(() => setMaximised(m => !m), []),
    sharedRef,
  }
}
```

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/hooks/use-dock-layout.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/hooks/use-dock-layout.ts src/hooks/use-dock-layout.test.ts
git commit -m "feat(frontend): useDockLayout keeps the dock's size, fold and maximise"
```

---

### Task 5: `DockSeparator`

**Files:**
- Create: `src/components/workspace/DockSeparator.tsx`
- Test: `src/components/workspace/DockSeparator.test.tsx`

- [ ] **Step 1: Write the failing test** — `src/components/workspace/DockSeparator.test.tsx`:

```tsx
import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DockSeparator } from './DockSeparator'

function renderSeparator(props: Partial<Parameters<typeof DockSeparator>[0]> = {}) {
  const onResize = vi.fn()
  const onToggleFold = vi.fn()
  render(
    <DockSeparator controls="session-dock" height={300} maxHeight={500} folded={false} onResize={onResize} onToggleFold={onToggleFold} {...props} />,
  )
  return { separator: screen.getByRole('separator', { name: 'Resize the dock' }), onResize, onToggleFold }
}

describe('DockSeparator', () => {
  it('is a focusable horizontal separator that says how tall the dock is', () => {
    const { separator } = renderSeparator()
    expect(separator).toHaveAttribute('tabindex', '0')
    expect(separator).toHaveAttribute('aria-orientation', 'horizontal')
    expect(separator).toHaveAttribute('aria-valuenow', '300')
    expect(separator).toHaveAttribute('aria-valuemin', '0')
    expect(separator).toHaveAttribute('aria-controls', 'session-dock')
    expect(separator).toHaveAttribute('aria-valuemax', '500')
    expect(separator).toHaveAttribute('aria-valuetext', 'Dock 300 pixels tall')
  })

  it('reports a folded dock as collapsed', () => {
    const { separator } = renderSeparator({ folded: true })
    expect(separator).toHaveAttribute('aria-valuenow', '0')
    expect(separator).toHaveAttribute('aria-valuetext', 'Dock folded')
  })

  it('omits the maximum until the space is measured', () => {
    const { separator } = renderSeparator({ maxHeight: Number.POSITIVE_INFINITY })
    expect(separator).not.toHaveAttribute('aria-valuemax')
  })

  it('moves with the arrow and page keys; Home gives the dock its smallest size, End its largest (APG)', async () => {
    const { separator, onResize } = renderSeparator()
    separator.focus()
    await userEvent.keyboard('{ArrowUp}')
    expect(onResize).toHaveBeenLastCalledWith(324)
    await userEvent.keyboard('{ArrowDown}')
    expect(onResize).toHaveBeenLastCalledWith(276)
    await userEvent.keyboard('{PageUp}')
    expect(onResize).toHaveBeenLastCalledWith(396)
    await userEvent.keyboard('{PageDown}')
    expect(onResize).toHaveBeenLastCalledWith(204)
    await userEvent.keyboard('{Home}')
    expect(onResize).toHaveBeenLastCalledWith(120)
    await userEvent.keyboard('{End}')
    expect(onResize).toHaveBeenLastCalledWith(500)
  })

  it('folds and unfolds with Enter', async () => {
    const { separator, onToggleFold } = renderSeparator()
    separator.focus()
    await userEvent.keyboard('{Enter}')
    expect(onToggleFold).toHaveBeenCalledTimes(1)
  })

  it('follows the mouse: dragging up makes the dock taller', () => {
    const { separator, onResize } = renderSeparator()
    fireEvent.pointerDown(separator, { clientY: 500, pointerId: 1, button: 0 })
    fireEvent.pointerMove(window, { clientY: 450, pointerId: 1 })
    expect(onResize).toHaveBeenLastCalledWith(350)
    fireEvent.pointerUp(window, { clientY: 450, pointerId: 1 })
    fireEvent.pointerMove(window, { clientY: 300, pointerId: 1 })
    expect(onResize).toHaveBeenCalledTimes(1)
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/components/workspace/DockSeparator.test.tsx`
Expected: FAIL — cannot resolve `./DockSeparator`.

- [ ] **Step 3: Implement** — `src/components/workspace/DockSeparator.tsx`:

```tsx
import { useEffect, useRef, type KeyboardEvent, type PointerEvent } from 'react'
import { DOCK_MIN_PX, DOCK_STEP_PX } from '@/lib/dock-layout'

interface DockSeparatorProps {
  /** The id of the pane it sizes: the dock. */
  controls: string
  /** The dock's current height in px. */
  height: number
  /** The largest the dock may be; Infinity until the page has been measured. */
  maxHeight: number
  folded: boolean
  onResize: (height: number) => void
  onToggleFold: () => void
}

/**
 * The handle between the main view and the dock (spec S-1, S-14): a focusable
 * `separator` following the WAI-ARIA window-splitter pattern, with the dock as
 * the pane it sizes. Up makes the dock taller, down smaller; Home gives the
 * dock its smallest size and End its largest; Enter folds or unfolds it, and a
 * folded dock reads as collapsed (0). Dragging follows the pointer.
 */
export function DockSeparator({ controls, height, maxHeight, folded, onResize, onToggleFold }: DockSeparatorProps) {
  const drag = useRef<{ startY: number; startHeight: number } | null>(null)
  const detach = useRef<(() => void) | null>(null)
  useEffect(() => () => detach.current?.(), [])

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const step = { ArrowUp: DOCK_STEP_PX, ArrowDown: -DOCK_STEP_PX, PageUp: 4 * DOCK_STEP_PX, PageDown: -4 * DOCK_STEP_PX }[
      e.key
    ]
    if (step !== undefined) {
      e.preventDefault()
      onResize(height + step)
    } else if (e.key === 'Home') {
      e.preventDefault()
      onResize(DOCK_MIN_PX)
    } else if (e.key === 'End' && Number.isFinite(maxHeight)) {
      e.preventDefault()
      onResize(maxHeight)
    } else if (e.key === 'Enter') {
      e.preventDefault()
      onToggleFold()
    }
  }

  // Moves are read on the window, so a fast drag that leaves the thin handle
  // keeps resizing until the button is released.
  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return
    e.preventDefault()
    drag.current = { startY: e.clientY, startHeight: height }
    const onMove = (m: globalThis.PointerEvent) => {
      const d = drag.current
      if (d) onResize(d.startHeight + (d.startY - m.clientY))
    }
    const onUp = () => detach.current?.()
    detach.current = () => {
      drag.current = null
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      detach.current = null
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
  }

  return (
    <div
      role="separator"
      aria-orientation="horizontal"
      aria-label="Resize the dock"
      aria-controls={controls}
      // Folded is the collapsed position; unfolded, the dock is at least DOCK_MIN_PX.
      aria-valuenow={folded ? 0 : height}
      aria-valuemin={0}
      aria-valuemax={Number.isFinite(maxHeight) ? maxHeight : undefined}
      aria-valuetext={folded ? 'Dock folded' : `Dock ${height} pixels tall`}
      tabIndex={0}
      onKeyDown={onKeyDown}
      onPointerDown={onPointerDown}
      className="group flex h-3 shrink-0 cursor-row-resize touch-none items-center justify-center rounded-medium outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      <span
        aria-hidden="true"
        className="h-1 w-12 rounded-full bg-default-300 transition-colors group-hover:bg-primary group-focus-visible:bg-primary"
      />
    </div>
  )
}
```

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/components/workspace/DockSeparator.test.tsx`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/components/workspace/DockSeparator.tsx src/components/workspace/DockSeparator.test.tsx
git commit -m "feat(frontend): a dock separator you can drag or move with the keyboard"
```

---

### Task 6: `SessionDock`

**Files:**
- Create: `src/components/workspace/SessionDock.tsx`
- Test: `src/components/workspace/SessionDock.test.tsx`

- [ ] **Step 1: Write the failing test** — `src/components/workspace/SessionDock.test.tsx`:

```tsx
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SessionDock } from './SessionDock'

function renderDock(folded = false) {
  const onToggleFold = vi.fn()
  render(
    <SessionDock
      height={300}
      maxHeight={500}
      folded={folded}
      onResize={vi.fn()}
      onToggleFold={onToggleFold}
      bar={<h2>Details</h2>}
    >
      <p>dock body</p>
    </SessionDock>,
  )
  return { onToggleFold }
}

describe('SessionDock', () => {
  it('is a labelled region with a separator, its bar and its body at the size asked', () => {
    renderDock()
    const dock = screen.getByRole('region', { name: 'Dock' })
    expect(dock).toHaveAttribute('id', 'session-dock')
    expect(screen.getByRole('separator')).toHaveAttribute('aria-controls', 'session-dock')
    expect(dock).toHaveStyle({ height: '300px' })
    expect(screen.getByRole('separator', { name: 'Resize the dock' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Details' })).toBeInTheDocument()
    expect(screen.getByText('dock body')).toBeVisible()
  })

  it('folds to its bar, and says so on the Fold button', async () => {
    const { onToggleFold } = renderDock()
    const fold = screen.getByRole('button', { name: 'Fold the dock' })
    expect(fold).toHaveAttribute('aria-expanded', 'true')
    expect(fold).toHaveAttribute('aria-controls', 'dock-body')
    await userEvent.click(fold)
    expect(onToggleFold).toHaveBeenCalledTimes(1)
  })

  it('folded, shows only its bar and offers to unfold', () => {
    renderDock(true)
    const dock = screen.getByRole('region', { name: 'Dock' })
    expect(dock.style.height).toBe('')
    // Hidden, not unmounted: panels keep their state and fetch nothing again (D-17, #124).
    expect(screen.getByText('dock body')).not.toBeVisible()
    const unfold = screen.getByRole('button', { name: 'Unfold the dock' })
    expect(unfold).toHaveAttribute('aria-expanded', 'false')
    expect(unfold).toHaveAttribute('aria-controls', 'dock-body')
  })

  it('is hidden, not unmounted, while the main view is maximised', () => {
    render(
      <SessionDock height={300} maxHeight={500} folded={false} hidden onResize={vi.fn()} onToggleFold={vi.fn()} bar={null}>
        <p>dock body</p>
      </SessionDock>,
    )
    expect(screen.queryByRole('region', { name: 'Dock' })).toBeNull()
    expect(screen.getByText('dock body')).not.toBeVisible()
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/components/workspace/SessionDock.test.tsx`
Expected: FAIL — cannot resolve `./SessionDock`.

- [ ] **Step 3: Implement** — `src/components/workspace/SessionDock.tsx`:

```tsx
import type { ReactNode } from 'react'
import { Button } from '@heroui/react'
import { FiChevronDown, FiChevronUp } from 'react-icons/fi'
import { DockSeparator } from './DockSeparator'

interface SessionDockProps {
  height: number
  maxHeight: number
  folded: boolean
  /** The main view is maximised: the dock is hidden, not unmounted. */
  hidden?: boolean
  onResize: (height: number) => void
  onToggleFold: () => void
  /** The dock's bar: the tab row (3b), with the Fold button added on the right. */
  bar: ReactNode
  children: ReactNode
}

/**
 * The dock under the main view (spec S-1): a separator to resize it, a bar
 * that stays when the dock is folded, and a body that scrolls on its own.
 *
 * Folding and maximising hide the body rather than unmount it: the panels keep
 * their state, and none of them re-subscribes to a query that has gone stale
 * meanwhile — a stale `/events` read would refetch history, and in the shared
 * view it would be a protected request (#124, T-03-22).
 */
export function SessionDock({
  height,
  maxHeight,
  folded,
  hidden = false,
  onResize,
  onToggleFold,
  bar,
  children,
}: SessionDockProps) {
  return (
    <section
      id="session-dock"
      aria-label="Dock"
      hidden={hidden}
      // `flex` would beat the `[hidden]` rule of Tailwind's preflight, so a
      // hidden dock drops it.
      className={hidden ? 'hidden' : 'flex shrink-0 flex-col rounded-xl border border-default-200 bg-content1'}
      style={folded ? undefined : { height }}
    >
      <DockSeparator
        controls="session-dock"
        height={height}
        maxHeight={maxHeight}
        folded={folded}
        onResize={onResize}
        onToggleFold={onToggleFold}
      />
      <div className="flex min-w-0 items-center gap-2 border-b border-default-200 px-2 pb-1">
        <div className="flex min-w-0 flex-1 items-center">{bar}</div>
        <Button
          size="sm"
          variant="light"
          aria-label={folded ? 'Unfold the dock' : 'Fold the dock'}
          aria-expanded={!folded}
          aria-controls="dock-body"
          startContent={folded ? <FiChevronUp aria-hidden /> : <FiChevronDown aria-hidden />}
          onPress={onToggleFold}
        >
          {folded ? 'Unfold' : 'Fold'}
        </Button>
      </div>
      <div id="dock-body" hidden={folded} className="min-h-0 flex-1 overflow-auto p-4">
        {children}
      </div>
    </section>
  )
}
```

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/components/workspace/SessionDock.test.tsx`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/components/workspace/SessionDock.tsx src/components/workspace/SessionDock.test.tsx
git commit -m "feat(frontend): the session dock frame with Fold"
```

---

### Task 7: Replay shortcuts leave the separator and tabs alone

`ReplayController` binds ←/→/Home/End/Space on `document` while replay is on. The separator (and, in 3b, the tabs) use the same keys, so the controller must ignore them while one of those has focus.

**Files:**
- Modify: `src/components/replay/ReplayController.tsx` (`isFormFieldFocused`, ~line 56–65, and its call)
- Test: `src/components/replay/ReplayController.test.tsx`

- [ ] **Step 1: Write the failing test** — append inside the top-level `describe` of `src/components/replay/ReplayController.test.tsx` (it already has a `makeReplay`-style fixture; reuse it — check the file's helper name and use it in place of `mockReplay()` below):

```tsx
  it('leaves the arrow keys to a focused separator or tab', async () => {
    const replay = mockReplay()
    render(
      <>
        <ReplayController replay={replay} />
        <div role="separator" tabIndex={0} aria-label="Resize the dock" />
        <button role="tab" type="button">Inspector</button>
      </>,
    )
    screen.getByRole('separator').focus()
    await userEvent.keyboard('{ArrowRight}{ArrowLeft}{Home}{End}')
    screen.getByRole('tab').focus()
    await userEvent.keyboard('{ArrowRight}{ArrowLeft}{Home}{End}')
    expect(replay.stepForward).not.toHaveBeenCalled()
    expect(replay.stepBack).not.toHaveBeenCalled()
    expect(replay.seekTo).not.toHaveBeenCalled()
  })
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/components/replay/ReplayController.test.tsx`
Expected: FAIL — `stepForward` was called; `play` was called.

- [ ] **Step 3: Implement** — in `src/components/replay/ReplayController.tsx`, replace `isFormFieldFocused` with:

```tsx
/**
 * True when the focused element handles this key itself, so the replay
 * shortcut must be suppressed: a text-entry surface (T-02-13, mirrors
 * standard log-viewer behavior), the dock's separator (arrows resize it), a
 * tab (arrows move between tabs), or — for Space and Enter — a button, link or
 * menu item, which those keys press.
 */
function focusOwnsKey(key: string): boolean {
  const el = document.activeElement
  if (!el) return false
  const tag = el.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  if ((el as HTMLElement).isContentEditable) return true
  const role = el.getAttribute('role')
  if (role === 'separator' || role === 'tab') return true
  const pressable = tag === 'BUTTON' || tag === 'A' || role === 'button' || role === 'menuitem'
  return pressable && (key === ' ' || key === 'Enter')
}
```

and in the handler replace `if (isFormFieldFocused()) return` with `if (focusOwnsKey(e.key)) return`. Add a second test: Space on a focused `<button aria-expanded>` presses it and does not call `play`; ArrowRight from the same button still steps.

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/components/replay/ReplayController.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/components/replay/ReplayController.tsx src/components/replay/ReplayController.test.tsx
git commit -m "fix(frontend): replay shortcuts leave a focused separator or tab alone"
```

---

### Task 8: The graph fills its box

**Files:**
- Modify: `src/components/CoroutineTreeGraph.tsx:279-283` (the non-fullscreen class)
- Test: `src/components/CoroutineTreeGraph.test.tsx`

- [ ] **Step 1: Write the failing test** — append to the top-level `describe` of `src/components/CoroutineTreeGraph.test.tsx` (use the file's existing coroutine fixture; `coroutines` below stands for it):

```tsx
  it('fills the box it is given instead of a fixed share of the window (S-1)', () => {
    render(<CoroutineTreeGraph coroutines={coroutines} />)
    const canvas = screen.getByTestId('graph-canvas')
    expect(canvas.className).toContain('h-full')
    expect(canvas.className).not.toContain('70vh')
    expect(canvas.className).not.toContain('min-h-')
  })
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/components/CoroutineTreeGraph.test.tsx`
Expected: FAIL — the class contains `h-[70vh]`.

- [ ] **Step 3: Implement** — replace the non-fullscreen branch:

```tsx
          : 'relative h-full w-full overflow-hidden rounded-lg border border-default-200 bg-default-50'
```

and above the `className` prop add the comment:

```tsx
      // The main view sizes the canvas (S-1): it fills that box, however small
      // the dock leaves it — a minimum of its own would overflow the box.
```

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/components/CoroutineTreeGraph.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/components/CoroutineTreeGraph.tsx src/components/CoroutineTreeGraph.test.tsx
git commit -m "feat(frontend): the coroutine graph fills its box"
```

---

### Task 9: A bare state bar for the toolbar

**Files:**
- Modify: `src/components/StateBar.tsx` (props + the wrapper `div`)
- Test: `src/components/StateBar.test.tsx`

- [ ] **Step 1: Write the failing test** — append to `src/components/StateBar.test.tsx` (reuse its counts fixture; `counts` below stands for it):

```tsx
  it('drops its own box when it sits in a toolbar', () => {
    render(<StateBar counts={counts} filter="all" onFilterChange={() => {}} bare />)
    const bar = screen.getByRole('group', { name: 'Coroutine state' })
    expect(bar.className).not.toContain('border')
    expect(bar.className).not.toContain('bg-content1')
  })
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/components/StateBar.test.tsx`
Expected: FAIL — the class contains `border`.

- [ ] **Step 3: Implement** — add `bare?: boolean` to the props (documented "In the main view's toolbar the bar sits on the toolbar's surface, so it has no box of its own."), default `false`, and:

```tsx
    <div
      className={
        bare
          ? 'flex flex-wrap items-center gap-2'
          : 'flex flex-wrap items-center gap-2 rounded-medium border border-default-200 bg-content1 px-3 py-2'
      }
      role="group"
      aria-label="Coroutine state"
    >
```

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/components/StateBar.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/components/StateBar.tsx src/components/StateBar.test.tsx
git commit -m "feat(frontend): a bare state bar for the main view toolbar"
```

---

### Task 10: `CoroutineCanvas` becomes the main view

The canvas keeps every derivation it has (empty states, "Show completed", "N more"), and gains the frame of the board: a labelled section with a toolbar (the Graph/List switch moves here from the header, then the state bar), the heading, Maximise, and a body the graph fills.

**Files:**
- Modify: `src/components/workspace/CoroutineCanvas.tsx`
- Create: `src/components/workspace/CoroutineCanvas.test.tsx`

- [ ] **Step 1: Write the failing test** — `src/components/workspace/CoroutineCanvas.test.tsx`:

```tsx
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef } from 'react'
import { CoroutineCanvas } from './CoroutineCanvas'
import type { CoroutineNode } from '@/types/api'

vi.mock('../CoroutineTreeGraph', () => ({
  CoroutineTreeGraph: () => <div data-testid="coroutine-tree-graph" />,
}))
vi.mock('../CoroutineTree', () => ({
  CoroutineTree: () => <div data-testid="coroutine-tree" />,
}))

const node = { id: 'c-1', jobId: 'j-1', parentId: null, scopeId: 's', label: 'a', state: 'ACTIVE' } as CoroutineNode

function renderCanvas(props: Partial<Parameters<typeof CoroutineCanvas>[0]> = {}) {
  const handlers = { onViewModeChange: vi.fn(), onToggleMaximise: vi.fn(), onToggleCompleted: vi.fn() }
  render(
    <CoroutineCanvas
      coroutines={[node]}
      viewMode="graph"
      completedCount={0}
      showCompleted={false}
      moreCount={0}
      stateFilter="all"
      selectedCoroutineId={null}
      panelRef={createRef()}
      maximised={false}
      stateBar={<div data-testid="state-bar" />}
      {...handlers}
      {...props}
    />,
  )
  return handlers
}

describe('CoroutineCanvas — the main view', () => {
  it('is a labelled region with its toolbar first and the canvas filling the rest', () => {
    renderCanvas()
    const main = screen.getByRole('region', { name: 'Main view' })
    expect(main.className).toContain('flex-1')
    expect(screen.getByRole('group', { name: 'View' })).toBeInTheDocument()
    expect(screen.getByTestId('state-bar')).toBeInTheDocument()
    expect(screen.getByText(/What's running now/)).toBeInTheDocument()
    expect(screen.getByTestId('coroutine-tree-graph')).toBeInTheDocument()
  })

  it('switches between the graph and the list', async () => {
    const { onViewModeChange } = renderCanvas()
    expect(screen.getByRole('button', { name: 'Graph' })).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(screen.getByRole('button', { name: 'List' }))
    expect(onViewModeChange).toHaveBeenCalledWith('list')
  })

  it('maximises, and says when it is maximised', async () => {
    const { onToggleMaximise } = renderCanvas()
    const button = screen.getByRole('button', { name: 'Maximise' })
    expect(button).toHaveAttribute('aria-pressed', 'false')
    await userEvent.click(button)
    expect(onToggleMaximise).toHaveBeenCalledTimes(1)
  })

  it('reads "Restore dock" while maximised', () => {
    renderCanvas({ maximised: true })
    expect(screen.getByRole('button', { name: 'Restore dock' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('keeps its empty states inside the main view', () => {
    renderCanvas({ coroutines: [], isDemo: true })
    expect(screen.getByRole('region', { name: 'Main view' })).toHaveTextContent('Run the scenario to see its coroutines here.')
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/components/workspace/CoroutineCanvas.test.tsx`
Expected: FAIL — no region named "Main view".

- [ ] **Step 3: Implement** — rewrite `src/components/workspace/CoroutineCanvas.tsx`. Keep the existing props and their doc comments; add:

```tsx
  onViewModeChange: (next: 'graph' | 'list') => void
  /** The state bar, already bound to the filter (rendered in the toolbar). */
  stateBar: ReactNode
  /** The dock is hidden and the main view takes the page (S-1). */
  maximised: boolean
  onToggleMaximise: () => void
```

Replace the doc comment's first paragraph with: "The main view (spec S-1): the coroutine tree or graph in a box that takes the page's free height, with the view switch, the state bar, the heading and Maximise in its toolbar." Keep the second paragraph ("Presentational — every derivation …"). Then the body:

```tsx
  return (
    <section
      aria-label="Main view"
      className="flex min-h-[320px] min-w-0 flex-1 flex-col rounded-xl border border-default-200 bg-content1"
    >
      <div className="flex flex-wrap items-center gap-2 border-b border-default-200 px-3 py-2">
        <div role="group" aria-label="View" className="flex gap-0.5 rounded-medium bg-default-100 p-0.5">
          <ViewButton label="Graph" pressed={viewMode === 'graph'} onPress={() => onViewModeChange('graph')} />
          <ViewButton label="List" pressed={viewMode === 'list'} onPress={() => onViewModeChange('list')} />
        </div>
        <h2 className="text-sm font-semibold">
          {allFinished ? 'Everything has finished' : <>What&apos;s running now</>}
        </h2>
        {stateBar}
        {completedCount > 0 && !allFinished && (
          <Button size="sm" variant="flat" onPress={onToggleCompleted}>
            {showCompleted ? `Hide completed (${completedCount})` : `Show completed (${completedCount})`}
          </Button>
        )}
        <div className="flex-1" />
        <Button
          size="sm"
          variant="bordered"
          aria-pressed={maximised}
          startContent={maximised ? <FiMinimize2 aria-hidden /> : <FiMaximize2 aria-hidden />}
          onPress={onToggleMaximise}
        >
          {maximised ? 'Restore dock' : 'Maximise'}
        </Button>
      </div>

      {/* Gate on what the canvas will actually render, not on the active set —
          a state filter can select terminal coroutines, and gating on
          activeCoroutines hid them behind the "no app connected" empty state. */}
      <div className="flex min-h-0 flex-1 flex-col p-3">
        {coroutines.length === 0 ? (
          <div className="flex flex-1 items-center justify-center">
            {/* … the three EmptyState branches, unchanged … */}
          </div>
        ) : (
          <>
            {/* The list scrolls inside the box; the graph fills it and pans. */}
            <div ref={panelRef} className={viewMode === 'graph' ? 'min-h-0 flex-1' : 'min-h-0 flex-1 overflow-auto'}>
              {/* … the graph / list branch, unchanged, including its comment … */}
            </div>
            {moreCount > 0 && <div className="mt-2 text-xs text-default-500">{moreCount} more coroutines</div>}
          </>
        )}
      </div>
    </section>
  )
}

function ViewButton({ label, pressed, onPress }: { label: string; pressed: boolean; onPress: () => void }) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onPress}
      className={
        pressed
          ? 'rounded-small bg-content1 px-3 py-1 text-xs font-semibold text-foreground shadow-small'
          : 'rounded-small px-3 py-1 text-xs text-default-500 hover:text-foreground'
      }
    >
      {label}
    </button>
  )
}
```

Imports: `import type { ReactNode, RefObject } from 'react'`, `import { Button } from '@heroui/react'` (drop `Card`, `CardBody`), `import { FiMaximize2, FiMinimize2 } from 'react-icons/fi'`. The three `EmptyState` branches move verbatim into the new centring `div`.

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/components/workspace/CoroutineCanvas.test.tsx`
Expected: PASS (5 tests). `SessionWorkspace` does not compile yet (new required props); Task 13 wires it — do not run the whole suite here.

- [ ] **Step 5: Commit**

```bash
git add src/components/workspace/CoroutineCanvas.tsx src/components/workspace/CoroutineCanvas.test.tsx
git commit -m "feat(frontend): the coroutine canvas becomes the main view with its toolbar"
```

---

### Task 11: Scenario actions out of `ScenarioControls`

**Files:**
- Create: `src/components/workspace/use-scenario-actions.ts`, `src/components/workspace/RunScenarioButton.tsx`
- Create: `src/components/workspace/use-scenario-actions.test.tsx`
- Delete: `src/components/workspace/ScenarioControls.tsx`, `src/components/workspace/ScenarioControls.test.tsx`

- [ ] **Step 1: Write the failing test** — `src/components/workspace/use-scenario-actions.test.tsx` (ported from `ScenarioControls.test.tsx`; the outcome texts are covered by `run-outcome.test.ts` and the header test):

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, render, renderHook, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApiError } from '@/lib/api-errors'

const navigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => navigate }))

const runScenario = vi.fn()
const deleteSession = vi.fn()
const createSession = vi.fn()
vi.mock('@/hooks/use-scenarios', () => ({
  useRunScenario: () => ({ mutateAsync: runScenario, isPending: false }),
}))
vi.mock('@/hooks/use-sessions', () => ({
  useCreateSession: () => ({ mutateAsync: createSession, isPending: false }),
  useDeleteSession: () => ({ mutateAsync: deleteSession, isPending: false }),
}))

import { useScenarioActions } from './use-scenario-actions'
import { RunScenarioButton } from './RunScenarioButton'

const args = (over = {}) => ({
  sessionId: 's-1',
  scenarioId: 'sync/mutex/deadlock-demo',
  scenarioName: 'Deadlock',
  onClearEvents: vi.fn(),
  onRefetch: vi.fn(),
  ...over,
})

beforeEach(() => vi.clearAllMocks())

describe('useScenarioActions', () => {
  it('runs the demo the session was opened for, in that session', async () => {
    runScenario.mockResolvedValue({ sessionId: 's-1' })
    const { result } = renderHook(() => useScenarioActions(args()))
    await act(() => result.current.run())
    expect(runScenario).toHaveBeenCalledWith({ scenarioId: 'sync/mutex/deadlock-demo', sessionId: 's-1' })
  })

  it('says why a run failed, e.g. the server is busy (#139)', async () => {
    runScenario.mockRejectedValue(
      new ApiError(503, 'Server busy: too many demos running, try again in a few seconds', 'demo-limit'),
    )
    const { result } = renderHook(() => useScenarioActions(args()))
    await act(() => result.current.run())
    expect(result.current.errorText).toBe(
      'Could not run the scenario: Server busy: too many demos running, try again in a few seconds',
    )
  })

  it('clears the live events without deleting the session (WR-05)', () => {
    const a = args()
    const { result } = renderHook(() => useScenarioActions(a))
    act(() => result.current.clear())
    expect(a.onClearEvents).toHaveBeenCalledTimes(1)
    expect(deleteSession).not.toHaveBeenCalled()
  })

  it('resets: deletes this session and opens a fresh one for the same demo', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    createSession.mockResolvedValue({ sessionId: 's-2' })
    const { result } = renderHook(() => useScenarioActions(args()))
    await act(() => result.current.reset())
    expect(deleteSession).toHaveBeenCalledWith('s-1')
    expect(navigate).toHaveBeenCalledWith({
      to: '/sessions/$sessionId',
      params: { sessionId: 's-2' },
      search: { scenarioId: 'sync/mutex/deadlock-demo', scenarioName: 'Deadlock' },
    })
  })

  it('does nothing on reset when the user cancels the confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const { result } = renderHook(() => useScenarioActions(args()))
    await act(() => result.current.reset())
    expect(deleteSession).not.toHaveBeenCalled()
  })
})

describe('RunScenarioButton — today\'s Run button, kept until Run again (Part 9)', () => {
  it.each([
    ['notStarted', 'Run Scenario', false],
    ['running', 'Scenario Running', true],
    ['finished', 'Scenario Finished', true],
    ['failed', 'Scenario Failed', true],
  ] as const)('%s reads %s', (outcome, label, disabled) => {
    render(<RunScenarioButton outcome={outcome} isPending={false} onRun={vi.fn()} />)
    const button = screen.getByRole('button', { name: label })
    if (disabled) expect(button).toBeDisabled()
    else expect(button).toBeEnabled()
  })

  it('runs on press', async () => {
    const onRun = vi.fn()
    render(<RunScenarioButton outcome="notStarted" isPending={false} onRun={onRun} />)
    await userEvent.click(screen.getByRole('button', { name: 'Run Scenario' }))
    expect(onRun).toHaveBeenCalledTimes(1)
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/components/workspace/use-scenario-actions.test.tsx`
Expected: FAIL — cannot resolve `./use-scenario-actions`.

- [ ] **Step 3: Implement**

`src/components/workspace/use-scenario-actions.ts` — the handlers of `ScenarioControls`, moved verbatim with their comments; the error becomes text the header shows:

```ts
import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useRunScenario } from '@/hooks/use-scenarios'
import { useCreateSession, useDeleteSession } from '@/hooks/use-sessions'
import { describeApiError } from '@/lib/api-errors'

interface ScenarioActionArgs {
  sessionId: string
  scenarioId: string
  /** Display name of the scenario; names the fresh session Reset creates. */
  scenarioName?: string
  /** Empties the live event list. Never deletes the session (WR-05). */
  onClearEvents: () => void
  onRefetch: () => void
}

export interface ScenarioActions {
  run: () => Promise<void>
  reset: () => Promise<void>
  clear: () => void
  isRunPending: boolean
  isResetPending: boolean
  /** Why the last action failed, in a sentence (#139); null when it did not. */
  errorText: string | null
}

/**
 * Run / Reset / Clear for a scenario-backed session (they were the
 * `ScenarioControls` card; the header now offers them, S-3).
 *
 * Mutation hooks only: nothing is requested until an action is invoked, and
 * the caller offers none of them in the read-only shared view (T-03-22).
 */
export function useScenarioActions({
  sessionId,
  scenarioId,
  scenarioName,
  onClearEvents,
  onRefetch,
}: ScenarioActionArgs): ScenarioActions {
  const runScenario = useRunScenario()
  const deleteSession = useDeleteSession()
  const createSession = useCreateSession()
  const navigate = useNavigate()
  const [actionError, setActionError] = useState<{ action: string; error: unknown } | null>(null)

  const run = async () => {
    setActionError(null)
    try {
      await runScenario.mutateAsync({ scenarioId, sessionId })
      // Refetch immediately after running
      setTimeout(() => onRefetch(), 500)
    } catch (error) {
      setActionError({ action: 'Run the scenario', error })
    }
  }

  // Clear ONLY empties the live event list (WR-05) — it must never delete
  // the session. Reset (below) remains the destructive delete-and-restart.
  const clear = () => {
    onClearEvents()
    onRefetch()
  }

  // Reset = "clear all coroutines and start fresh": delete this session, then
  // open a fresh session for the same scenario. It used to navigate to
  // /scenarios, which only redirects back to the sessions list (#139).
  const reset = async () => {
    if (!confirm('Reset this session? This will clear all coroutines and start fresh.')) {
      return
    }
    setActionError(null)
    try {
      await deleteSession.mutateAsync(sessionId)
    } catch (error) {
      setActionError({ action: 'Delete this session', error })
      return
    }
    const name = scenarioName ?? scenarioId
    try {
      const fresh = await createSession.mutateAsync(`scenario-${name}`)
      void navigate({
        to: '/sessions/$sessionId',
        params: { sessionId: fresh.sessionId },
        search: { scenarioId, scenarioName: name },
      })
    } catch {
      // The old session is gone; with no fresh one the list is the only
      // sensible place left, and it shows its own errors.
      void navigate({ to: '/' })
    }
  }

  return {
    run,
    reset,
    clear,
    isRunPending: runScenario.isPending,
    isResetPending: deleteSession.isPending || createSession.isPending,
    errorText: actionError
      ? `Could not ${actionError.action.toLowerCase()}: ${describeApiError(actionError.error, 'This session').message}`
      : null,
  }
}
```

`src/components/workspace/RunScenarioButton.tsx`:

```tsx
import { Button } from '@heroui/react'
import { FiPlay } from 'react-icons/fi'
import type { RunOutcome } from '@/lib/run-outcome'

/**
 * Today's Run button, moved into the header with its labels and states
 * unchanged (spec Part 3: it stays until Run again arrives in Part 9).
 */
export function RunScenarioButton({
  outcome,
  isPending,
  onRun,
}: {
  outcome: RunOutcome
  isPending: boolean
  onRun: () => void
}) {
  if (outcome === 'finished' || outcome === 'failed') {
    return (
      <Button
        size="sm"
        color={outcome === 'failed' ? 'danger' : 'success'}
        variant="flat"
        startContent={<FiPlay aria-hidden />}
        isDisabled
      >
        {outcome === 'failed' ? 'Scenario Failed' : 'Scenario Finished'}
      </Button>
    )
  }
  return (
    <Button
      size="sm"
      color="primary"
      startContent={<FiPlay aria-hidden />}
      onPress={onRun}
      isLoading={isPending}
      isDisabled={outcome === 'running'}
    >
      {outcome === 'running' ? 'Scenario Running' : 'Run Scenario'}
    </Button>
  )
}
```

Delete `ScenarioControls.tsx` and `ScenarioControls.test.tsx` (`git rm`).

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/components/workspace/use-scenario-actions.test.tsx`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit** (the workspace still imports `ScenarioControls`; Task 13 removes that — commit this together with Task 12 and 13 if `tsc` must stay green per commit. Otherwise:)

```bash
git add -A src/components/workspace
git commit -m "refactor(frontend): scenario Run/Reset/Clear as a hook and a header button"
```

---

### Task 12: The header (S-3, without the connection pill)

**Files:**
- Rewrite: `src/components/workspace/SessionHeader.tsx`
- Create: `src/components/workspace/SessionHeader.test.tsx`

The header shows, left to right: the title and rung badge; the counts ("5 coroutines · 42 events"); the **run outcome** pill (demos only); the **view** pill (with Reconnect and the dropped-events chip when relevant; in replay, the "N new events" badge); then actions: the Run button (demos), Replay / Exit replay, Pause view / Resume view (not in a shared view), Export, and "⋯".

- [ ] **Step 1: Write the failing test** — `src/components/workspace/SessionHeader.test.tsx`:

```tsx
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef } from 'react'
import { SessionHeader } from './SessionHeader'
import type { SessionSnapshot } from '@/types/api'

vi.mock('@/hooks/use-capabilities', () => ({ useCapabilities: () => ({ data: { sharingEnabled: true } }) }))
vi.mock('../export/ExportMenu', () => ({ ExportMenu: () => <div data-testid="export-menu" /> }))

const session = { sessionId: 's-1', coroutineCount: 5, eventCount: 42, coroutines: [] } as unknown as SessionSnapshot

function renderHeader(props: Partial<Parameters<typeof SessionHeader>[0]> = {}) {
  const handlers = {
    onToggleStream: vi.fn(),
    onToggleReplay: vi.fn(),
    onExitReplay: vi.fn(),
    onReconnect: vi.fn(),
    onRefetch: vi.fn(),
    onOpenShares: vi.fn(),
    onOpenChecks: vi.fn(),
  }
  render(
    <SessionHeader
      sessionId="s-1"
      session={session}
      title="Deadlock"
      rung="demo"
      readOnly={false}
      outcome={null}
      runSpanNanos={null}
      streamEnabled
      isConnected
      replayActive={false}
      replayAtNanos={null}
      newEventsCount={0}
      loadedAtMs={0}
      panelRef={createRef()}
      panelEvents={[]}
      viewMode="graph"
      {...handlers}
      {...props}
    />,
  )
  return handlers
}

describe('SessionHeader', () => {
  it('names the session, its rung and its counts', () => {
    renderHeader()
    expect(screen.getByRole('heading', { name: 'Deadlock' })).toBeInTheDocument()
    expect(screen.getByTestId('rung-badge')).toHaveTextContent('DEMO')
    expect(screen.getByText('5 coroutines')).toBeInTheDocument()
    expect(screen.getByText('42 events')).toBeInTheDocument()
  })

  describe('the run outcome (demos only)', () => {
    it('is absent for an attached app', () => {
      renderHeader({ outcome: null })
      expect(screen.queryByTestId('run-outcome')).toBeNull()
    })

    it('reads Finished with how long the run took', () => {
      renderHeader({ outcome: { outcome: 'finished', failedCount: 0, ended: true }, runSpanNanos: 12e9 })
      expect(screen.getByTestId('run-outcome')).toHaveTextContent('Finished · 12.0 s')
    })

    it('reads Failed in red with how many failed — never green Completed (S-3)', () => {
      renderHeader({ outcome: { outcome: 'failed', failedCount: 2, ended: true }, runSpanNanos: 3e9 })
      const pill = screen.getByTestId('run-outcome')
      expect(pill).toHaveTextContent('Failed · 2 coroutines failed · 3.0 s')
      expect(pill.className).toContain('danger')
    })

    it('reads Running', () => {
      renderHeader({ outcome: { outcome: 'running', failedCount: 0, ended: false }, runSpanNanos: 4e9 })
      expect(screen.getByTestId('run-outcome')).toHaveTextContent(/^Running$/)
    })

    it('gives a failed run no duration while some of it still runs', () => {
      renderHeader({ outcome: { outcome: 'failed', failedCount: 1, ended: false }, runSpanNanos: 3e9 })
      expect(screen.getByTestId('run-outcome')).toHaveTextContent(/^Failed · 1 coroutine failed$/)
    })

    it('says "at least" when the backend dropped events', () => {
      renderHeader({ outcome: { outcome: 'finished', failedCount: 0, ended: true }, runSpanNanos: 12e9, droppedCount: 2 })
      expect(screen.getByTestId('run-outcome')).toHaveTextContent('Finished · at least 12.0 s')
    })
  })

  describe('the view (T-2)', () => {
    it('reads Updating while the stream is connected, and offers Pause view', async () => {
      const { onToggleStream } = renderHeader()
      expect(screen.getByTestId('view-status')).toHaveTextContent('Updating')
      await userEvent.click(screen.getByRole('button', { name: 'Pause view' }))
      expect(onToggleStream).toHaveBeenCalledTimes(1)
    })

    it('reads View paused with the stream off, and offers Resume view', () => {
      renderHeader({ streamEnabled: false, isConnected: false })
      expect(screen.getByTestId('view-status')).toHaveTextContent('View paused')
      expect(screen.getByRole('button', { name: 'Resume view' })).toBeInTheDocument()
    })

    it('says what is wrong with the stream and offers Reconnect (#137)', async () => {
      const { onReconnect } = renderHeader({ isConnected: false, streamError: 'Live updates stopped' })
      expect(screen.getByTestId('view-status')).toHaveTextContent('Live updates stopped')
      await userEvent.click(screen.getByRole('button', { name: 'Reconnect' }))
      expect(onReconnect).toHaveBeenCalledTimes(1)
    })

    it('counts the events the backend dropped', () => {
      renderHeader({ droppedCount: 3 })
      expect(screen.getByTestId('dropped-count')).toHaveTextContent('3 events dropped')
    })

    it('reads Replay at the cursor, with the new-events badge that exits replay', async () => {
      const { onExitReplay } = renderHeader({ replayActive: true, replayAtNanos: 4.2e9, newEventsCount: 2 })
      expect(screen.getByTestId('view-status')).toHaveTextContent('Replay at 4.2 s')
      expect(screen.getByRole('button', { name: 'Exit replay' })).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Exit replay and jump to live' }))
      expect(onExitReplay).toHaveBeenCalledTimes(1)
    })

    it('reads Loaded at in a shared view, which has no live controls', () => {
      renderHeader({ readOnly: true, loadedAtMs: new Date(2026, 10, 6, 14, 2, 31).getTime() })
      expect(screen.getByTestId('view-status')).toHaveTextContent(/^Loaded at /)
      expect(screen.queryByRole('button', { name: /pause view|resume view/i })).toBeNull()
    })
  })

  describe('actions', () => {
    it('offers Replay and Export', () => {
      renderHeader()
      expect(screen.getByRole('button', { name: 'Replay' })).toBeInTheDocument()
      expect(screen.getByTestId('export-menu')).toBeInTheDocument()
    })

    it('puts Share, Checks and Refresh in "⋯"', async () => {
      const { onOpenShares, onOpenChecks } = renderHeader()
      await userEvent.click(screen.getByRole('button', { name: 'More actions' }))
      const menu = await screen.findByRole('menu')
      expect(within(menu).getByRole('menuitem', { name: /share/i })).toBeInTheDocument()
      expect(within(menu).getByRole('menuitem', { name: /refresh/i })).toBeInTheDocument()
      await userEvent.click(within(menu).getByRole('menuitem', { name: /checks/i }))
      expect(onOpenChecks).toHaveBeenCalledTimes(1)
      expect(onOpenShares).not.toHaveBeenCalled()
    })

    it('adds Reset session and Clear events to "⋯" for a demo, and the Run button', async () => {
      const scenario = { run: vi.fn(), reset: vi.fn(), clear: vi.fn(), isRunPending: false, isResetPending: false, errorText: null }
      renderHeader({ scenario, outcome: { outcome: 'notStarted', failedCount: 0, ended: false } })
      expect(screen.getByRole('button', { name: 'Run Scenario' })).toBeEnabled()
      await userEvent.click(screen.getByRole('button', { name: 'More actions' }))
      await userEvent.click(await screen.findByRole('menuitem', { name: /clear events/i }))
      expect(scenario.clear).toHaveBeenCalledTimes(1)
    })

    it('shows why a scenario action failed', () => {
      const scenario = {
        run: vi.fn(), reset: vi.fn(), clear: vi.fn(), isRunPending: false, isResetPending: false,
        errorText: 'Could not run the scenario: Server busy',
      }
      renderHeader({ scenario, outcome: { outcome: 'notStarted', failedCount: 0, ended: false } })
      expect(screen.getByRole('alert')).toHaveTextContent('Could not run the scenario: Server busy')
    })

    it('offers no Share in a shared view (ADR-019)', async () => {
      renderHeader({ readOnly: true })
      await userEvent.click(screen.getByRole('button', { name: 'More actions' }))
      const menu = await screen.findByRole('menu')
      expect(within(menu).queryByRole('menuitem', { name: /share/i })).toBeNull()
      expect(within(menu).getByRole('menuitem', { name: /checks/i })).toBeInTheDocument()
    })
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/components/workspace/SessionHeader.test.tsx`
Expected: FAIL — props such as `outcome` do not exist / no `run-outcome`.

- [ ] **Step 3: Implement** — rewrite `src/components/workspace/SessionHeader.tsx`:

```tsx
import type { RefObject } from 'react'
import { Button, Chip, Dropdown, DropdownItem, DropdownMenu, DropdownTrigger, Tooltip } from '@heroui/react'
import { FiMoreHorizontal, FiPause, FiPlay, FiRadio } from 'react-icons/fi'
import { useCapabilities } from '@/hooks/use-capabilities'
import { RUNG_LABEL, type Rung } from '@/lib/fidelity-rung'
import { formatDuration } from '@/lib/format-duration'
import type { RunOutcome, RunOutcomeFacts } from '@/lib/run-outcome'
import { ExportMenu } from '../export/ExportMenu'
import { RunScenarioButton } from './RunScenarioButton'
import type { ScenarioActions } from './use-scenario-actions'
import type { SessionSnapshot, VizEvent } from '@/types/api'

interface SessionHeaderProps {
  sessionId: string
  session: SessionSnapshot
  /** The scenario's name, or the app name the session id was minted from (#145). */
  title: string
  /** Which fidelity rung this session is on (D-6). */
  rung: Rung
  readOnly: boolean
  /** A demo run's outcome (S-3); null for an attached app. */
  outcome: RunOutcomeFacts | null
  /** From the first to the last event the page holds; shown once the run has ended. */
  runSpanNanos: number | null
  /** Live SSE stream state + toggle. */
  streamEnabled: boolean
  isConnected: boolean
  /** Why the stream is not connected, or null while healthy (#137). */
  streamError?: string | null
  /** Events the backend shed under overload since the stream started (#137). */
  droppedCount?: number
  /** Re-open the stream once its automatic retries are spent. */
  onReconnect?: () => void
  onToggleStream: () => void
  /** Replay state + toggle (D-01). */
  replayActive: boolean
  /** Time from the first event to the replay cursor's, on the app's clock. */
  replayAtNanos: number | null
  newEventsCount: number
  onToggleReplay: () => void
  onExitReplay: () => void
  /** When the shared view loaded its data (epoch ms). */
  loadedAtMs: number
  onRefetch: () => void
  onOpenShares: () => void
  /** Opens the session-checks report (M-3). */
  onOpenChecks: () => void
  /** Run / Reset / Clear — only for a scenario session outside the shared view. */
  scenario?: ScenarioActions
  /** ExportMenu wiring (ADR-018 / EXPT-01/02 / D-22). */
  panelRef: RefObject<HTMLDivElement | null>
  panelEvents: VizEvent[]
  viewMode: 'graph' | 'list'
  onRecord?: () => void
}

/**
 * The session's header (spec S-3, which revises triage T-7). It keeps three
 * facts apart: the run outcome of a demo (Running, Finished, Failed — a run
 * with any failed coroutine is Failed, in red), the app connection (a pill
 * that arrives with the health API in Part 7, so none is shown yet) and the
 * view (updating, paused, replay, loaded). Then the actions: Run (demos, until
 * Run again in Part 9), Replay, Pause view, Export and "⋯".
 *
 * Presentational apart from `useCapabilities`, which decides whether Share is
 * offered — nothing else in the workspace reads it.
 */
export function SessionHeader({
  sessionId,
  session,
  title,
  rung,
  readOnly,
  outcome,
  runSpanNanos,
  streamEnabled,
  isConnected,
  streamError = null,
  droppedCount = 0,
  onReconnect,
  onToggleStream,
  replayActive,
  replayAtNanos,
  newEventsCount,
  onToggleReplay,
  onExitReplay,
  loadedAtMs,
  onRefetch,
  onOpenShares,
  onOpenChecks,
  scenario,
  panelRef,
  panelEvents,
  viewMode,
  onRecord,
}: SessionHeaderProps) {
  // Sharing is DB-backed (ADR-019); in memory mode the share routes are absent.
  // Gate the Share item on the server capability so we never offer an action
  // that 404s (explicit false only — stay enabled while still loading).
  const { data: capabilities } = useCapabilities()
  const sharingDisabled = capabilities?.sharingEnabled === false

  return (
    <header className="space-y-2">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <h1 className="text-xl font-bold">{title}</h1>
        {/* Which fidelity rung this session is on (D-6). */}
        <Chip size="sm" variant="flat" color={rung === 'demo' ? 'default' : 'primary'} data-testid="rung-badge">
          {RUNG_LABEL[rung]}
        </Chip>
        {outcome && (
          <OutcomePill
            facts={outcome}
            spanNanos={runSpanNanos}
            // Events the backend shed may have been the first or the last.
            spanIsFloor={droppedCount > 0}
          />
        )}
        <ViewPill
          readOnly={readOnly}
          replayActive={replayActive}
          replayAtNanos={replayAtNanos}
          streamEnabled={streamEnabled}
          isConnected={isConnected}
          streamError={streamError}
          loadedAtMs={loadedAtMs}
        />
        {!readOnly && streamEnabled && !isConnected && streamError && onReconnect && (
          <Button size="sm" variant="light" onPress={onReconnect}>
            Reconnect
          </Button>
        )}
        {droppedCount > 0 && (
          <Tooltip content="The backend shed these events under load, so the view may be missing detail. Structural events (create/complete) are never shed.">
            <Chip color="warning" variant="flat" size="sm" data-testid="dropped-count">
              {droppedCount === 1 ? '1 event dropped' : `${droppedCount} events dropped`}
            </Chip>
          </Tooltip>
        )}
        {/* New-events badge (D-02/D-04): clickable, exits replay + applies
            buffered events. Hidden when N = 0. */}
        {replayActive && newEventsCount > 0 && (
          <button type="button" aria-label="Exit replay and jump to live" onClick={onExitReplay}>
            <Chip size="sm" color="warning" variant="dot">
              {newEventsCount === 1 ? '1 new event' : `${newEventsCount} new events`}
            </Chip>
          </button>
        )}
        <span className="text-sm text-default-500">
          <span>{session.coroutineCount} coroutines</span> · <span>{session.eventCount} events</span>
        </span>

        <div className="flex-1" />

        <div className="flex flex-wrap items-center gap-2">
          {scenario && outcome && (
            <RunScenarioButton outcome={outcome.outcome} isPending={scenario.isRunPending} onRun={() => void scenario.run()} />
          )}
          {/* Replay toggle (D-01) — always available, the shared view included. */}
          <Button
            size="sm"
            color={replayActive ? 'primary' : 'default'}
            variant={replayActive ? 'flat' : 'bordered'}
            startContent={<FiPlay aria-hidden />}
            onPress={onToggleReplay}
          >
            {replayActive ? 'Exit replay' : 'Replay'}
          </Button>
          {/* A shared session is a frozen capture: no live stream to pause (T-03-22). */}
          {!readOnly && (
            <Button
              size="sm"
              variant="bordered"
              startContent={streamEnabled ? <FiPause aria-hidden /> : <FiRadio aria-hidden />}
              onPress={onToggleStream}
            >
              {streamEnabled ? 'Pause view' : 'Resume view'}
            </Button>
          )}
          {/* Export is its own menu (PNG / SVG / JSON / Record): menus do not nest. */}
          <ExportMenu
            getPanelEl={() => panelRef.current}
            sessionId={sessionId}
            sessionName={title}
            events={panelEvents}
            panel={viewMode === 'graph' ? 'graph' : 'tree'}
            onRecord={onRecord}
          />
          <Dropdown>
            <DropdownTrigger>
              <Button size="sm" variant="bordered" isIconOnly aria-label="More actions">
                <FiMoreHorizontal aria-hidden />
              </Button>
            </DropdownTrigger>
            <DropdownMenu
              aria-label="More actions"
              disabledKeys={[
                ...(sharingDisabled ? ['share'] : []),
                ...(scenario?.isResetPending ? ['reset'] : []),
              ]}
              onAction={key => {
                if (key === 'share') onOpenShares()
                else if (key === 'checks') onOpenChecks()
                else if (key === 'refresh') onRefetch()
                else if (key === 'reset') void scenario?.reset()
                else if (key === 'clear') scenario?.clear()
              }}
            >
              {[
                // Owner only: a shared link can never re-share (ADR-019, T-03-22).
                ...(readOnly
                  ? []
                  : [
                      <DropdownItem
                        key="share"
                        description={sharingDisabled ? 'Sharing requires storage.type=database' : undefined}
                      >
                        Share
                      </DropdownItem>,
                    ]),
                // Session checks (M-3) — validation is an action, not a tab.
                <DropdownItem key="checks">Checks</DropdownItem>,
                <DropdownItem key="refresh">Refresh</DropdownItem>,
                ...(scenario
                  ? [
                      <DropdownItem key="reset" description="Deletes this session and starts a fresh one">
                        Reset session
                      </DropdownItem>,
                      <DropdownItem key="clear" description="Empties the live event list; keeps the session">
                        Clear events
                      </DropdownItem>,
                    ]
                  : []),
              ]}
            </DropdownMenu>
          </Dropdown>
        </div>
      </div>
      {scenario?.errorText && (
        <p role="alert" className="text-sm text-danger">
          {scenario.errorText}
        </p>
      )}
    </header>
  )
}

function OutcomePill({
  facts: { outcome, failedCount, ended },
  spanNanos,
  spanIsFloor,
}: {
  facts: RunOutcomeFacts
  spanNanos: number | null
  spanIsFloor: boolean
}) {
  // A duration only once nothing runs any more: a failed run whose other
  // coroutines still run has no length yet.
  const span = ended && spanNanos !== null ? ` · ${formatDuration(spanNanos, { atLeast: spanIsFloor })}` : ''
  const failed = `${failedCount} ${failedCount === 1 ? 'coroutine' : 'coroutines'} failed`
  const text: Record<RunOutcome, string> = {
    notStarted: 'Not run yet',
    running: 'Running',
    finished: `Finished${span}`,
    failed: `Failed · ${failed}${span}`,
  }
  return (
    <Chip
      size="sm"
      variant="dot"
      color={outcome === 'failed' ? 'danger' : outcome === 'running' ? 'primary' : 'default'}
      data-testid="run-outcome"
      title={span ? 'From the first to the last event this page holds' : undefined}
      className={outcome === 'failed' ? 'border-danger text-danger' : undefined}
    >
      {text[outcome]}
    </Chip>
  )
}

function ViewPill({
  readOnly,
  replayActive,
  replayAtNanos,
  streamEnabled,
  isConnected,
  streamError,
  loadedAtMs,
}: {
  readOnly: boolean
  replayActive: boolean
  replayAtNanos: number | null
  streamEnabled: boolean
  isConnected: boolean
  streamError: string | null
  loadedAtMs: number
}) {
  const [text, color] = replayActive
    ? [replayAtNanos !== null ? `Replay at ${formatDuration(replayAtNanos)}` : 'Replay', 'primary' as const]
    : readOnly
      ? [`Loaded at ${new Date(loadedAtMs).toLocaleTimeString()}`, 'default' as const]
      : !streamEnabled
        ? ['View paused', 'default' as const]
        : isConnected
          ? ['Updating', 'success' as const]
          : [streamError ?? 'Connecting…', streamError ? ('danger' as const) : ('warning' as const)]
  return (
    <Chip size="sm" variant="dot" color={color} data-testid="view-status">
      {text}
    </Chip>
  )
}
```

HeroUI `DropdownMenu` accepts an array of `DropdownItem` children; keep `key` on each.

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/components/workspace/SessionHeader.test.tsx`
Expected: PASS (17 tests).

- [ ] **Step 5: Commit** — together with Task 13 (the workspace must adopt the new props for `tsc` to pass).

---

### Task 13: The workspace in three layers

**Files:**
- Modify: `src/components/SessionWorkspace.tsx`
- Delete: `src/components/workspace/WorkspaceBody.tsx`, `src/components/workspace/WorkspaceBody.test.tsx`
- Modify: `src/routes/sessions/$sessionId.tsx`, `src/routes/shared.$token.tsx`
- Test: `src/components/SessionWorkspace.test.tsx`, `src/components/SessionWorkspace.reachability.test.tsx`

- [ ] **Step 1: Write the failing tests** — in `src/components/SessionWorkspace.test.tsx`, add a describe block (below the existing ones):

```tsx
describe('SessionWorkspace — header, main view and dock (S-1)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    mockedUseSession.mockReturnValue({
      data: makeSession({ coroutineCount: 1 }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
  })

  it('stacks the header, the main view and the dock, in that order', () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    const header = screen.getByRole('banner')
    const main = screen.getByRole('region', { name: 'Main view' })
    const dock = screen.getByRole('region', { name: 'Dock' })
    expect(header.compareDocumentPosition(main) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(main.compareDocumentPosition(dock) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('opens the dock at 40 % of the window', () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    expect(screen.getByRole('region', { name: 'Dock' })).toHaveStyle({
      height: `${Math.round(window.innerHeight * 0.4)}px`,
    })
  })

  it('resizes the dock from the keyboard and remembers it', async () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    const start = Math.round(window.innerHeight * 0.4)
    screen.getByRole('separator', { name: 'Resize the dock' }).focus()
    await userEvent.keyboard('{ArrowUp}')
    expect(screen.getByRole('region', { name: 'Dock' })).toHaveStyle({ height: `${start + 24}px` })
    expect(JSON.parse(localStorage.getItem('vizcore.dock')!)).toEqual({ height: start + 24, folded: false })
  })

  it('folds the dock to its bar', async () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: 'Fold the dock' }))
    expect(screen.queryByTestId('inspector-slot')).toBeNull()
    expect(screen.getByRole('button', { name: 'Unfold the dock' })).toBeInTheDocument()
  })

  it('maximise hides the dock; restoring brings it back', async () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: 'Maximise' }))
    expect(screen.queryByRole('region', { name: 'Dock' })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Restore dock' }))
    expect(screen.getByRole('region', { name: 'Dock' })).toBeInTheDocument()
  })

  it('switches the main view between graph and list from its toolbar', async () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: 'List' }))
    expect(screen.getByTestId('coroutine-tree')).toBeInTheDocument()
  })

  it('says Failed in the header for a demo with a failed coroutine', () => {
    mockedUseSession.mockReturnValue({
      data: makeSession({
        coroutineCount: 1,
        coroutines: [{ id: 'c-1', jobId: 'j', parentId: null, scopeId: 's', label: 'x', state: 'FAILED' as CoroutineState }],
      }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    render(<SessionWorkspace sessionId="session-1" scenarioId="demo" scenarioName="Demo" />, { wrapper: createWrapper() })
    expect(screen.getByTestId('run-outcome')).toHaveTextContent('Failed · 1 coroutine failed')
  })
})
```

Then update the existing tests that assert the old layout (each is a one-line change; keep the test's intent):

| Test (file:line today) | Change |
|---|---|
| `'shows "Enable Live Stream" button by default'` (~340) | rename to `'offers Resume view while the view is paused'`; expect `getByRole('button', { name: 'Resume view' })` and `getByTestId('view-status')` with "View paused" |
| `'opens with the live stream on … (startLive, #126)'` (~356) | expect `getByRole('button', { name: 'Pause view' })` instead of "Live Stream Active" |
| `'lets the user switch the live stream OFF …'` (~472) | click `Pause view`; expect `Resume view` and no `Pause view` |
| `'"Clear" clears the live events …'` (~493) | open `More actions`, click menu item `Clear events` |
| `'shows the SSE connection problem …'` (~612) | `stream-status` → `view-status` |
| `'entering replay seeks to end + shows REPLAY chip …'` (~757) | the REPLAY chip is now `view-status` reading `Replay`; keep the controller assertion |
| `'renders no tab bar …'` (three tests, ~1141–1158) | keep (3a still has no tabs; 3b replaces them) |
| `'reaches the canvas, the events drawer, the inspector and the evidence with no navigation'` (~1161) | unchanged: all four sit in the dock body in 3a |
| `'offers the checks report from the header …'` (~1192) | open `More actions`, click menu item `Checks` |
| `'mounts the metric strip below the live canvas'`, `'puts the tree above the explanation'`, `'mounts SessionMetrics exactly once'` | unchanged (the dock body keeps that order) |

In `SessionWorkspace.reachability.test.tsx`: add `useCreateSession` to its `@/hooks/use-sessions` mock (the workspace now calls `useScenarioActions` unconditionally — mutation hooks only, inert until used); the list button is now `'List'` (was `/list view/i`, five places); the test `'renders the workspace body (metric strip + canvas) …'` keeps its assertions and is retitled "renders the main view and the dock (metric strip + canvas) …".

In `SessionWorkspace.request-budget.test.tsx` add, with the file's helpers (real hooks, fake timers):

```tsx
  it('folding, unfolding, maximising and restoring the dock long after load refetch nothing', async () => {
    renderWorkspace()
    await advance(MINUTE)
    const afterLoad = requestCount()

    // Past every query's stale time: a panel that re-subscribed now would refetch.
    fireEvent.click(screen.getByRole('button', { name: 'Fold the dock' }))
    await advance(5_000)
    fireEvent.click(screen.getByRole('button', { name: 'Unfold the dock' }))
    fireEvent.click(screen.getByRole('button', { name: 'Maximise' }))
    await advance(5_000)
    fireEvent.click(screen.getByRole('button', { name: 'Restore dock' }))
    await advance(5_000)

    expect(requestCount() - afterLoad).toBe(0)
  })
```

(it fails with 2 extra requests if the dock unmounts its body); and the four `getByText('Enable Live Stream')` become `getByRole('button', { name: 'Resume view' })`, and `stream-status` becomes `view-status`; the request counts and limits stay as they are.

In `src/routes/first-run-ctas.test.tsx`: the two Reset tests open `More actions` and click the menu item `Reset session`.

The fold test asserts `getByTestId('inspector-slot')` is **not visible** (the body is hidden, not unmounted).

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm vitest run src/components/SessionWorkspace.test.tsx`
Expected: FAIL — no "Main view" region, no `run-outcome`, old prop errors.

- [ ] **Step 3: Implement**

In `src/components/SessionWorkspace.tsx`:

1. Imports: drop `ScenarioControls`, `WorkspaceBody`; add `useDockLayout`, `SessionDock`, `useScenarioActions`, `deriveRunOutcome`, `recordedSpanNanos`, `sessionDisplayName`, `SessionMetrics`, `LeakList`, `useSessionMetrics` is already imported.
2. After `const allEvents = …`, add:

```tsx
  // The dock's size, fold and maximise (S-1), remembered per browser.
  const dock = useDockLayout()
  // Run / Reset / Clear (S-3). Mutation hooks only — nothing is requested until
  // an action is used, and none is offered in the shared view.
  const scenarioActions = useScenarioActions({
    sessionId,
    scenarioId: scenarioId ?? '',
    scenarioName,
    onClearEvents: clearEvents,
    onRefetch: () => void refetch(),
  })
```

   (`refetch` comes from `useSession`, which is declared above this point — move these lines below the `useSession` call.)

3. After `selectedCoroutine`, add:

```tsx
  // A demo's run outcome (S-3); an attached app has none.
  const isDemo = hasScenario || isDemoSessionId(sessionId)
  const outcome = useMemo(() => (isDemo && session ? deriveRunOutcome(session) : null), [isDemo, session])
  // The run's length over everything the page holds: the history loaded with
  // it and whatever streamed in since (either may be missing the other's part).
  const runSpanNanos = useMemo(
    () => recordedSpanNanos(storedEvents ?? [], streamEnabled ? liveEvents : []),
    [storedEvents, streamEnabled, liveEvents],
  )
  // Where the replay cursor is, as time since the first event (the view pill).
  const replayAtNanos = useMemo(() => {
    const visible = replay.visibleEvents
    return replayActive && visible.length > 0 ? visible[visible.length - 1]!.tsNanos - visible[0]!.tsNanos : null
  }, [replayActive, replay.visibleEvents])
```

   and read `dataUpdatedAt` from `useSession` (add it to the destructuring) for `loadedAtMs`.

4. Replace the returned JSX (from `<div className="space-y-6">` to the end) with:

```tsx
  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      <SessionHeader
        sessionId={sessionId}
        session={session}
        title={hasScenario && scenarioName ? scenarioName : sessionDisplayName(sessionId)}
        rung={rung}
        readOnly={readOnly}
        outcome={outcome}
        runSpanNanos={runSpanNanos}
        streamEnabled={streamEnabled}
        isConnected={isConnected}
        streamError={streamError}
        droppedCount={droppedCount}
        onReconnect={reconnect}
        onToggleStream={() => {
          if (streamEnabled) {
            clearEvents()
          }
          setStreamEnabled(!streamEnabled)
        }}
        replayActive={replayActive}
        replayAtNanos={replayAtNanos}
        newEventsCount={newEventsCount}
        onToggleReplay={replayActive ? exitReplay : () => enterReplay()}
        onExitReplay={exitReplay}
        loadedAtMs={dataUpdatedAt}
        onRefetch={() => refetch()}
        onOpenShares={() => setSharesOpen(true)}
        onOpenChecks={() => setChecksOpen(true)}
        // Run/Reset/Clear are mutations, gated OFF in the read-only shared view (T-03-22).
        scenario={scenarioId && !readOnly ? scenarioActions : undefined}
        panelRef={panelRef}
        panelEvents={panelEvents}
        viewMode={viewMode}
        onRecord={recordReplay.canRecord ? recordReplay.startRecording : undefined}
      />

      {/* The ReplayController bar (D-13), directly under the header. While
          recording, the controller shows the red-dot recording cluster and the
          scripted pipeline drives playback (D-06/D-23). */}
      {replayActive && (
        <ReplayController
          replay={replay}
          isRecording={recordReplay.isRecording}
          elapsedMs={recordReplay.elapsedMs}
          onStopRecording={recordReplay.stopRecording}
        />
      )}

      {/* … the three modals (Manage shares, ChecksModal, RecordConfirmModal), unchanged … */}

      {/* The main view and the dock share the rest of the page (S-1). */}
      <div ref={dock.sharedRef} className="flex min-h-[480px] flex-1 flex-col gap-3">
        <CoroutineCanvas
          coroutines={renderedCoroutines}
          viewMode={viewMode}
          onViewModeChange={setViewMode}
          completedCount={completedCount}
          allFinished={allFinished}
          showCompleted={showCompleted}
          onToggleCompleted={() => setShowCompleted(prev => !prev)}
          moreCount={moreCount}
          pendingChildCounts={pendingChildCounts}
          stateFilter={stateFilter}
          stateBar={
            // The state bar (D-4): what the session is doing, and the canvas filter.
            <StateBar
              bare
              counts={stateCounts}
              filter={stateFilter}
              onFilterChange={setStateFilter}
              // Shown only once a run has actually failed something (M-3): a chip
              // reading zero for a session nobody validated is a claim we cannot make.
              action={
                failedChecks > 0
                  ? { label: 'Checks', count: failedChecks, onPress: () => setChecksOpen(true) }
                  : undefined
              }
            />
          }
          isDemo={isDemo}
          selectedCoroutineId={selectedCoroutineId}
          // Selection is LIVE-ONLY (PD-01): replay and the read-only shared
          // view stay presentational, with no added interactivity.
          onSelect={isLiveView ? setSelectedCoroutineId : undefined}
          panelRef={panelRef}
          maximised={dock.maximised}
          onToggleMaximise={dock.toggleMaximise}
        />

        {/* The dock (S-1). In this PR it holds today's panels in one scrolling
            body; Part 3b gives them tabs. Maximise hides it, never unmounts it. */}
        {(
          <SessionDock
            height={dock.height}
            maxHeight={dock.maxHeight}
            folded={dock.folded}
            hidden={dock.maximised}
            onResize={dock.resize}
            onToggleFold={dock.toggleFold}
            bar={<h2 className="px-2 text-sm font-semibold">Details</h2>}
          >
            <div className="space-y-6">
              <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
                <div data-testid="inspector-slot" className="min-w-0">
                  <Inspector
                    sessionId={sessionId}
                    coroutine={isLiveView ? selectedCoroutine : null}
                    readOnly={readOnly}
                    // Selection is live-only, so outside the live view the
                    // inspector must not invite a click that cannot work (#145).
                    placeholder={
                      replayActive
                        ? 'The inspector is paused during replay. Exit replay to inspect a coroutine.'
                        : readOnly
                          ? 'Coroutine details are not included in a shared link.'
                          : undefined
                    }
                    threadActivity={panelThreadActivity}
                  />
                </div>
                {/* Events, scoped to the current selection. */}
                <div className="min-w-0">
                  <EventsDrawer events={panelEvents} selectedCoroutine={selectedCoroutine} />
                </div>
              </div>

              {/* The metric strip and the leak list are live-only: in replay they
                  would report current numbers over a frozen view, and the shared
                  shell has no Bearer for /metrics (M-4). Leaks render once (PD-02). */}
              {isLiveView && (
                <div className="space-y-4">
                  <SessionMetrics sessionId={sessionId} enabled={!readOnly} showLeaks={false} />
                  {metrics && metrics.leaks.length > 0 && (
                    <LeakList leaks={metrics.leaks} leakThresholdMs={metrics.leakThresholdMs} />
                  )}
                </div>
              )}

              {/* How to read the tree (spec Part 1). */}
              {session.coroutineCount > 0 && <StructuredConcurrencyInfo />}

              {/* Scenario Pipeline View - shown for realistic scenarios */}
              {scenarioId === 'order-processing' && allEvents.length > 0 && (
                <OrderProcessingView events={allEvents} />
              )}
              {scenarioId === 'user-registration' && allEvents.length > 0 && (
                <RegistrationFlowView events={allEvents} />
              )}

              {/* The evidence section (M-1/M-2). */}
              <EvidencePanels
                sessionId={sessionId}
                rung={rung}
                categories={eventCategories}
                replayActive={replayActive}
                readOnly={readOnly}
                threadActivity={panelThreadActivity}
              />
            </div>
          </SessionDock>
        )}
      </div>
    </div>
  )
```

   (The `{( … )}` wrapper above is only to show the change; write `<SessionDock …>` directly, un-nested.) The loading spinner branch centres in the flex column (`<div className="flex flex-1 items-center justify-center py-20">`).

5. `git rm src/components/workspace/WorkspaceBody.tsx src/components/workspace/WorkspaceBody.test.tsx`. Its guarantees move with it: the metric strip is mounted once and only in the live view; the leak list once (covered by the existing `SessionWorkspace` tests `mounts SessionMetrics exactly once` and the reachability test).

6. `src/routes/sessions/$sessionId.tsx` — the page fills the window under the navbar and drops the 80 rem width limit (S-1):

```tsx
  return (
    <Layout>
      {/* Full width, full height under the navbar (64 px and its 1 px border,
          S-1): the main view and the dock share the window instead of
          scrolling a long page. */}
      <div className="flex h-[calc(100dvh-65px)] flex-col px-4 py-3 sm:px-6">
        {/* key: a new session is a new page — no selection, dock tab or stream
            state carries over (Reset navigates between sessions). */}
        <SessionWorkspace key={sessionId} … unchanged props … />
      </div>
    </Layout>
  )
```

7. `src/routes/shared.$token.tsx` — the valid-token branch:

```tsx
  return (
    <div className="flex h-dvh flex-col px-4 py-3 sm:px-6">
      <div className="mb-2 flex justify-end">
        <Chip color="default" variant="flat" startContent={<FiEye />}>
          {COPY.banner}
        </Chip>
      </div>
      <SessionWorkspace sessionId={result.data.session.sessionId} readOnly />
    </div>
  )
```

   (the other branches keep `container-custom`).

- [ ] **Step 4: Run the workspace tests and see them pass**

Run: `pnpm vitest run src/components/SessionWorkspace src/components/workspace src/routes`
Expected: PASS.

- [ ] **Step 5: Run the gates**

Run: `pnpm exec tsc --noEmit && pnpm lint && pnpm test && pnpm build`
Expected: all green; test count = 947 − deleted (`WorkspaceBody` 14, `ScenarioControls` ~8) + added.

- [ ] **Step 6: Commit** (Tasks 11–13 if they were not committed separately)

```bash
git add -A src
git commit -m "feat(frontend): the session page as header, main view and resizable dock"
```

---

### Task 14: Browser check (3a) and PR

- [ ] Start the backend on 18801 and the frontend on 13801 (commands in the 3b browser-check task); open a live demo, a finished demo, a failed demo, replay and the shared view at 1440 and 900 px, dark and light. Check: the graph fills its box; the dock starts at 40 %; dragging and the keyboard resize it; Enter and Fold fold it; Maximise hides it; a reload keeps the size and fold. Screenshots to the session scratchpad `part3-shots/3a-*`.
- [ ] Codex code review of `git diff origin/feat/session-quick-wins...HEAD` (up to 3 rounds).
- [ ] `git push -u origin feat/session-dock-shell`; open the PR to `main` titled "Session page as a dock (3a): header, main view, resizable dock", body saying it is stacked on #290 and should merge after it.

---
# PR 3b · Tabs

Branch from 3a once 3a's PR is open:

```bash
cd /Users/jirihermann/Documents/workspace-vizcore/vizcore-dock
git checkout -b feat/session-dock-tabs
```

### Task 15: Lifetimes through `formatDuration`

**Files:**
- Modify: `src/components/workspace/Inspector/TimingCard.tsx` (`Approx`, the two "so far" spans, the possibly-blocked line)
- Modify: `src/components/LeakList.tsx` (drop the local `formatMs`)
- Test: `src/components/workspace/Inspector/Inspector.test.tsx:132-134,172,200`, `src/components/LeakList.test.tsx:18,41,85`

- [ ] **Step 1: Update the tests to the new wording** (they now fail):

| File:line | Old | New |
|---|---|---|
| `Inspector.test.tsx:132` | `'~4.20s'` | `'~4.2 s'` |
| `Inspector.test.tsx:133` | `'~200.00ms'` | `'~200 ms'` |
| `Inspector.test.tsx:134` | `'~4.00s'` | `'~4.0 s'` |
| `Inspector.test.tsx:172` | `'~3.00s so far'` | `'~3.0 s so far'` |
| `Inspector.test.tsx:200` | `/^~60\.\d{2}s so far$/` | `/^~(59\.\d s\|1 m 0 s) so far$/` |
| `LeakList.test.tsx:18` | `/fetchUser · alive 42s/` | `/fetchUser · alive 42\.0 s/` |
| `LeakList.test.tsx:41` | `/pollOrders · alive 61s/` | `/pollOrders · alive 1 m 1 s/` |
| `LeakList.test.tsx:85` | `/dp-9 · alive 5s/` | `/dp-9 · alive 5\.0 s/` |

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm vitest run src/components/workspace/Inspector src/components/LeakList.test.tsx`
Expected: FAIL on the old formats.

- [ ] **Step 3: Implement**

In `TimingCard.tsx` import `formatDuration` from `@/lib/format-duration` and drop `formatNanoTime`:

```tsx
            <span title="Still running: time since it was created">~{formatDuration(live.total, { soFar: true })}</span>
```
```tsx
            <span title="Still running: time from its first to its latest recorded event">
              ~{formatDuration(soFar, { soFar: true })}
            </span>
```
```tsx
          Running for ~{formatDuration(live.openFor)} without suspending since it last resumed. It may be
```
```tsx
function Approx({ nanos, soFar = false }: { nanos: number | null | undefined; soFar?: boolean }) {
  if (nanos === null || nanos === undefined) return <NotReported />
  return <>~{formatDuration(nanos, { soFar })}</>
}
```

In `LeakList.tsx` import `formatDurationMs` and replace both `formatMs(...)` calls with `formatDurationMs(...)`; delete `formatMs`.

- [ ] **Step 4: Run them and see them pass**

Run: `pnpm vitest run src/components/workspace/Inspector src/components/LeakList.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/components/workspace/Inspector src/components/LeakList.tsx src/components/LeakList.test.tsx
git commit -m "feat(frontend): lifetimes read in words in the inspector and the long-lived list"
```

---

### Task 16: `DockTabs`

**Files:**
- Create: `src/components/workspace/DockTabs.tsx`
- Test: `src/components/workspace/DockTabs.test.tsx`

- [ ] **Step 1: Write the failing test** — `src/components/workspace/DockTabs.test.tsx`:

```tsx
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { DockTabPanel, DockTabs, type DockTabId } from './DockTabs'

const TABS = [
  { id: 'inspector', label: 'Inspector' },
  { id: 'evidence', label: 'Evidence · 1 lock' },
  { id: 'events', label: 'Events' },
  { id: 'learn', label: 'Learn' },
] as const

function Harness({ start = 'inspector' }: { start?: DockTabId }) {
  const [tab, setTab] = useState<DockTabId>(start)
  return (
    <>
      <DockTabs tabs={TABS} selected={tab} onSelect={setTab} />
      {TABS.map(t => (
        <DockTabPanel key={t.id} id={t.id} selected={tab === t.id}>
          {t.label} body
        </DockTabPanel>
      ))}
    </>
  )
}

describe('DockTabs — the WAI-ARIA tabs pattern (S-14)', () => {
  it('lists the tabs in a labelled tablist, wired to their panels', () => {
    render(<Harness />)
    expect(screen.getByRole('tablist', { name: 'Dock' })).toBeInTheDocument()
    const tab = screen.getByRole('tab', { name: 'Inspector' })
    expect(tab).toHaveAttribute('aria-selected', 'true')
    expect(tab).toHaveAttribute('aria-controls', 'dock-panel-inspector')
    expect(screen.getByRole('tabpanel', { name: 'Inspector' })).toHaveTextContent('Inspector body')
  })

  it('keeps one tab in the Tab order', () => {
    render(<Harness />)
    expect(screen.getByRole('tab', { name: 'Inspector' })).toHaveAttribute('tabindex', '0')
    expect(screen.getByRole('tab', { name: 'Learn' })).toHaveAttribute('tabindex', '-1')
  })

  it('moves with the arrow keys, wrapping, and jumps with Home and End', async () => {
    render(<Harness />)
    screen.getByRole('tab', { name: 'Inspector' }).focus()
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Evidence · 1 lock' })).toHaveFocus()
    expect(screen.getByRole('tab', { name: 'Evidence · 1 lock' })).toHaveAttribute('aria-selected', 'true')
    await userEvent.keyboard('{End}')
    expect(screen.getByRole('tab', { name: 'Learn' })).toHaveFocus()
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Inspector' })).toHaveFocus()
    await userEvent.keyboard('{ArrowLeft}')
    expect(screen.getByRole('tab', { name: 'Learn' })).toHaveFocus()
    await userEvent.keyboard('{Home}')
    expect(screen.getByRole('tab', { name: 'Inspector' })).toHaveFocus()
  })

  it('selects on click and hides the other panels', async () => {
    render(<Harness />)
    await userEvent.click(screen.getByRole('tab', { name: 'Learn' }))
    expect(screen.getByRole('tabpanel', { name: 'Learn' })).toBeVisible()
    expect(screen.getByText('Inspector body')).not.toBeVisible()
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/components/workspace/DockTabs.test.tsx`
Expected: FAIL — cannot resolve `./DockTabs`.

- [ ] **Step 3: Implement** — `src/components/workspace/DockTabs.tsx`:

```tsx
import { useRef, type KeyboardEvent, type ReactNode } from 'react'

/**
 * The dock's tabs, one per question (spec S-4): what is this coroutine, what
 * did the resources do, what exactly happened, how do I read this. Problems
 * ("what is wrong?") is the fifth and arrives with the health API (Part 7).
 */
export type DockTabId = 'inspector' | 'evidence' | 'events' | 'learn'

export interface DockTabSpec {
  id: DockTabId
  label: string
}

const tabId = (id: DockTabId) => `dock-tab-${id}`
const panelId = (id: DockTabId) => `dock-panel-${id}`

/**
 * The WAI-ARIA tabs pattern (S-14) with automatic activation: one tab is in
 * the Tab order, the arrow keys move between tabs (wrapping), Home and End go
 * to the first and last. The row scrolls sideways rather than wrapping, so a
 * narrow window keeps one line of tabs.
 */
export function DockTabs({
  tabs,
  selected,
  onSelect,
}: {
  tabs: readonly DockTabSpec[]
  selected: DockTabId
  onSelect: (id: DockTabId) => void
}) {
  const refs = useRef(new Map<DockTabId, HTMLButtonElement>())

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const n = tabs.length
    const i = tabs.findIndex(t => t.id === selected)
    const next = { ArrowRight: (i + 1) % n, ArrowLeft: (i - 1 + n) % n, Home: 0, End: n - 1 }[e.key]
    if (next === undefined) return
    e.preventDefault()
    const to = tabs[next]!.id
    onSelect(to)
    refs.current.get(to)?.focus()
  }

  return (
    <div
      role="tablist"
      aria-label="Dock"
      onKeyDown={onKeyDown}
      className="flex min-w-0 flex-nowrap gap-1 overflow-x-auto py-1"
    >
      {tabs.map(tab => {
        const active = tab.id === selected
        return (
          <button
            key={tab.id}
            ref={el => {
              if (el) refs.current.set(tab.id, el)
              else refs.current.delete(tab.id)
            }}
            type="button"
            role="tab"
            id={tabId(tab.id)}
            aria-selected={active}
            aria-controls={panelId(tab.id)}
            tabIndex={active ? 0 : -1}
            onClick={() => onSelect(tab.id)}
            className={
              active
                ? 'shrink-0 whitespace-nowrap rounded-medium bg-default-200 px-3 py-1.5 text-sm font-semibold text-foreground outline-none focus-visible:ring-2 focus-visible:ring-primary'
                : 'shrink-0 whitespace-nowrap rounded-medium px-3 py-1.5 text-sm text-default-500 outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-primary'
            }
          >
            {tab.label}
          </button>
        )
      })}
    </div>
  )
}

/** A tab's panel. Always in the DOM so `aria-controls` resolves; hidden when not selected. */
export function DockTabPanel({ id, selected, children }: { id: DockTabId; selected: boolean; children: ReactNode }) {
  return (
    <div
      role="tabpanel"
      id={panelId(id)}
      aria-labelledby={tabId(id)}
      hidden={!selected}
      tabIndex={0}
      className="outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      {children}
    </div>
  )
}
```

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/components/workspace/DockTabs.test.tsx`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/components/workspace/DockTabs.tsx src/components/workspace/DockTabs.test.tsx
git commit -m "feat(frontend): dock tabs following the WAI-ARIA tabs pattern"
```

---

### Task 17: What was captured — `evidence-summary`

**Files:**
- Create: `src/lib/evidence-summary.ts`
- Test: `src/lib/evidence-summary.test.ts`

- [ ] **Step 1: Write the failing test** — `src/lib/evidence-summary.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { countEvidence, evidenceTabLabel, plural, resourceSummary } from './evidence-summary'
import type { VizEvent } from '@/types/api'

const ev = (kind: string, extra: Record<string, unknown> = {}) => ({ seq: 1, tsNanos: 1, sessionId: 's', kind, ...extra }) as unknown as VizEvent

describe('countEvidence', () => {
  it('counts each lock, semaphore, channel and flow once, however many events it has', () => {
    const counts = countEvidence([
      ev('MutexCreated', { mutexId: 'm-1' }),
      ev('MutexLockRequested', { mutexId: 'm-1' }),
      ev('MutexLockAcquired', { mutexId: 'm-2' }),
      ev('SemaphoreCreated', { semaphoreId: 'p-1' }),
      ev('ChannelSendStarted', { channelId: 'ch-1' }),
      ev('FlowValueEmitted', { flowId: 'f-1' }),
      ev('FlowCreated', { flowId: 'f-2' }),
      ev('JobStateChanged'),
      ev('CoroutineCreated', { coroutineId: 'c-1' }),
    ])
    expect(counts).toEqual({ locks: 2, semaphores: 1, channels: 1, flows: 2, jobEvents: 1 })
  })
})

describe('labels', () => {
  it('names what the Evidence tab holds (S-4)', () => {
    expect(evidenceTabLabel({ locks: 1, semaphores: 1, channels: 0, flows: 0, jobEvents: 3 })).toBe(
      'Evidence · 1 lock, 1 semaphore',
    )
    expect(evidenceTabLabel({ locks: 0, semaphores: 0, channels: 2, flows: 3, jobEvents: 0 })).toBe(
      'Evidence · 2 channels, 3 flows',
    )
  })

  it('is just "Evidence" when no resource was captured', () => {
    expect(evidenceTabLabel({ locks: 0, semaphores: 0, channels: 0, flows: 0, jobEvents: 4 })).toBe('Evidence')
  })

  it('summarises a section from its own counts', () => {
    expect(resourceSummary([plural(2, 'lock'), plural(0, 'semaphore')])).toBe('2 locks')
    expect(resourceSummary([plural(0, 'lock')])).toBeNull()
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/lib/evidence-summary.test.ts`
Expected: FAIL — cannot resolve `./evidence-summary`.

- [ ] **Step 3: Implement** — `src/lib/evidence-summary.ts`:

```ts
import { CHANNEL_EVENT_KINDS, FLOW_EVENT_KINDS, JOB_EVENT_KINDS, SYNC_EVENT_KINDS, type VizEvent } from '@/types/api'

/** What the session's events show beyond the tree: distinct resources, and job events. */
export interface EvidenceCounts {
  locks: number
  semaphores: number
  channels: number
  flows: number
  jobEvents: number
}

/** Fields the resource events carry; each kind has at most one of them. */
type ResourceIds = { mutexId?: string; semaphoreId?: string; channelId?: string; flowId?: string }

export function countEvidence(events: readonly VizEvent[]): EvidenceCounts {
  const locks = new Set<string>()
  const semaphores = new Set<string>()
  const channels = new Set<string>()
  const flows = new Set<string>()
  let jobEvents = 0
  for (const event of events) {
    const ids = event as unknown as ResourceIds
    if (SYNC_EVENT_KINDS.has(event.kind)) {
      if (ids.mutexId) locks.add(ids.mutexId)
      if (ids.semaphoreId) semaphores.add(ids.semaphoreId)
    } else if (CHANNEL_EVENT_KINDS.has(event.kind)) {
      if (ids.channelId) channels.add(ids.channelId)
    } else if (FLOW_EVENT_KINDS.has(event.kind)) {
      if (ids.flowId) flows.add(ids.flowId)
    } else if (JOB_EVENT_KINDS.has(event.kind)) {
      jobEvents++
    }
  }
  return { locks: locks.size, semaphores: semaphores.size, channels: channels.size, flows: flows.size, jobEvents }
}

/** "1 lock", "2 locks"; empty for zero, so callers can drop it. */
export function plural(n: number, one: string, many = `${one}s`): string {
  return n === 0 ? '' : `${n} ${n === 1 ? one : many}`
}

/** The non-empty parts, comma-separated; null when there are none. */
export function resourceSummary(parts: readonly string[]): string | null {
  const shown = parts.filter(p => p !== '')
  return shown.length > 0 ? shown.join(', ') : null
}

/** The Evidence tab's label names what it holds (S-4): "Evidence · 1 lock, 1 semaphore". */
export function evidenceTabLabel(c: EvidenceCounts): string {
  const held = resourceSummary([
    plural(c.locks, 'lock'),
    plural(c.semaphores, 'semaphore'),
    plural(c.channels, 'channel'),
    plural(c.flows, 'flow'),
  ])
  return held ? `Evidence · ${held}` : 'Evidence'
}
```

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/lib/evidence-summary.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/lib/evidence-summary.ts src/lib/evidence-summary.test.ts
git commit -m "feat(frontend): count what a session captured for the Evidence tab"
```

---

### Task 18: The grouped thread table (T-5)

**Files:**
- Create: `src/lib/thread-groups.ts`, `src/lib/thread-groups.test.ts`
- Create: `src/components/ThreadTable.tsx`, `src/components/ThreadTable.test.tsx`
- Delete: `src/components/ThreadTimeline.tsx` (its only user is `EvidencePanels`, rewritten in Task 19)

- [ ] **Step 1: Write the failing tests**

`src/lib/thread-groups.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { earliestThreadEvent, groupThreadsByName, threadSummary } from './thread-groups'
import type { ThreadActivity, ThreadEvent } from '@/types/api'

const e = (threadId: number, threadName: string, coroutineId: string, timestamp: number, eventType: ThreadEvent['eventType'] = 'ASSIGNED', dispatcherName: string | null = 'Default'): ThreadEvent =>
  ({ threadId, threadName, coroutineId, timestamp, eventType, dispatcherName })

const activity: ThreadActivity = {
  '57': [e(57, 'worker-1', 'c-1', 1_000), e(57, 'worker-1', 'c-1', 2_000, 'RELEASED'), e(57, 'worker-1', 'c-2', 3_000)],
  // A pool thread that died and came back under the same name: a second JVM thread.
  '91': [e(91, 'worker-1', 'c-3', 9_000)],
  '80': [e(80, 'worker-2', 'c-2', 4_000, 'ASSIGNED', 'IO')],
}

describe('groupThreadsByName', () => {
  it('groups by thread name with JVM threads, coroutines seen, assignments and the last assignment', () => {
    expect(groupThreadsByName(activity)).toEqual([
      { name: 'worker-1', jvmThreads: 2, coroutinesSeen: 3, assignments: 3, lastAssignedNanos: 9_000 },
      { name: 'worker-2', jvmThreads: 1, coroutinesSeen: 1, assignments: 1, lastAssignedNanos: 4_000 },
    ])
  })

  it('names a thread with no name by its id', () => {
    expect(groupThreadsByName({ '7': [e(7, '', 'c-1', 1)] })[0]!.name).toBe('Thread 7')
  })

  it('is empty without activity', () => {
    expect(groupThreadsByName(undefined)).toEqual([])
  })
})

describe('threadSummary', () => {
  it('reads names and JVM threads', () => {
    expect(threadSummary(groupThreadsByName(activity))).toBe('2 names (3 JVM threads)')
  })

  it('says none captured when empty', () => {
    expect(threadSummary([])).toBe('none captured')
  })
})

describe('earliestThreadEvent', () => {
  it('is the first thread event, or null', () => {
    expect(earliestThreadEvent(activity)).toBe(1_000)
    expect(earliestThreadEvent({})).toBeNull()
  })
})
```

`src/components/ThreadTable.test.tsx`:

```tsx
import { describe, it, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThreadTable } from './ThreadTable'
import type { ThreadActivity } from '@/types/api'

const S = 1_000_000_000

function activityWith(names: number): ThreadActivity {
  const out: ThreadActivity = {}
  for (let i = 0; i < names; i++) {
    out[String(i)] = [
      { threadId: i, threadName: `worker-${i}`, coroutineId: `c-${i}`, timestamp: (i + 1) * S, eventType: 'ASSIGNED', dispatcherName: 'Default' },
    ]
  }
  return out
}

describe('ThreadTable', () => {
  it('shows one row per thread name with T-5\'s columns', () => {
    render(<ThreadTable threadActivity={activityWith(2)} captureStartNanos={0} />)
    const table = screen.getByRole('table', { name: 'Threads by name' })
    expect(within(table).getAllByRole('columnheader').map(h => h.textContent)).toEqual([
      'Thread', 'JVM threads', 'Coroutines seen', 'Assignments observed', 'Last assignment',
    ])
    expect(within(table).getByRole('row', { name: /worker-1/ })).toHaveTextContent('2.0 s in')
  })

  it('shows ten names, then the rest behind "Show all"', async () => {
    render(<ThreadTable threadActivity={activityWith(12)} captureStartNanos={0} />)
    expect(screen.getAllByRole('row')).toHaveLength(11) // header + 10
    await userEvent.click(screen.getByRole('button', { name: 'Show all (12)' }))
    expect(screen.getAllByRole('row')).toHaveLength(13)
  })

  it('measures from the first thread event when the capture start is unknown', () => {
    render(<ThreadTable threadActivity={activityWith(2)} captureStartNanos={null} />)
    expect(screen.getByRole('row', { name: /worker-1/ })).toHaveTextContent('1.0 s in')
  })

  it('says so when no thread activity was captured', () => {
    render(<ThreadTable threadActivity={{}} captureStartNanos={null} />)
    expect(screen.getByText('No thread activity captured yet.')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm vitest run src/lib/thread-groups.test.ts src/components/ThreadTable.test.tsx`
Expected: FAIL — modules not found.

- [ ] **Step 3: Implement**

`src/lib/thread-groups.ts`:

```ts
import type { ThreadActivity } from '@/types/api'
import { plural } from './evidence-summary'

/** One thread name's activity (triage T-5). */
export interface ThreadGroup {
  name: string
  /** Distinct JVM threads seen under this name (pool threads come and go). */
  jvmThreads: number
  coroutinesSeen: number
  assignments: number
  /** The last ASSIGNED event's timestamp, on the app's clock. */
  lastAssignedNanos: number | null
}

/**
 * Thread activity grouped by thread name, busiest first. One row per name, not
 * one per event: a real app's thread list was thousands of rows (spec Problem 4).
 */
export function groupThreadsByName(activity: ThreadActivity | undefined): ThreadGroup[] {
  const groups = new Map<string, { ids: Set<string>; coroutines: Set<string>; assignments: number; last: number | null }>()
  for (const [threadId, events] of Object.entries(activity ?? {})) {
    for (const event of events) {
      const name = event.threadName || `Thread ${threadId}`
      let g = groups.get(name)
      if (!g) {
        g = { ids: new Set(), coroutines: new Set(), assignments: 0, last: null }
        groups.set(name, g)
      }
      g.ids.add(threadId)
      g.coroutines.add(event.coroutineId)
      if (event.eventType === 'ASSIGNED') {
        g.assignments++
        g.last = g.last === null ? event.timestamp : Math.max(g.last, event.timestamp)
      }
    }
  }
  return [...groups.entries()]
    .map(([name, g]) => ({
      name,
      jvmThreads: g.ids.size,
      coroutinesSeen: g.coroutines.size,
      assignments: g.assignments,
      lastAssignedNanos: g.last,
    }))
    .sort((a, b) => b.assignments - a.assignments || a.name.localeCompare(b.name))
}

/** "18 names (89 JVM threads)", or "none captured". */
export function threadSummary(groups: readonly ThreadGroup[]): string {
  if (groups.length === 0) return 'none captured'
  const jvm = groups.reduce((n, g) => n + g.jvmThreads, 0)
  return `${plural(groups.length, 'name')} (${plural(jvm, 'JVM thread')})`
}

/** The first thread event's timestamp, or null when there is none. */
export function earliestThreadEvent(activity: ThreadActivity | undefined): number | null {
  let first: number | null = null
  for (const events of Object.values(activity ?? {})) {
    for (const event of events) {
      if (first === null || event.timestamp < first) first = event.timestamp
    }
  }
  return first
}
```

(`jvmThreads` sums per name; a JVM thread renamed mid-run would count under both names — acceptable, and the test pins the ordinary case.)

`src/components/ThreadTable.tsx`:

```tsx
import { useMemo, useState } from 'react'
import { Button } from '@heroui/react'
import { formatDuration } from '@/lib/format-duration'
import { earliestThreadEvent, groupThreadsByName } from '@/lib/thread-groups'
import type { ThreadActivity } from '@/types/api'

/** Rows shown before "Show all". */
const ROW_CAP = 10

/**
 * Threads as a table grouped by thread name (triage T-5): how many JVM threads
 * carried the name, how many coroutines were seen on them, how many
 * assignments were observed, and when the last one happened, as time into the
 * capture. It replaces a list with one row per event.
 */
export function ThreadTable({
  threadActivity,
  captureStartNanos,
}: {
  threadActivity: ThreadActivity
  /** The first retained event's timestamp; null falls back to the first thread event. */
  captureStartNanos: number | null
}) {
  const [showAll, setShowAll] = useState(false)
  const groups = useMemo(() => groupThreadsByName(threadActivity), [threadActivity])
  const start = captureStartNanos ?? earliestThreadEvent(threadActivity)

  if (groups.length === 0) {
    return <p className="text-sm text-default-400">No thread activity captured yet.</p>
  }

  const shown = showAll ? groups : groups.slice(0, ROW_CAP)
  return (
    <div className="space-y-2">
      <div className="overflow-x-auto">
        <table aria-label="Threads by name" className="w-full text-left text-sm">
          <thead className="text-xs text-default-500">
            <tr>
              <th scope="col" className="py-1 pr-4 font-semibold">Thread</th>
              <th scope="col" className="py-1 pr-4 text-right font-semibold">JVM threads</th>
              <th scope="col" className="py-1 pr-4 text-right font-semibold">Coroutines seen</th>
              <th scope="col" className="py-1 pr-4 text-right font-semibold">Assignments observed</th>
              <th scope="col" className="py-1 text-right font-semibold">Last assignment</th>
            </tr>
          </thead>
          <tbody>
            {shown.map(g => (
              <tr key={g.name} className="border-t border-default-200">
                <th scope="row" className="py-1 pr-4 font-mono text-xs font-normal">{g.name}</th>
                <td className="py-1 pr-4 text-right tabular-nums">{g.jvmThreads}</td>
                <td className="py-1 pr-4 text-right tabular-nums">{g.coroutinesSeen}</td>
                <td className="py-1 pr-4 text-right tabular-nums">{g.assignments}</td>
                <td className="py-1 text-right tabular-nums">
                  {g.lastAssignedNanos !== null && start !== null
                    ? `${formatDuration(g.lastAssignedNanos - start)} in`
                    : 'none observed'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-default-400">Last assignment: time since the first retained event.</p>
      {groups.length > ROW_CAP && (
        <Button size="sm" variant="light" aria-expanded={showAll} onPress={() => setShowAll(v => !v)}>
          {showAll ? 'Show fewer' : `Show all (${groups.length})`}
        </Button>
      )}
    </div>
  )
}
```

- [ ] **Step 4: Run them and see them pass**

Run: `pnpm vitest run src/lib/thread-groups.test.ts src/components/ThreadTable.test.tsx`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add src/lib/thread-groups.ts src/lib/thread-groups.test.ts src/components/ThreadTable.tsx src/components/ThreadTable.test.tsx
git commit -m "feat(frontend): threads as a table grouped by thread name"
```

---

### Task 19: Evidence sections, always listed (S-4, T-5, D-5)

**Files:**
- Rewrite: `src/components/workspace/EvidencePanels.tsx`
- Rewrite: `src/components/workspace/EvidencePanels.test.tsx`
- Delete: `src/components/LockedPanel.tsx`, `src/components/LockedPanel.test.tsx`, `src/components/ThreadTimeline.tsx` (its copy moves into the sections' "none captured" rows; no other user)

Threads gets no replay "live data" notice: its table is projected from the replay cursor (`panelThreadActivity`), unlike the other panels, which read the live session (D-17).

- [ ] **Step 1: Write the failing test** — replace `src/components/workspace/EvidencePanels.test.tsx` (keep its panel mocks; mock `../ThreadTable` instead of `../ThreadTimeline`):

```tsx
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { EventCategories } from '@/hooks/use-event-categories'
import type { EvidenceCounts } from '@/lib/evidence-summary'
import type { ThreadActivity } from '@/types/api'
import { EvidencePanels } from './EvidencePanels'

vi.mock('../channels/ChannelPanel', () => ({ ChannelPanel: () => <div data-testid="channel-panel" /> }))
vi.mock('../flow/FlowPanel', () => ({ FlowPanel: () => <div data-testid="flow-panel" /> }))
vi.mock('../sync/SyncPanel', () => ({ SyncPanel: () => <div data-testid="sync-panel" /> }))
vi.mock('../jobs/JobPanel', () => ({ JobPanel: () => <div data-testid="job-panel" /> }))
vi.mock('../ThreadTable', () => ({ ThreadTable: () => <div data-testid="thread-table" /> }))
vi.mock('../DispatcherOverview', () => ({ DispatcherOverview: () => <div data-testid="dispatcher-overview" /> }))

const none: EvidenceCounts = { locks: 0, semaphores: 0, channels: 0, flows: 0, jobEvents: 0 }
const activity: ThreadActivity = {
  '1': [{ threadId: 1, threadName: 'worker-1', coroutineId: 'c', timestamp: 1, eventType: 'ASSIGNED', dispatcherName: 'Default' }],
}

function categories(overrides: Partial<EventCategories> = {}): EventCategories {
  return { hasChannels: false, hasFlowOps: false, hasSyncPrimitives: false, hasJobs: false, hasValidation: true, ...overrides }
}

function renderPanels(props: Partial<Parameters<typeof EvidencePanels>[0]> = {}) {
  return render(
    <EvidencePanels
      sessionId="s-1"
      rung="attached"
      categories={categories()}
      counts={none}
      replayActive={false}
      readOnly={false}
      threadActivity={activity}
      captureStartNanos={0}
      {...props}
    />,
  )
}

const SECTIONS = ['Threads', 'Dispatchers', 'Locks and permits', 'Channels', 'Flows', 'Jobs']

describe('EvidencePanels — six sections, always listed (S-4)', () => {
  it('lists every section in order, whatever was captured', () => {
    renderPanels()
    const titles = screen.getAllByTestId(/^evidence-section-/).map(s => s.getAttribute('data-testid'))
    expect(titles).toEqual(SECTIONS.map(t => `evidence-section-${t}`))
  })

  it('folds a captured section behind a disclosure with a one-line summary', async () => {
    renderPanels({ categories: categories({ hasSyncPrimitives: true }), counts: { ...none, locks: 1, semaphores: 1 } })
    const sync = screen.getByRole('button', { name: /Locks and permits/ })
    expect(sync).toHaveAttribute('aria-expanded', 'false')
    expect(sync).toHaveTextContent('1 lock, 1 semaphore')
    // Mounted from the start (its query subscribes at page load), only hidden.
    expect(screen.getByTestId('sync-panel')).not.toBeVisible()
    await userEvent.click(sync)
    expect(sync).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('sync-panel')).toBeVisible()
  })

  it('says "none captured" and what unlocks it, on a real session (D-5)', () => {
    renderPanels()
    const locks = screen.getByTestId('evidence-section-Locks and permits')
    expect(locks).toHaveTextContent('none captured')
    expect(locks).toHaveTextContent('Swap Mutex() for VizMutex()')
    expect(within(locks).queryByRole('button')).toBeNull()
    expect(screen.getByTestId('evidence-section-Flows')).toHaveTextContent('.instrumented()')
    expect(screen.getByTestId('evidence-section-Channels')).toHaveTextContent('InstrumentedChannel(...)')
  })

  it('names no code change on a demo, which has full fidelity already', () => {
    renderPanels({ rung: 'demo' })
    const locks = screen.getByTestId('evidence-section-Locks and permits')
    expect(locks).toHaveTextContent('none captured')
    expect(locks).not.toHaveTextContent('VizMutex')
  })

  it('offers no unlock for Jobs, which are not wrapper-only', () => {
    renderPanels()
    expect(screen.getByTestId('evidence-section-Jobs')).toHaveTextContent('none captured')
    expect(screen.getByTestId('evidence-section-Jobs')).not.toHaveTextContent('Swap')
  })

  it('summarises threads by name and opens the grouped table', async () => {
    renderPanels()
    const threads = screen.getByRole('button', { name: /Threads/ })
    expect(threads).toHaveTextContent('1 name (1 JVM thread)')
    await userEvent.click(threads)
    expect(screen.getByTestId('thread-table')).toBeVisible()
  })

  it('summarises the dispatchers named in thread activity', async () => {
    renderPanels()
    const dispatchers = screen.getByRole('button', { name: /Dispatchers/ })
    expect(dispatchers).toHaveTextContent('1 dispatcher')
    await userEvent.click(dispatchers)
    expect(screen.getByTestId('dispatcher-overview')).toBeVisible()
  })

  it('warns that an opened panel is not following the replay cursor (D-17)', async () => {
    renderPanels({ replayActive: true })
    await userEvent.click(screen.getByRole('button', { name: /Dispatchers/ }))
    expect(within(screen.getByTestId('evidence-section-Dispatchers')).getByTestId('live-data-notice')).toBeVisible()
  })

  it('gives Threads no live-data warning in replay: its table follows the cursor', async () => {
    renderPanels({ replayActive: true })
    await userEvent.click(screen.getByRole('button', { name: /Threads/ }))
    expect(within(screen.getByTestId('evidence-section-Threads')).queryByTestId('live-data-notice')).toBeNull()
  })

  it('shows no live-data warning outside replay', async () => {
    renderPanels()
    await userEvent.click(screen.getByRole('button', { name: /Dispatchers/ }))
    expect(screen.queryByTestId('live-data-notice')).toBeNull()
  })

  it('opens and closes with Space and Enter, like any disclosure button', async () => {
    renderPanels()
    const threads = screen.getByRole('button', { name: /Threads/ })
    threads.focus()
    await userEvent.keyboard(' ')
    expect(threads).toHaveAttribute('aria-expanded', 'true')
    await userEvent.keyboard('{Enter}')
    expect(threads).toHaveAttribute('aria-expanded', 'false')
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/components/workspace/EvidencePanels.test.tsx`
Expected: FAIL — no `evidence-section-*`.

- [ ] **Step 3: Implement** — rewrite `src/components/workspace/EvidencePanels.tsx`:

```tsx
import { useMemo, useState, type ReactNode } from 'react'
import { Spinner } from '@heroui/react'
import { FiArrowUpRight, FiChevronDown, FiChevronRight } from 'react-icons/fi'
import { ChannelPanel } from '../channels/ChannelPanel'
import { FlowPanel } from '../flow/FlowPanel'
import { SyncPanel } from '../sync/SyncPanel'
import { JobPanel } from '../jobs/JobPanel'
import { ThreadTable } from '../ThreadTable'
import { DispatcherOverview } from '../DispatcherOverview'
import { LiveDataNotice } from '../replay/LiveDataNotice'
import { plural, resourceSummary, type EvidenceCounts } from '@/lib/evidence-summary'
import { groupThreadsByName, threadSummary } from '@/lib/thread-groups'
import type { EventCategories } from '@/hooks/use-event-categories'
import type { Rung } from '@/lib/fidelity-rung'
import type { ThreadActivity } from '@/types/api'

interface EvidencePanelsProps {
  sessionId: string
  rung: Rung
  categories: EventCategories
  /** Distinct resources and job events in the session's events. */
  counts: EvidenceCounts
  replayActive: boolean
  readOnly: boolean
  /** Lanes for the threads table — server snapshot, or projected in replay. */
  threadActivity: ThreadActivity | undefined
  /** The first retained event's timestamp, for "time into the capture". */
  captureStartNanos: number | null
}

/**
 * A wrapper-only capability: captured, or absent-and-explainable.
 *
 * The unlock copy is the product's growth path written down, so it names the
 * exact change (`VizMutex`, `.instrumented()`, `InstrumentedChannel`) rather
 * than "enable instrumentation" — a developer cannot act on the latter.
 */
interface Capability {
  title: string
  present: boolean
  summary: string
  whatYouWouldSee: string
  /** Empty when no single change unlocks it. */
  unlockWith: string
  panel: ReactNode
}

/**
 * The Evidence tab (spec S-4, triage T-5): what the resources did. Six
 * sections, always listed in the same order, each folded to a one-line
 * summary. A section with nothing captured is not hidden — it says "none
 * captured" and, on a real session, the one change that would capture it
 * (D-5). Nothing is locked on a demo: it has full fidelity already, and
 * telling its user to swap in `VizMutex` names code that is not theirs.
 *
 * Folded sections are hidden, not unmounted: every panel subscribes at page
 * load, exactly as before, so opening one later never refetches.
 */
export function EvidencePanels({
  sessionId,
  rung,
  categories,
  counts,
  replayActive,
  readOnly,
  threadActivity,
  captureStartNanos,
}: EvidencePanelsProps) {
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set())
  const toggle = (title: string) =>
    setOpen(prev => {
      const next = new Set(prev)
      if (next.has(title)) next.delete(title)
      else next.add(title)
      return next
    })

  const groups = useMemo(() => groupThreadsByName(threadActivity), [threadActivity])
  const dispatchers = useMemo(() => {
    const names = new Set<string>()
    for (const events of Object.values(threadActivity ?? {})) {
      for (const e of events) if (e.dispatcherName) names.add(e.dispatcherName)
    }
    return names.size
  }, [threadActivity])

  const capabilities: Capability[] = [
    {
      title: 'Locks and permits',
      present: categories.hasSyncPrimitives,
      summary: resourceSummary([plural(counts.locks, 'lock'), plural(counts.semaphores, 'semaphore')]) ?? 'captured',
      whatYouWouldSee: 'Who holds each mutex and semaphore, who is queued behind it, and for how long.',
      unlockWith: 'Swap Mutex() for VizMutex() in the code you want to watch.',
      panel: <SyncPanel sessionId={sessionId} />,
    },
    {
      title: 'Channels',
      present: categories.hasChannels,
      summary: resourceSummary([plural(counts.channels, 'channel')]) ?? 'captured',
      whatYouWouldSee: 'Send and receive pairs, buffer occupancy, and which side is waiting.',
      unlockWith: 'Create the channel with InstrumentedChannel(...).',
      panel: <ChannelPanel sessionId={sessionId} />,
    },
    {
      title: 'Flows',
      present: categories.hasFlowOps,
      summary: resourceSummary([plural(counts.flows, 'flow')]) ?? 'captured',
      whatYouWouldSee: 'Emissions, operator stages, and where a slow collector is stalling the producer.',
      unlockWith: 'Wrap the flow you care about with .instrumented().',
      panel: <FlowPanel sessionId={sessionId} />,
    },
    {
      title: 'Jobs',
      present: categories.hasJobs,
      summary: resourceSummary([plural(counts.jobEvents, 'job event')]) ?? 'captured',
      whatYouWouldSee: 'Job lifecycle, cancellation propagation, and who is joining on whom.',
      // Job events are not confirmed wrapper-only (the same reason `hasJobs`
      // does not promote a session to instrumented), so there is no honest
      // one-line unlock to offer for them.
      unlockWith: '',
      panel: <JobPanel sessionId={sessionId} />,
    },
  ]

  return (
    <div className="space-y-2">
      {/* Threads is not rung-gated: the attach path reports thread and
          dispatcher assignment, so it is populated at every rung. */}
      <Section
        title="Threads"
        summary={threadActivity ? threadSummary(groups) : 'loading'}
        open={open.has('Threads')}
        onToggle={() => toggle('Threads')}
        // The table is projected from the replay cursor, so it needs no
        // "showing current data" warning (D-17).
        replayActive={false}
      >
        {threadActivity ? (
          <ThreadTable threadActivity={threadActivity} captureStartNanos={captureStartNanos} />
        ) : (
          <div className="py-4 text-center text-default-400">
            <Spinner size="sm" className="mb-2" />
            <p>Loading thread activity...</p>
          </div>
        )}
      </Section>
      <Section
        title="Dispatchers"
        summary={dispatchers > 0 ? plural(dispatchers, 'dispatcher') : 'none named in the thread activity'}
        open={open.has('Dispatchers')}
        onToggle={() => toggle('Dispatchers')}
        replayActive={replayActive}
      >
        <DispatcherOverview sessionId={sessionId} enabled={!readOnly} />
      </Section>
      {capabilities.map(c =>
        c.present ? (
          <Section
            key={c.title}
            title={c.title}
            summary={c.summary}
            open={open.has(c.title)}
            onToggle={() => toggle(c.title)}
            replayActive={replayActive}
          >
            {c.panel}
          </Section>
        ) : (
          <div
            key={c.title}
            data-testid={`evidence-section-${c.title}`}
            className="rounded-medium border border-dashed border-default-200 px-3 py-2 text-sm"
          >
            <span className="font-semibold">{c.title}</span>
            <span className="text-default-500"> · none captured</span>
            {/* Only a real session can be told what to change. */}
            {rung !== 'demo' && c.unlockWith !== '' && (
              <>
                <p className="mt-1 text-default-500">{c.whatYouWouldSee}</p>
                <p className="mt-1 flex items-start gap-2 text-primary">
                  <FiArrowUpRight className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
                  <span>{c.unlockWith}</span>
                </p>
              </>
            )}
          </div>
        ),
      )}
    </div>
  )
}

/** A section folded to its title and summary; a disclosure button with `aria-expanded` (triage accessibility). */
function Section({
  title,
  summary,
  open,
  onToggle,
  replayActive,
  children,
}: {
  title: string
  summary: string
  open: boolean
  onToggle: () => void
  /** These panels query the live session, not the replay cursor (D-17). */
  replayActive: boolean
  children: ReactNode
}) {
  const bodyId = `evidence-${title.toLowerCase().replace(/\W+/g, '-')}`
  return (
    <div data-testid={`evidence-section-${title}`} className="rounded-medium border border-default-200">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={bodyId}
        onClick={onToggle}
        className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm"
      >
        {open ? <FiChevronDown className="h-4 w-4" aria-hidden /> : <FiChevronRight className="h-4 w-4" aria-hidden />}
        <span className="font-semibold">{title}</span>{' '}
        <span className="text-default-500">· {summary}</span>
      </button>
      {/* Hidden, not unmounted: the panels subscribe to the session's events
          at page load, as before, and never re-subscribe to a stale query (a
          protected refetch in the shared view, T-03-22). */}
      <div id={bodyId} hidden={!open} className="space-y-2 px-3 pb-3">
        {replayActive && <LiveDataNotice />}
        {children}
      </div>
    </div>
  )
}
```

`git rm src/components/LockedPanel.tsx src/components/LockedPanel.test.tsx src/components/ThreadTimeline.tsx`.

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/components/workspace/EvidencePanels.test.tsx`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit** (with Task 24, which passes the new props; or now if the workspace is updated in the same commit)

---

### Task 20: Learn

**Files:**
- Create: `src/components/workspace/LearnPanel.tsx`, `src/components/workspace/LearnPanel.test.tsx`
- Delete: `src/components/StructuredConcurrencyInfo.tsx`, `src/components/StructuredConcurrencyInfo.test.tsx`

- [ ] **Step 1: Write the failing test** — `src/components/workspace/LearnPanel.test.tsx`:

```tsx
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LearnPanel } from './LearnPanel'

describe('LearnPanel (S-9, T-6)', () => {
  it('is marked as explanation, not recorded data (S-6)', () => {
    render(<LearnPanel />)
    expect(screen.getByText('Explanation, not recorded data')).toBeInTheDocument()
  })

  it('holds the legend with every state in words', () => {
    render(<LearnPanel />)
    for (const word of ['Active', 'Suspended', 'Waiting for children', 'Completed', 'Failed', 'Cancelled']) {
      expect(screen.getByTestId('learn-legend')).toHaveTextContent(word)
    }
  })

  it('qualifies how a failure spreads: an ordinary job versus a SupervisorJob (T-6)', () => {
    render(<LearnPanel />)
    expect(screen.getByText(/In an ordinary Job/)).toHaveTextContent('cancels its other children')
    expect(screen.getByText(/In an ordinary Job/)).toHaveTextContent('SupervisorJob')
  })

  it('names the sampling (H-4)', () => {
    render(<LearnPanel />)
    expect(screen.getByTestId('learn-sampling')).toHaveTextContent('captured coroutines')
  })

  it('shows a demo\'s own pipeline view when given one', () => {
    render(<LearnPanel><div data-testid="pipeline" /></LearnPanel>)
    expect(screen.getByTestId('pipeline')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/components/workspace/LearnPanel.test.tsx`
Expected: FAIL — cannot resolve `./LearnPanel`.

- [ ] **Step 3: Implement** — `src/components/workspace/LearnPanel.tsx`. The three explanation blocks and the legend are today's `StructuredConcurrencyInfo` body, opened up (no fold: the tab is the fold), with T-6's failure text and H-4's sampling note:

```tsx
import type { ReactNode } from 'react'
import { FiAlertTriangle, FiCheckCircle, FiClock, FiInfo } from 'react-icons/fi'

/**
 * The Learn tab (spec S-9, triage T-6): how to read the page. The legend and
 * the structured-concurrency explanation that used to sit on the page, the
 * sampling note (H-4), and a demo's own pipeline view when it has one. Story
 * mode joins it in Part 9.
 *
 * Everything here explains rather than records, and says so (S-6).
 */
export function LearnPanel({ children }: { children?: ReactNode }) {
  return (
    <div className="space-y-4 text-sm">
      <p className="flex items-center gap-2 text-xs text-default-500">
        <FiInfo className="h-4 w-4 text-primary" aria-hidden />
        <span>Explanation, not recorded data</span>
      </p>

      {children}

      <section aria-labelledby="learn-legend-heading" className="rounded-lg border border-default-200 p-3">
        <h3 id="learn-legend-heading" className="mb-2 text-xs font-semibold text-default-700">
          How to read the tree
        </h3>
        <div data-testid="learn-legend" className="grid grid-cols-2 gap-2 text-xs sm:grid-cols-3">
          <div className="flex items-center gap-2">
            <span className="inline-block h-2 w-2 rounded-full bg-primary animate-pulse"></span>
            <span>Active</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="inline-block h-2 w-2 rounded-full bg-primary"></span>
            <span>Waiting for children</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="inline-block h-2 w-2 rounded-full bg-warning"></span>
            <span>Suspended</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="inline-block h-2 w-2 rounded-full bg-success"></span>
            <span>Completed</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="inline-block h-2 w-2 rounded-full bg-danger"></span>
            <span>Failed</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="inline-block h-2 w-2 rounded-full bg-warning opacity-60"></span>
            <span>Cancelled</span>
          </div>
        </div>
      </section>

      <div className="grid gap-3 lg:grid-cols-3">
        <div className="flex items-start gap-3 rounded-lg bg-primary/10 p-3">
          <FiClock className="mt-0.5 h-4 w-4 flex-shrink-0 text-primary" aria-hidden />
          <div>
            <div className="font-semibold text-primary">Waiting for children</div>
            <div className="mt-1 text-xs text-default-600">
              When a parent's own code finishes while its children still run, it waits for them. Structured
              concurrency means a parent never finishes before its children.
            </div>
          </div>
        </div>
        <div className="flex items-start gap-3 rounded-lg bg-success/10 p-3">
          <FiCheckCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-success" aria-hidden />
          <div>
            <div className="font-semibold text-success">Normal completion</div>
            <div className="mt-1 text-xs text-default-600">
              A coroutine is Completed only when its own code and all of its children have finished.
            </div>
          </div>
        </div>
        <div className="flex items-start gap-3 rounded-lg bg-danger/10 p-3">
          <FiAlertTriangle className="mt-0.5 h-4 w-4 flex-shrink-0 text-danger" aria-hidden />
          <div>
            <div className="font-semibold text-danger">How a failure spreads</div>
            <div className="mt-1 text-xs text-default-600">
              A child that throws is recorded Failed. In an ordinary Job its failure fails the parent, which
              cancels its other children: they are recorded Cancelled. Under a SupervisorJob (or inside
              supervisorScope) the other children keep running and the parent does not fail.
            </div>
          </div>
        </div>
      </div>

      <p data-testid="learn-sampling" className="text-xs text-default-500">
        This page shows what vizcore captured. For an attached app, coroutines are sampled on a poll, so a step
        shorter than the poll can be missed and durations are approximate; a demo's wrappers record each step
        as it happens. When the page says nothing was found, it means nothing in the captured coroutines.
      </p>
    </div>
  )
}
```

The implementer copies the legend rows from `StructuredConcurrencyInfo.tsx` (the `Visual Indicators` grid) into the marked place, then `git rm src/components/StructuredConcurrencyInfo.tsx src/components/StructuredConcurrencyInfo.test.tsx`.

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/components/workspace/LearnPanel.test.tsx`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit** (with Task 24)

---

### Task 21: The Inspector's empty state is the session summary (T-3)

H-4 asks for the sampling explanation where a user first meets an empty result. Two places: the session summary when nothing was captured ("Nothing captured yet. vizcore samples an attached app's coroutines on a poll, so one that starts and ends between two polls may not appear."), and the main view's empty state for an attached app (`CoroutineCanvas`, the "No live coroutines yet" description gains the same clause; a test in `CoroutineCanvas.test.tsx` checks for "on a poll").

**Files:**
- Create: `src/components/workspace/SessionSummary.tsx`, `src/components/workspace/SessionSummary.test.tsx`
- Modify: `src/components/workspace/Inspector/Inspector.tsx`
- Test: `src/components/workspace/Inspector/Inspector.test.tsx`

- [ ] **Step 1: Write the failing tests**

`src/components/workspace/SessionSummary.test.tsx`:

```tsx
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

const metrics = vi.fn()
vi.mock('@/hooks/use-session-metrics', () => ({ useSessionMetrics: (...args: unknown[]) => metrics(...args) }))
vi.mock('../SessionMetrics', () => ({ SessionMetrics: () => <div data-testid="session-metrics" /> }))

import { SessionSummary } from './SessionSummary'

describe('SessionSummary — the Inspector with nothing selected (T-3)', () => {
  it('invites a selection, says what was captured and shows the metric tiles', () => {
    metrics.mockReturnValue({ data: { leaks: [], leakThresholdMs: 60_000 } })
    render(<SessionSummary sessionId="s-1" enabled coroutineCount={7} eventCount={54} />)
    expect(screen.getByText('Select a coroutine to inspect it')).toBeInTheDocument()
    expect(screen.getByTestId('session-captured')).toHaveTextContent('7 coroutines and 54 events captured')
    expect(screen.getAllByTestId('session-metrics')).toHaveLength(1)
  })

  it('lists long-lived coroutines once, with their age in words', () => {
    metrics.mockReturnValue({ data: { leaks: [{ coroutineId: 'c-1', label: 'poll', aliveMs: 46_000 }], leakThresholdMs: 30_000 } })
    render(<SessionSummary sessionId="s-1" enabled coroutineCount={1} eventCount={2} />)
    expect(screen.getByText(/poll · alive 46\.0 s/)).toBeInTheDocument()
  })

  it('explains sampling where nothing has been captured yet (H-4)', () => {
    metrics.mockReturnValue({ data: undefined })
    render(<SessionSummary sessionId="s-1" enabled coroutineCount={0} eventCount={0} />)
    expect(screen.getByTestId('session-captured')).toHaveTextContent('Nothing captured yet')
    expect(screen.getByTestId('session-captured')).toHaveTextContent('on a poll')
  })

  it('passes the protected-fetch flag through (no /metrics without a Bearer)', () => {
    metrics.mockReturnValue({ data: undefined })
    render(<SessionSummary sessionId="s-1" enabled={false} coroutineCount={0} eventCount={0} />)
    expect(metrics).toHaveBeenCalledWith('s-1', false, false)
  })
})
```

In `Inspector.test.tsx`, change the D-7 ordering test to read the cards' headings only (`within(inspector).getAllByRole('heading', { level: 4 })`), since the new `<h2>` names the coroutine, and add:

```tsx
  it('heads the inspector with the coroutine\'s name and state', async () => {
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} />, { wrapper: createWrapper() })
    const heading = screen.getByRole('heading', { level: 2 })
    expect(heading).toHaveTextContent(coroutine().label ?? coroutine().id)
    expect(heading).toHaveTextContent('Active')
  })

  it('shows the empty state it is given when nothing is selected', () => {
    render(<Inspector sessionId="s-1" coroutine={null} readOnly={false} emptyState={<p>summary</p>} />, {
      wrapper: createWrapper(),
    })
    expect(screen.getByText('summary')).toBeInTheDocument()
  })
```

(`coroutine()` is the file's existing fixture; if its state is not ACTIVE, assert that state's word from `stateLabel`.)

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm vitest run src/components/workspace/SessionSummary.test.tsx src/components/workspace/Inspector`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/components/workspace/SessionSummary.tsx`:

```tsx
import { useSessionMetrics } from '@/hooks/use-session-metrics'
import { SessionMetrics } from '../SessionMetrics'
import { LeakList } from '../LeakList'

/**
 * What the Inspector shows with nothing selected (triage T-3): the session's
 * metric tiles and its long-lived coroutines. Live view only — in replay the
 * numbers would describe now over a frozen view, and the shared view has no
 * Bearer for /metrics (M-4). The leak list renders once, from the same query
 * the tiles use (React Query dedupes the key; PD-02). The app/vizcore split,
 * and "in retained events since" once history is trimmed, join it with the
 * health payload in Part 7.
 */
export function SessionSummary({
  sessionId,
  enabled,
  coroutineCount,
  eventCount,
}: {
  sessionId: string
  enabled: boolean
  /** From the session snapshot: everything captured, vizcore's own connection included. */
  coroutineCount: number
  eventCount: number
}) {
  // A pure observer: the workspace owns the refresh cadence (#124).
  const { data: metrics } = useSessionMetrics(sessionId, false, enabled)
  return (
    <div data-testid="session-summary" className="space-y-4">
      <p className="text-sm text-default-400">Select a coroutine to inspect it</p>
      {coroutineCount === 0 ? (
        // H-4: where a user first meets an empty result, say how capture works.
        <p data-testid="session-captured" className="text-sm">
          Nothing captured yet.{' '}
          <span className="text-default-500">
            vizcore samples an attached app's coroutines on a poll, so one that starts and ends between two polls may not appear.
          </span>
        </p>
      ) : (
        <p data-testid="session-captured" className="text-sm">
          {coroutineCount} coroutines and {eventCount} events captured
          <span className="text-default-500">
            {' '}
            — what vizcore captured; which are your app's and which its own connection's arrives with the
            health API (Part 7).
          </span>
        </p>
      )}
      <SessionMetrics sessionId={sessionId} enabled={enabled} showLeaks={false} />
      {metrics && metrics.leaks.length > 0 && (
        <LeakList leaks={metrics.leaks} leakThresholdMs={metrics.leakThresholdMs} />
      )}
    </div>
  )
}
```

`Inspector.tsx`: add the prop

```tsx
  /** What to show while nothing is selected; overrides `placeholder` (T-3's session summary). */
  emptyState?: ReactNode
```

the empty branch becomes `return emptyState ?? <div className="text-sm text-default-400">{placeholder}</div>` (wrap in a fragment for the type), and the populated branch:

```tsx
  return (
    <div data-testid="inspector" className="space-y-3">
      <h2 className="flex flex-wrap items-baseline gap-x-2 text-base font-semibold">
        <span className="font-mono [overflow-wrap:anywhere]">{coroutine.label ?? coroutine.id}</span>
        <span className="text-sm font-normal text-default-500">{stateLabel(coroutine.state)}</span>
      </h2>
      {/* Two columns on a wide dock, one below 1,024 px (S-14). D-7's order
          reads down the first column, then the events. */}
      <div className="grid gap-3 lg:grid-cols-2">
        <div className="min-w-0 space-y-3">
          {showTimeline && (
            <>
              <TimingCard timeline={timeline} receivedAtMs={dataUpdatedAt || undefined} />
              <SuspendedAtCard sessionId={sessionId} coroutineId={coroutine.id} />
              <RunsOnCard runsOn={runsOn} />
            </>
          )}
          <IdentityCard coroutine={coroutine} />
        </div>
        {showTimeline && (
          <div className="min-w-0">
            <EventsCard timeline={timeline} />
          </div>
        )}
      </div>
    </div>
  )
```

Imports: `type ReactNode` from react, `stateLabel` from `@/lib/coroutine-state-colors`. Update the doc comment's first line: "The Inspector tab (D-7, S-4), ordered most-diagnostic-first:". In `InspectorCard.tsx` change "in a ~320px column" to "in a narrow column".

- [ ] **Step 4: Run them and see them pass**

Run: `pnpm vitest run src/components/workspace/SessionSummary.test.tsx src/components/workspace/Inspector`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/components/workspace/SessionSummary.tsx src/components/workspace/SessionSummary.test.tsx src/components/workspace/Inspector
git commit -m "feat(frontend): the Inspector heads with the coroutine and sums up the session when empty"
```

---

### Task 22: Events as a tab

**Files:**
- Create: `src/components/workspace/EventsPanel.tsx`, `src/components/workspace/EventsPanel.test.tsx` (from `EventsDrawer` and its test)
- Delete: `src/components/workspace/EventsDrawer.tsx`, `src/components/workspace/EventsDrawer.test.tsx`

- [ ] **Step 1: Write the failing test** — `git mv` the drawer test to `EventsPanel.test.tsx` and replace it with:

```tsx
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { VizEvent } from '@/types/api'

vi.mock('../EventsList', () => ({
  EventsList: ({ events }: { events: VizEvent[] }) => <div data-testid="events-list" data-count={events.length} />,
}))

import { EventsPanel } from './EventsPanel'

function event(seq: number, coroutineId?: string): VizEvent {
  return {
    sessionId: 's-1',
    seq,
    tsNanos: seq * 1_000,
    kind: 'coroutine.created',
    ...(coroutineId
      ? { coroutineId, jobId: `job-${coroutineId}`, parentCoroutineId: null, scopeId: 'scope-1', label: `label-${seq}` }
      : {}),
  } as VizEvent
}

const EVENTS: VizEvent[] = [event(1, 'c-1'), event(2, 'c-2'), event(3, 'c-1'), event(4)]

describe('EventsPanel', () => {
  it('lists the whole session when nothing is selected', () => {
    render(<EventsPanel events={EVENTS} selectedCoroutine={null} />)
    expect(screen.getByTestId('events-scope')).toHaveTextContent('All 4 events in this session')
    expect(screen.getByTestId('events-list')).toHaveAttribute('data-count', '4')
  })

  it('narrows to the selected coroutine and says whose events these are', () => {
    render(<EventsPanel events={EVENTS} selectedCoroutine={{ id: 'c-1', label: 'fetchUser' }} />)
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events for fetchUser · 2')
    expect(screen.getByTestId('events-list')).toHaveAttribute('data-count', '2')
  })

  it('falls back to the coroutine id when it has no label', () => {
    render(<EventsPanel events={EVENTS} selectedCoroutine={{ id: 'c-1', label: null }} />)
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events for c-1')
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/components/workspace/EventsPanel.test.tsx`
Expected: FAIL — cannot resolve `./EventsPanel`.

- [ ] **Step 3: Implement** — `git mv EventsDrawer.tsx EventsPanel.tsx`, rename the component, drop the disclosure state and button, and return:

```tsx
  return (
    <div className="space-y-3">
      <p data-testid="events-scope" className="text-xs text-default-500">
        {who ? (
          <>
            Events for <span className="font-mono text-default-700">{who}</span> · {scoped.length}
          </>
        ) : (
          `All ${events.length} events in this session`
        )}
      </p>
      <EventsList events={scoped} />
    </div>
  )
```

Doc comment: "The Events tab (spec S-4): the raw event stream, scoped to what is selected in the tree. It narrows to the selected coroutine rather than making the developer search a session-wide list for the id they just clicked. Events with no coroutineId are session-scoped and belong to the unselected view."

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/components/workspace/EventsPanel.test.tsx`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit** (with Task 24)

---

### Task 23: What opens first, and the remembered tab (S-5)

**Files:**
- Create: `src/lib/dock-tab-memory.ts`, `src/lib/dock-tab-memory.test.ts`

- [ ] **Step 1: Write the failing test** — `src/lib/dock-tab-memory.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest'
import { DOCK_TABS_KEY, openingDockTab, rememberDockTab, rememberedDockTab } from './dock-tab-memory'

beforeEach(() => localStorage.clear())

describe('openingDockTab (S-5 without Problems)', () => {
  it('opens a demo started with its scenario on Learn while it has not finished', () => {
    expect(openingDockTab({ startedAsDemo: true, outcome: 'notStarted' })).toBe('learn')
    expect(openingDockTab({ startedAsDemo: true, outcome: 'running' })).toBe('learn')
  })

  it('opens a finished or failed demo on the Inspector', () => {
    expect(openingDockTab({ startedAsDemo: true, outcome: 'finished' })).toBe('inspector')
    expect(openingDockTab({ startedAsDemo: true, outcome: 'failed' })).toBe('inspector')
  })

  it('opens an attached app on the Inspector', () => {
    expect(openingDockTab({ startedAsDemo: false, outcome: null })).toBe('inspector')
  })
})

describe('remembered tab', () => {
  it('is per session', () => {
    rememberDockTab('s-1', 'events')
    expect(rememberedDockTab('s-1')).toBe('events')
    expect(rememberedDockTab('s-2')).toBeNull()
  })

  it('keeps the 50 most recent sessions', () => {
    for (let i = 0; i < 55; i++) rememberDockTab(`s-${i}`, 'learn')
    expect(rememberedDockTab('s-0')).toBeNull()
    expect(rememberedDockTab('s-54')).toBe('learn')
    expect(JSON.parse(localStorage.getItem(DOCK_TABS_KEY)!)).toHaveLength(50)
  })

  it('ignores what it cannot read, including a tab that no longer exists', () => {
    localStorage.setItem(DOCK_TABS_KEY, JSON.stringify([['s-1', 'problems'], ['s-2', 'events']]))
    expect(rememberedDockTab('s-1')).toBeNull()
    expect(rememberedDockTab('s-2')).toBe('events')
    localStorage.setItem(DOCK_TABS_KEY, 'nope')
    expect(rememberedDockTab('s-2')).toBeNull()
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/lib/dock-tab-memory.test.ts`
Expected: FAIL — cannot resolve `./dock-tab-memory`.

- [ ] **Step 3: Implement** — `src/lib/dock-tab-memory.ts`:

```ts
import type { DockTabId } from '@/components/workspace/DockTabs'
import type { RunOutcome } from './run-outcome'

/**
 * Which dock tab a session opens on (spec S-5), and the tab the user last
 * picked for it. Problems is not offered until Part 7, so the "detected
 * problem" and "attached app" rows open on the Inspector, whose empty state
 * is the session summary.
 */
export const DOCK_TABS_KEY = 'vizcore.dockTabs'
const KEEP = 50
const TABS: readonly DockTabId[] = ['inspector', 'evidence', 'events', 'learn']

export function openingDockTab({
  startedAsDemo,
  outcome,
}: {
  /** Opened with its scenario: from the Gallery or the new-demo dialog. */
  startedAsDemo: boolean
  outcome: RunOutcome | null
}): DockTabId {
  return startedAsDemo && (outcome === 'notStarted' || outcome === 'running') ? 'learn' : 'inspector'
}

function read(): Array<[string, DockTabId]> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(DOCK_TABS_KEY) ?? '[]')
    if (!Array.isArray(parsed)) return []
    return parsed.filter(
      (e): e is [string, DockTabId] =>
        Array.isArray(e) && typeof e[0] === 'string' && TABS.includes(e[1] as DockTabId),
    )
  } catch {
    return []
  }
}

export function rememberedDockTab(sessionId: string): DockTabId | null {
  return read().find(([id]) => id === sessionId)?.[1] ?? null
}

/** Most recent first; the oldest sessions fall off after [KEEP]. */
export function rememberDockTab(sessionId: string, tab: DockTabId): void {
  const next = [[sessionId, tab] as [string, DockTabId], ...read().filter(([id]) => id !== sessionId)].slice(0, KEEP)
  try {
    localStorage.setItem(DOCK_TABS_KEY, JSON.stringify(next))
  } catch {
    // Private mode or storage full: the tab still works for this visit.
  }
}
```

- [ ] **Step 4: Run it and see it pass**

Run: `pnpm vitest run src/lib/dock-tab-memory.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/lib/dock-tab-memory.ts src/lib/dock-tab-memory.test.ts
git commit -m "feat(frontend): choose the dock's opening tab and remember the one picked"
```

---

### Task 24: Tabs in the dock

**Files:**
- Modify: `src/components/SessionWorkspace.tsx`
- Test: `src/components/SessionWorkspace.test.tsx`, `src/components/SessionWorkspace.reachability.test.tsx`

- [ ] **Step 1: Write the failing tests** — in `SessionWorkspace.test.tsx`, replace the describe "the tabs are retired" with:

```tsx
describe('SessionWorkspace — the dock tabs (S-4, S-5)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    mockedUseSession.mockReturnValue({
      data: makeSession({ coroutineCount: 1 }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
  })

  it('offers Inspector, Evidence, Events and Learn — Problems stays hidden until Part 7', () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    expect(screen.getAllByRole('tab').map(t => t.textContent)).toEqual(['Inspector', 'Evidence', 'Events', 'Learn'])
    expect(screen.queryByRole('tab', { name: /problems/i })).toBeNull()
  })

  it('keeps the same tabs in the shared view and in replay', async () => {
    const { unmount } = render(<SessionWorkspace sessionId="session-1" readOnly />, { wrapper: createWrapper() })
    expect(screen.getAllByRole('tab')).toHaveLength(4)
    unmount()
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))
    expect(screen.getAllByRole('tab')).toHaveLength(4)
  })

  it('opens an attached app on the Inspector, with the session summary', () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    expect(screen.getByRole('tab', { name: 'Inspector' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('Select a coroutine to inspect it')).toBeVisible()
    expect(screen.getAllByTestId('session-metrics')).toHaveLength(1)
  })

  it('opens a demo started with its scenario on Learn while it runs', () => {
    render(<SessionWorkspace sessionId="session-1" scenarioId="basics/launch" scenarioName="Launch" />, {
      wrapper: createWrapper(),
    })
    expect(screen.getByRole('tab', { name: 'Learn' })).toHaveAttribute('aria-selected', 'true')
  })

  it('opens a failed demo on the Inspector', () => {
    mockedUseSession.mockReturnValue({
      data: makeSession({
        coroutineCount: 1,
        coroutines: [{ id: 'c-1', jobId: 'j', parentId: null, scopeId: 's', label: 'x', state: 'FAILED' as CoroutineState }],
      }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    render(<SessionWorkspace sessionId="session-1" scenarioId="basics/launch" />, { wrapper: createWrapper() })
    expect(screen.getByRole('tab', { name: 'Inspector' })).toHaveAttribute('aria-selected', 'true')
  })

  it('remembers the tab the user picked for this session', async () => {
    const { unmount } = render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('tab', { name: 'Events' }))
    unmount()
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    expect(screen.getByRole('tab', { name: 'Events' })).toHaveAttribute('aria-selected', 'true')
  })

  it('names what Evidence holds', async () => {
    const { useSessionEvents } = await import('@/hooks/use-sessions')
    vi.mocked(useSessionEvents).mockReturnValue({
      data: [
        { sessionId: 's', seq: 1, tsNanos: 1, kind: 'MutexCreated', mutexId: 'm-1', mutexLabel: null, ownerCoroutineId: null },
        { sessionId: 's', seq: 2, tsNanos: 2, kind: 'SemaphoreCreated', semaphoreId: 'p-1', semaphoreLabel: null, totalPermits: 2 },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    expect(screen.getByRole('tab', { name: 'Evidence · 1 lock, 1 semaphore' })).toBeInTheDocument()
  })

  it('unfolds a folded dock when a tab is chosen', async () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: 'Fold the dock' }))
    await userEvent.click(screen.getByRole('tab', { name: 'Learn' }))
    expect(screen.getByRole('button', { name: 'Fold the dock' })).toBeInTheDocument()
    expect(screen.getByTestId('learn-legend')).toBeVisible()
  })

  it('reaches the dispatchers in replay through Evidence', async () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))
    await userEvent.click(screen.getByRole('tab', { name: /^Evidence/ }))
    await userEvent.click(screen.getByRole('button', { name: /Dispatchers/ }))
    expect(screen.getByTestId('dispatcher-overview')).toBeInTheDocument()
  })
})
```

Keep the existing `'never invites a selection that cannot work …(#145)'` test as it is (both texts are in the Inspector tab, which is selected). In `SessionWorkspace.reachability.test.tsx` add, with that file's helpers (its first test already clicks a live node in the real list):

```tsx
  it('switches the dock to the Inspector when a coroutine is selected', async () => {
    render(<SessionWorkspace sessionId="session-1" scenarioId="basics/launch" />, { wrapper: createWrapper() })
    expect(screen.getByRole('tab', { name: 'Learn' })).toHaveAttribute('aria-selected', 'true')
    await userEvent.click(screen.getByRole('button', { name: 'List' }))
    await userEvent.click(screen.getAllByRole('button', { name: /^Open source for/ })[0]!)
    expect(screen.getByRole('tab', { name: 'Inspector' })).toHaveAttribute('aria-selected', 'true')
  })
```

(with a session whose coroutines are still running, so it opens on Learn).

Update the other existing tests:

| Test | Change |
|---|---|
| `'the threads evidence panel renders thread activity …(UAT gap 1)'` | click the `Evidence` tab, then the `Threads` button; expect `worker-1` and `worker-2` in the table; replace the ASSIGNED-chip assertion with the row reading `1` in "Assignments observed" (`within(getByRole('row', { name: /worker-1/ })).getAllByText('1')`) |
| `'shows LiveDataNotice on a projection-backed tab while replaying (D-17)'` | unchanged: the notices are mounted (hidden) in the live-data sections, so `getAllByTestId('live-data-notice')` finds them; Threads has none, because its table follows the cursor |
| `'puts the tree above the explanation'` | rename to `'keeps the explanation in Learn'`; click the `Learn` tab; expect `getByTestId('learn-legend')` |
| `'mounts the metric strip below the live canvas'`, `'mounts SessionMetrics exactly once'`, `'keeps thread lanes and the metric strip on one screen …'` | the metric strip is in the Inspector tab (selected by default for `session-1`); drop the dispatcher assertion from the last one (covered by `'reaches the dispatchers in replay through Evidence'`) |
| `vi.mock('./StructuredConcurrencyInfo', …)` | delete the mock |
| `'folds the dock to its bar'` (3a) | `inspector-slot` is gone: assert `getByRole('tabpanel', { name: 'Inspector', hidden: true })` is not visible |
| `'invites the next rung instead of hiding what is missing'` | `Lock contention` is now the `Locks and permits` row reading "none captured"; keep the `Swap Mutex() for VizMutex()` assertion |
| the `'never invites a selection …(#145)'` test of the retired describe | move it into the new describe unchanged |
| `SessionWorkspace.reachability.test.tsx` `'renders the read-only shared view with no metric strip …'` | unchanged (no `SessionSummary` in the shared view) |

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm vitest run src/components/SessionWorkspace`
Expected: FAIL — no tabs.

- [ ] **Step 3: Implement** — in `src/components/SessionWorkspace.tsx`:

1. Imports: add `DockTabs`, `DockTabPanel`, `type DockTabId` from `./workspace/DockTabs`; `EventsPanel`; `LearnPanel`; `SessionSummary`; `countEvidence`, `evidenceTabLabel`; `openingDockTab`, `rememberDockTab`, `rememberedDockTab`. Remove `EventsDrawer`, `StructuredConcurrencyInfo`, `SessionMetrics`, `LeakList`, and the `metrics`/`leakIds` lines stay (the state bar's leak chip reads `leakIds`).

2. After `outcome` (3a), add:

```tsx
  // Which dock tab is showing (S-5). The opening tab is decided once, when the
  // session has loaded: a demo that finishes while you watch stays where you
  // are. A tab the user picks wins, and is remembered for this session.
  const [pickedTab, setPickedTab] = useState<DockTabId | null>(null)
  const [openingTab, setOpeningTab] = useState<DockTabId | null>(null)
  if (openingTab === null && session) {
    setOpeningTab(rememberedDockTab(sessionId) ?? openingDockTab({ startedAsDemo: hasScenario, outcome: outcome?.outcome ?? null }))
  }
  const dockTab: DockTabId = pickedTab ?? openingTab ?? 'inspector'
  const selectDockTab = (tab: DockTabId) => {
    setPickedTab(tab)
    rememberDockTab(sessionId, tab)
    // A tab chosen on a folded dock is a tab you want to read.
    if (dock.folded) dock.toggleFold()
  }

  // What the Evidence tab holds, named on its label (S-4).
  const evidenceCounts = useMemo(() => countEvidence(panelEvents), [panelEvents])
  // "Time into the capture" for the thread table: the first retained event.
  const captureStartNanos = useMemo(
    () => (panelEvents.length > 0 ? Math.min(...panelEvents.map(e => e.tsNanos)) : null),
    [panelEvents],
  )
```

   (`Math.min(...)` over 10,000 events is fine — the live history is capped at 10,000; use a loop if lint or perf asks.)

3. The canvas's `onSelect` becomes:

```tsx
          onSelect={
            isLiveView
              ? (id: string) => {
                  setSelectedCoroutineId(id)
                  // A selection is read in the Inspector: show it (S-4).
                  selectDockTab('inspector')
                }
              : undefined
          }
```

4. The `SessionDock` becomes:

```tsx
          <SessionDock
            height={dock.height}
            maxHeight={dock.maxHeight}
            folded={dock.folded}
            hidden={dock.maximised}
            onResize={dock.resize}
            onToggleFold={dock.toggleFold}
            bar={
              <DockTabs
                // Problems ("what is wrong?") is the fifth question of S-4. It
                // stays hidden, not empty, until the health API (Part 5) and its
                // tab (Part 7) land.
                tabs={[
                  { id: 'inspector', label: 'Inspector' },
                  { id: 'evidence', label: evidenceTabLabel(evidenceCounts) },
                  { id: 'events', label: 'Events' },
                  { id: 'learn', label: 'Learn' },
                ]}
                selected={dockTab}
                onSelect={selectDockTab}
              />
            }
          >
            <DockTabPanel id="inspector" selected={dockTab === 'inspector'}>
              <Inspector
                sessionId={sessionId}
                coroutine={isLiveView ? selectedCoroutine : null}
                readOnly={readOnly}
                // T-3's session summary, live view only (M-4).
                emptyState={
                  isLiveView ? (
                    <SessionSummary
                      sessionId={sessionId}
                      enabled={!readOnly}
                      coroutineCount={session.coroutineCount}
                      eventCount={session.eventCount}
                    />
                  ) : undefined
                }
                // Selection is live-only, so outside the live view the inspector
                // must not invite a click that cannot work (#145).
                placeholder={
                  replayActive
                    ? 'The inspector is paused during replay. Exit replay to inspect a coroutine.'
                    : readOnly
                      ? 'Coroutine details are not included in a shared link.'
                      : undefined
                }
                threadActivity={panelThreadActivity}
              />
            </DockTabPanel>
            <DockTabPanel id="evidence" selected={dockTab === 'evidence'}>
              <EvidencePanels
                sessionId={sessionId}
                rung={rung}
                categories={eventCategories}
                counts={evidenceCounts}
                replayActive={replayActive}
                readOnly={readOnly}
                threadActivity={panelThreadActivity}
                captureStartNanos={captureStartNanos}
              />
            </DockTabPanel>
            <DockTabPanel id="events" selected={dockTab === 'events'}>
              {/* Mounted only while shown: a long event list is the heaviest
                  render on the page, and a hidden one would re-render on every
                  live flush. It fetches nothing, so mounting later costs no
                  request. Every other panel stays mounted and is hidden. */}
              {dockTab === 'events' && <EventsPanel events={panelEvents} selectedCoroutine={selectedCoroutine} />}
            </DockTabPanel>
            <DockTabPanel id="learn" selected={dockTab === 'learn'}>
              <LearnPanel>
                {/* Scenario Pipeline View - shown for realistic scenarios */}
                {scenarioId === 'order-processing' && allEvents.length > 0 && (
                  <OrderProcessingView events={allEvents} />
                )}
                {scenarioId === 'user-registration' && allEvents.length > 0 && (
                  <RegistrationFlowView events={allEvents} />
                )}
              </LearnPanel>
            </DockTabPanel>
          </SessionDock>
```

5. `SessionDock`'s body keeps `p-4`; the tab panels need nothing more.

- [ ] **Step 4: Run the workspace tests and see them pass**

Run: `pnpm vitest run src/components/SessionWorkspace src/components/workspace src/routes`
Expected: PASS.

- [ ] **Step 5: Gates**

Run: `pnpm exec tsc --noEmit && pnpm lint && pnpm test && pnpm build`
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add -A src
git commit -m "feat(frontend): the dock's tabs re-host the inspector, evidence, events and learn"
```

---

### Task 25: The replay slider announces its time (S-14)

**Files:**
- Modify: `src/hooks/use-replay.ts` (return the frozen `events` it replays)
- Create: `src/lib/replay-time.ts`, `src/lib/replay-time.test.ts`
- Modify: `src/components/replay/ReplayController.tsx` (the slider's accessible value)
- Test: `src/components/replay/ReplayController.test.tsx`

- [ ] **Step 1: Write the failing tests**

`src/lib/replay-time.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { replayTimeText } from './replay-time'
import type { VizEvent } from '@/types/api'

const ev = (tsNanos: number) => ({ seq: 1, tsNanos, sessionId: 's', kind: 'CoroutineCreated' }) as unknown as VizEvent

describe('replayTimeText', () => {
  it('reads the cursor as seconds of the whole replay', () => {
    expect(replayTimeText([ev(1e9), ev(5.2e9), ev(13e9)], 1)).toBe('4.2 seconds of 12.0')
  })

  it('reads zero at the start and the whole length at the end', () => {
    expect(replayTimeText([ev(0), ev(12e9)], 0)).toBe('0.0 seconds of 12.0')
    expect(replayTimeText([ev(0), ev(12e9)], 1)).toBe('12.0 seconds of 12.0')
  })

  it('is undefined with nothing to replay', () => {
    expect(replayTimeText([], 0)).toBeUndefined()
  })
})
```

In `ReplayController.test.tsx` add: rendering with `makeReplay({ events: [ev(0), ev(4.2e9), ev(12e9)], currentIndex: 1, totalEvents: 3 })` gives the slider's input (`screen.getByRole('slider')`) `aria-valuetext` "4.2 seconds of 12.0".

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm vitest run src/lib/replay-time.test.ts src/components/replay/ReplayController.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/lib/replay-time.ts`:

```ts
import type { VizEvent } from '@/types/api'

/**
 * What the replay slider announces (S-14): "4.2 seconds of 12.0", from the
 * first event of the frozen replay to the cursor's, over the whole replay.
 * The slider still steps by event (seconds arrive with Part 8); this only
 * says where in time each step is.
 */
export function replayTimeText(events: readonly VizEvent[], index: number): string | undefined {
  const first = events[0]
  const last = events[events.length - 1]
  const at = events[Math.min(Math.max(index, 0), events.length - 1)]
  if (!first || !last || !at) return undefined
  const s = (nanos: number) => (nanos / 1e9).toFixed(1)
  return `${s(at.tsNanos - first.tsNanos)} seconds of ${s(last.tsNanos - first.tsNanos)}`
}
```

`use-replay.ts`: add `events: VizEvent[]` to `ReplayState` and return the `events` argument (the frozen snapshot) from the hook. `ReplayController`: compute `const timeText = replayTimeText(replay.events, currentIndex)` and give it to the slider — HeroUI's `Slider` forwards `getValue` to the visible output only, so pass the text to the thumb input through react-aria's `aria-valuetext` (check `@heroui/slider`'s `getThumbProps`; if no prop reaches the input, set it in an effect on `sliderRef.current?.querySelector('input[type=range]')`). Keep the visible "Event N / M" counter. Update `makeReplay` in the test file with `events: []`.

- [ ] **Step 4: Run them and see them pass**, then commit:

```bash
git add src/lib/replay-time.ts src/lib/replay-time.test.ts src/hooks/use-replay.ts src/components/replay
git commit -m "feat(frontend): the replay slider says where in time it is"
```

---

### Task 26: Narrow widths (S-14)

Most of S-14 is already in place after Task 24: the tab row scrolls sideways (`flex-nowrap overflow-x-auto`), the Inspector stacks below `lg` (1,024 px), the thread table scrolls in its own box, graph cards are buttons (Tab, Enter) and the list's cards are pressable. What remains is checked in the browser:

- [ ] At 900 px: header wraps to two lines without overlapping; the main view toolbar wraps; the dock's tab row scrolls; the Inspector is one column.
- [ ] Keyboard only: Tab reaches a graph card, Enter selects it and the Inspector tab shows it; Tab reaches the separator (↑/↓/Enter work), the tab row (←/→/Home/End), the Fold button and the panel.
- [ ] Fix anything found with a test first where it is testable in jsdom (class names, roles), and commit as `fix(frontend): …`.

---

### Task 27: Browser check (3a + 3b), reviews and PRs

- [ ] **Servers** (only these ports):

```bash
cd /Users/jirihermann/Documents/workspace-vizcore/vizcore-dock/backend && JAVA_HOME=/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home PORT=18801 CORS_ALLOWED_ORIGINS=http://localhost:13801 ./gradlew :run -q &
cd /Users/jirihermann/Documents/workspace-vizcore/vizcore-dock/frontend && VITE_PROXY_TARGET=http://localhost:18801 pnpm dev --port 13801 --strictPort &
```

  If the backend run rewrites `backend/docs/index.html`, `git checkout -- backend/docs/index.html`.

- [ ] **Sessions** (≥ 7 s apart; ≤ 10 per minute): a live demo (Basics), a finished demo, a failed demo (an Exception demo), replay of a finished demo, the shared view if sharing is enabled (database mode), and a real-app-like session if one can be produced (an `examples/` app or a session posted through the ingest API).
- [ ] **At 1440 and 900 px, dark and light:** header facts (outcome, view), graph filling its box, dock at 40 %, drag and keyboard resize, Fold (button and Enter), Maximise / Restore, reload keeps size and fold, tab keyboard navigation, Evidence label and sections, thread table, Learn, Inspector heading and two columns / one column, Events scoped to a selection. Screenshots to `/private/tmp/claude-501/-Users-jirihermann-Documents-workspace-vizcore/9e9c5bc5-1b4c-412c-966d-6cf483ca2458/scratchpad/part3-shots/`.
- [ ] Stop both servers.
- [ ] **Codex code review**, up to 3 rounds, fixing P1/P2: 3a against `origin/feat/session-quick-wins`, 3b against `feat/session-dock-shell`.
- [ ] **PRs** to `main`: 3a ("stacked on #290; merge after it"), 3b ("stacked on 3a"). Body: What (before/after) / Tests / Browser check / Codex review / Known, not in this PR (Problems tab and connection pill — Part 7; shared selection, live lock facts — Part 4; Find, rounds, grouping — Part 6; Inspector during replay and the seconds slider — Part 8; Run again and story — Part 9; Timeline — Part 10).
