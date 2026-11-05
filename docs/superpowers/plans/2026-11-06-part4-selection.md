# Part 4 · Shared selection and live facts — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One selection across the tree, the dock tabs and the event list (S-2); a second line on every node saying what it waits for or holds (S-6); and lock, permit and queue facts in Evidence that follow the events the page already holds, so they refresh live.

**Architecture:** Frontend only. Two new pure modules under `src/lib/` do the reading: `projections/project-sync.ts` (the mutex/semaphore reducer moved out of `useSyncEvents`, extended with permit counts per holder and permits whose holder is unknown) and `activity.ts` (one `ActivityLine` per coroutine, from `projectSync` + Part 2's `projectWaits`). A third, `selection.ts`, says what an event is about. `SessionWorkspace` computes everything from `panelEvents` — the live stream's events, the stored history when the view is paused, or the replay cursor's — and replaces `selectedCoroutineId` with a `Selection` (a coroutine or a resource) that every part reads. The evidence hooks gain an optional `events` argument (the idiom of `useEventCategories(sessionId, liveEvents)`), so they stop reading only the stored history that live updates never invalidate (`use-sync-events.ts:57`).

**Tech Stack:** React 19, TypeScript, HeroUI 2.7, Tailwind (literal classes only, IN-12), Vitest + Testing Library + user-event. Every command runs from `frontend/`.

**Spec:** [`docs/superpowers/specs/2026-11-05-session-dock-design.md`](../specs/2026-11-05-session-dock-design.md) — Part 4 row ("S-2 and S-6: one selection across tree, tabs and events; the activity line on nodes; live lock, permit and queue facts that refresh on new events"), S-2, S-6, the Part 4 Testing bullet ("selection shared across tree, tabs and events, including resource-only events; the activity line updating live") and Risks. Builds on Part 2 (#292: `waitKind` / `resourceId` on `CoroutineSuspended`, `projectWaits`, semaphore holder counts and `holderUnknown`) and Part 3 (#293/#294: the dock). Boards `G1-App`, `G2-Story` show the second lines ("waits for inventory-lock · 1st", "holds inventory-lock", "holds a payment-gateway permit"); their data is illustrative and the spec wins.

**Branch:** `feat/session-selection`, from `origin/main` (2688b944, Parts 1, 2, 3a and 3b merged):

```bash
cd /Users/jirihermann/Documents/workspace-vizcore/vizcore
git fetch origin
git worktree add -b feat/session-selection ../vizcore-selection origin/main
cd ../vizcore-selection/frontend && pnpm install --frozen-lockfile
```

Gates (from `frontend/`, before the PR): `pnpm exec tsc --noEmit && pnpm lint && pnpm test && pnpm build`. Baseline on `origin/main`: 118 files, 1,074 tests. No backend change.

One PR. It is about half of Part 3b's size and every task leaves a working page, so it does not split.

---

## Decisions taken while planning

- **A selection is a coroutine or a resource** (`{ kind: 'coroutine', id }` or `{ kind: 'resource', resource: 'mutex' | 'semaphore' | 'channel', id }`). Groups (S-12) arrive with Part 6 and extend the type then.
- **What an event is about** (S-2): its `coroutineId`; else, for lock and permit events, the requester, acquirer or releaser; else the resource it names (`MutexCreated`, `MutexQueueChanged`, `SemaphoreCreated`, `SemaphoreStateChanged`, `ChannelCreated`, `ChannelClosed`, `ChannelBufferStateChanged`, and a release made outside any coroutine, whose releaser the wrappers record as `unknown`). Deadlock events and flow events without a coroutine select nothing. An event about a coroutine the session snapshot does not hold (vizcore's own, or one trimmed away) is not offered as a selection: selecting it would be cleared at once.
- **Where a selection takes you.** A node click opens the Inspector, as today. A click inside a tab keeps you on that tab — except a resource, which opens the Evidence tab and unfolds its section (Locks and permits, or Channels), rings the resource and scrolls it into view (S-2). Holder and waiter names in Locks and permits select their coroutine.
- **The Events tab follows the selection:** a coroutine's events (as before, now including the lock and permit events about it), or a resource's events (those naming its id, and the suspensions waiting for it), with "All events" to clear the selection. Every event row that is about something selectable is a button.
- **The Inspector with a resource selected shows the session summary**; a resource has no Inspector of its own in this part (its facts are in Evidence).
- **Selection stays live-only (PD-01).** In replay and the shared view nothing is selectable, as today; Part 8 makes the Inspector follow the cursor. The second lines and the Evidence facts are shown in every mode, read from the same events as the tree.
- **The second line** (S-6), one per coroutine, facts joined with ", ", the wait first:
  - lock: "waits for inventory-lock · 1st" (position in the latest observed queue), "holds inventory-lock";
  - permit: "waits for a payment-gateway permit · 2nd", "holds a payment-gateway permit", "holds 2 payment-gateway permits" — a permit is never numbered;
  - channel: "waits to send to orders", "waits to receive from orders";
  - a wait whose resource the producer did not name: "waits for a lock", "waits for a permit", "waits to send", "waits to receive".
  A wait comes from the suspension the wrapper recorded (`waitKind`, Part 2); an older producer records none, so the latest queue also says who waits. A resource is named by its label, else its id. A wait line uses the Suspended colour (`text-warning`, readable in both themes); a hold alone is neutral (`text-default-600`): a held lock is normal work (S-6).
- **"holder unknown"** (S-6/S-7): in Locks and permits, a semaphore lists its holders with their permit counts and, when permits came back from a coroutine that held none, "1 permit, holder unknown". No node gets that line: nobody is the holder.
- **Evidence follows the page's events.** `useSyncEvents`, `useChannelEvents`, `useFlowEvents` and `useJobEvents` take the events the workspace passes (`panelEvents`), falling back to the stored history when none are passed; they still subscribe to it, so the request profile (#124) is unchanged. In replay these sections therefore follow the cursor, like Threads, and lose their "showing current data" notice; Dispatchers, still a server read, keeps it.
- **The graph card grows 16 px** (224 → 240) to hold the second line under the state; the list view adds it under the id line.
- **Not in this part:** the paused view still reads the history loaded with the page (as Evidence did before); "Refresh" re-reads the snapshot only. Part 8 binds every read to one moment.

## Also in this PR (decided by the user)

- **Metric tiles** read "Active0", "Peak6": label and value sit in a `justify-between` row with no gap inside a card that shrinks to its content. The tile becomes a `<dl>` (term, definition) with a gap.
- **Design doc, S-3**, amended with two decisions made while building Part 3: Export stays its own menu next to "⋯" (a menu inside a menu is awkward); T-2's "last data" and start time wait for Part 7, because they need the health API's wall-clock observation time, not the browser's clock.

## File structure

| File | Change | Responsibility |
|---|---|---|
| `src/components/SessionMetrics.tsx` (+ test) | modify | tiles as `<dl>` with a gap |
| `docs/superpowers/specs/2026-11-05-session-dock-design.md` | modify | S-3 amendment, ownership row |
| `src/lib/projections/project-sync.ts` (+ test) | create | pure mutex/semaphore reducer; holder permit counts; unknown-holder permits |
| `src/hooks/use-sync-events.ts` (+ test) | modify | thin hook over `projectSync`; optional `events` |
| `src/hooks/use-channel-events.ts`, `use-flow-events.ts`, `use-job-events.ts` (+ tests) | modify | optional `events` |
| `src/lib/projections/project-waits.ts` | modify | accepts a readonly list |
| `src/lib/selection.ts` (+ test) | create | `Selection`, what an event is about, which events concern a selection, resource names, a row's title |
| `src/lib/activity.ts` (+ test) | create | the second line per coroutine; `ordinal` |
| `src/components/CoroutineTreeGraph.tsx` (+ test) | modify | second line on the card; card 240 px |
| `src/components/CoroutineTree.tsx` (+ test) | modify | second line in the list |
| `src/components/workspace/CoroutineCanvas.tsx` (+ test) | modify | passes `activity` through |
| `src/components/sync/CoroutineName.tsx` | create | a coroutine's name as a select button, highlighted when selected |
| `src/components/sync/SyncPanel.tsx`, `MutexStateIndicator.tsx`, `SemaphoreGauge.tsx`, `WaitQueueList.tsx` (+ test) | modify | events in; selected resource ringed and scrolled to; names select; holders with permit counts and "holder unknown" |
| `src/components/channels/ChannelPanel.tsx`, `flow/FlowPanel.tsx`, `jobs/JobPanel.tsx` (+ tests) | modify | events in; ChannelPanel follows a focused channel |
| `src/components/workspace/EvidencePanels.tsx` (+ test) | modify | passes events and selection; a selected resource unfolds its section; notice only on Dispatchers |
| `src/components/EventsList.tsx` (+ new test) | modify | rows are buttons selecting what they are about; a title for lock and permit rows |
| `src/components/workspace/EventsPanel.tsx` (+ test) | modify | scoped by `Selection`; "All events" |
| `src/components/workspace/Inspector/Inspector.tsx` (+ test) | modify | the second line under the heading |
| `src/components/SessionWorkspace.tsx` (+ new `SessionWorkspace.selection.test.tsx`, existing tests) | modify | one `Selection`; activity from `panelEvents`; wiring |

---

### Task 1: Metric tiles read "Active 0", not "Active0"

**Files:**
- Modify: `frontend/src/components/SessionMetrics.tsx` (`MetricCard`, bottom of the file)
- Test: `frontend/src/components/SessionMetrics.test.tsx`

- [ ] **Step 1: Write the failing test** — append inside `describe('SessionMetrics', …)`:

```tsx
  it('keeps each tile\'s label and value apart: a term and its value, with a gap', () => {
    useSessionMetricsMock.mockReturnValue({ data: metrics({ active: 0, peak: 6 }), isLoading: false })

    render(<SessionMetrics sessionId="s-1" />)

    const terms = screen.getAllByRole('term')
    expect(terms.map(t => t.textContent)).toEqual(['Active', 'Peak', 'Throughput'])
    expect(terms[0]!.nextElementSibling).toHaveTextContent(/^0$/)
    expect(terms[1]!.nextElementSibling).toHaveTextContent(/^6$/)
    // The tile's row has a gap, so the value never touches its label ("Active0").
    expect(terms[0]!.parentElement!.className).toMatch(/\bgap-3\b/)
  })
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm exec vitest run src/components/SessionMetrics.test.tsx`
Expected: FAIL — `Unable to find an accessible element with the role "term"`.

- [ ] **Step 3: Implement** — replace `MetricCard`:

```tsx
/**
 * One metric tile: its label and value as a term and its definition. The gap
 * keeps them apart — the card shrinks to its content, so a `justify-between`
 * row alone left none ("Active0").
 */
function MetricCard({
  icon,
  label,
  value,
}: {
  icon: React.ReactNode
  label: string
  value: React.ReactNode
}) {
  return (
    <Card>
      <CardBody>
        <dl className="flex items-center justify-between gap-3">
          <dt className="flex items-center gap-2 text-sm text-default-600">
            {icon}
            <span>{label}</span>
          </dt>
          <dd>{value}</dd>
        </dl>
      </CardBody>
    </Card>
  )
}
```

- [ ] **Step 4: Run the file's tests**

Run: `pnpm exec vitest run src/components/SessionMetrics.test.tsx`
Expected: PASS (all).

- [ ] **Step 5: Commit**

```bash
git add src/components/SessionMetrics.tsx src/components/SessionMetrics.test.tsx
git commit -m "fix(frontend): metric tiles keep label and value apart (\"Active 0\", not \"Active0\")"
```

### Task 2: Amend S-3 with the two Part 3 decisions

**Files:**
- Modify: `docs/superpowers/specs/2026-11-05-session-dock-design.md` (Status line, S-3 row, the T-2 row of "Where triage's frontend work goes")

- [ ] **Step 1: Status line** — after "…with the Evidence tab kept as specified in S-4" append: `; S-3 amended 2026-11-06 with two decisions made while building Part 3 (Export beside "⋯"; "last data" and start time in Part 7)`.

- [ ] **Step 2: S-3** — replace the sentence `Actions: Run again (demos, S-13), Replay, Pause view, and "⋯" (Share, Export, Checks, Reset session, Clear events). This revises T-7.` with:

```markdown
Actions: Run again (demos, S-13), Replay, Pause view, Export, and "⋯" (Share, Checks, Reset session, Clear events). This revises T-7. *Amended while building Part 3:* Export stays its own menu next to "⋯" — it is a menu itself (PNG, SVG, JSON, Record), and a menu inside a menu is awkward. T-2's "last data" and start time are not in the header until Part 7: they need the health API's wall-clock observation time (`asOf`), and the browser's clock must not pass for the app's.
```

- [ ] **Step 3: Ownership table** — the row `| Header with two status pills, last data, start time, rung badge (T-2) | Part 3 (view pill), Part 7 (connection pill) |` becomes `| Header with two status pills, last data, start time, rung badge (T-2) | Part 3 (view pill, rung badge), Part 7 (connection pill, last data, start time) |`.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-11-05-session-dock-design.md
git commit -m "docs: amend S-3 — Export beside the menu; last data and start time wait for Part 7"
```

### Task 3: `projectSync`, the lock and permit reducer, with permit counts and unknown holders

**Files:**
- Create: `frontend/src/lib/projections/project-sync.ts`
- Create: `frontend/src/lib/projections/project-sync.test.ts`
- Modify: `frontend/src/hooks/use-sync-events.ts` (whole file)
- Modify: `frontend/src/hooks/use-sync-events.test.ts:255-258` (holders gain `permits`), add one test

- [ ] **Step 1: Write the failing tests** — `project-sync.test.ts`:

```ts
/** Unit tests for projectSync — what each mutex and semaphore looks like after a list of events (S-6). */
import { describe, it, expect } from 'vitest'
import type { VizEvent } from '@/types/api'
import { projectSync } from './project-sync'

let seq = 0
function event(kind: string, fields: Record<string, unknown>): VizEvent {
  seq += 1
  return { sessionId: 's-1', seq, tsNanos: seq * 1_000, kind, ...fields } as unknown as VizEvent
}

const created = event('SemaphoreCreated', { semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', totalPermits: 3 })
function stateChanged(fields: Record<string, unknown>) {
  return event('SemaphoreStateChanged', {
    semaphoreId: 'sem-1',
    semaphoreLabel: 'payment-gateway',
    availablePermits: 0,
    totalPermits: 3,
    activeHolders: ['c-1', 'c-2'],
    activeHolderLabels: ['order-1', 'order-2'],
    waitingCoroutines: [],
    waitingLabels: [],
    ...fields,
  })
}

describe('projectSync', () => {
  it('has nothing for no events', () => {
    expect(projectSync(undefined)).toMatchObject({ mutexes: [], semaphores: [], hasDeadlock: false })
    expect(projectSync([])).toMatchObject({ mutexes: [], semaphores: [] })
  })

  it('counts the permits each holder holds', () => {
    const { semaphores } = projectSync([created, stateChanged({ activeHolderPermits: [2, 1] })])
    expect(semaphores[0]!.activeHolders).toEqual([
      { id: 'c-1', label: 'order-1', permits: 2 },
      { id: 'c-2', label: 'order-2', permits: 1 },
    ])
  })

  it('reads one permit per holder from an older producer that sends no counts', () => {
    const { semaphores } = projectSync([created, stateChanged({})])
    expect(semaphores[0]!.activeHolders.map(h => h.permits)).toEqual([1, 1])
  })

  it('keeps the permits whose holder is unknown (S-7), zero by default', () => {
    expect(projectSync([created]).semaphores[0]!.unknownHolderPermits).toBe(0)
    const { semaphores } = projectSync([created, stateChanged({ activeHolders: ['c-1'], activeHolderLabels: ['order-1'], unknownHolderPermits: 1 })])
    expect(semaphores[0]!.unknownHolderPermits).toBe(1)
  })

  it('tracks the lock holder and the latest queue of a mutex', () => {
    const { mutexes } = projectSync([
      event('MutexCreated', { mutexId: 'm-1', mutexLabel: 'inventory-lock', ownerCoroutineId: null }),
      event('MutexLockAcquired', { mutexId: 'm-1', mutexLabel: 'inventory-lock', acquirerId: 'c-1', acquirerLabel: 'order-1', waitDurationNanos: 0 }),
      event('MutexQueueChanged', { mutexId: 'm-1', mutexLabel: 'inventory-lock', waitingCoroutineIds: ['c-2', 'c-3'], waitingLabels: ['order-2', 'order-3'] }),
    ])
    expect(mutexes[0]).toMatchObject({ isLocked: true, currentHolder: 'c-1', waitQueue: [{ id: 'c-2', label: 'order-2' }, { id: 'c-3', label: 'order-3' }] })
  })
})
```

And in `use-sync-events.test.ts`, change the expectation at lines 255-258 to:

```ts
    expect(semaphore.activeHolders).toEqual([
      { id: 'c1', label: 'Worker-1', permits: 1 },
      { id: 'c2', label: 'Worker-2', permits: 1 },
    ])
```

and add, inside `describe('useSyncEvents', …)`:

```ts
  it('reads the events it is given, not the stored history, when given some', () => {
    mockedUseSessionEvents.mockReturnValue({ data: [] } as unknown as ReturnType<typeof useSessionEvents>)
    const live = [makeEvent('MutexCreated', 1, { mutexId: 'm1', mutexLabel: 'L', ownerCoroutineId: null })]

    const { result } = renderHook(() => useSyncEvents('s1', live), { wrapper: createWrapper() })

    expect(result.current.mutexes.map(m => m.mutexId)).toEqual(['m1'])
    // Still subscribed to the history, so the page's request profile is unchanged (#124).
    expect(mockedUseSessionEvents).toHaveBeenCalledWith('s1')
  })
```

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm exec vitest run src/lib/projections/project-sync.test.ts src/hooks/use-sync-events.test.ts`
Expected: FAIL — `Failed to resolve import "./project-sync"`; the hook tests fail on `permits` and on the `live` list being ignored.

- [ ] **Step 3: Implement** — `project-sync.ts` is today's reducer from `use-sync-events.ts`, moved verbatim, with these changes: the module header; `Holder` / `SemaphoreHolder` types; `SemaphoreState` gains `activeHolders: SemaphoreHolder[]` and `unknownHolderPermits`; the `SemaphoreCreated`, `SemaphoreStateChanged` and `ensureSemaphore` branches fill them; the function takes `readonly VizEvent[] | undefined`.

```ts
/**
 * Pure reducer: VizEvent[] -> what each mutex and semaphore looks like after these events (spec S-6).
 *
 * Moved out of useSyncEvents so the Evidence section and the tree's second line read one derivation, from
 * whichever events the page shows: the live stream's, the stored history's, or the replay cursor's.
 * Queues are the latest observed queue. Permits are counted per holder, never numbered; permits that came
 * back from a coroutine that held none have no known holder (S-7) and are counted apart.
 */
import { SYNC_EVENT_KINDS } from '@/types/api'
import type {
  VizEvent,
  MutexCreated,
  MutexLockRequested,
  MutexLockAcquired,
  MutexUnlocked,
  MutexTryLockFailed,
  MutexQueueChanged,
  SemaphoreCreated,
  SemaphoreAcquireRequested,
  SemaphorePermitAcquired,
  SemaphorePermitReleased,
  SemaphoreTryAcquireFailed,
  SemaphoreStateChanged,
  DeadlockDetected,
  PotentialDeadlockWarning,
  SyncEvent,
} from '@/types/api'

/** A coroutine named in a lock or permit event. */
export interface Holder {
  id: string
  label: string | null
}

/** A semaphore holder and how many permits it holds. */
export interface SemaphoreHolder extends Holder {
  permits: number
}

/** Tracked state for a single mutex. */
export interface MutexState {
  mutexId: string
  label: string | null
  isLocked: boolean
  currentHolder: string | null
  currentHolderLabel: string | null
  waitQueue: Holder[]
  contentionCount: number
  lockAcquisitions: number
}

/** Tracked state for a single semaphore. */
export interface SemaphoreState {
  semaphoreId: string
  label: string | null
  totalPermits: number
  availablePermits: number
  activeHolders: SemaphoreHolder[]
  /** Permits in use whose holder is unknown: released by a coroutine that held none (S-7). */
  unknownHolderPermits: number
  waitQueue: Holder[]
}

export interface SyncProjection {
  syncEvents: SyncEvent[]
  mutexes: MutexState[]
  semaphores: SemaphoreState[]
  deadlocks: DeadlockDetected[]
  warnings: PotentialDeadlockWarning[]
  hasDeadlock: boolean
}

export function projectSync(events: readonly VizEvent[] | undefined): SyncProjection {
  // … the body of today's useMemo callback in use-sync-events.ts, unchanged except:
  //   case 'SemaphoreCreated': the new state also gets `unknownHolderPermits: 0`
  //   case 'SemaphoreStateChanged':
  //     state.activeHolders = e.activeHolders.map((id, i) => ({
  //       id,
  //       label: e.activeHolderLabels[i] ?? null,
  //       // An older producer sends no counts: each listed holder held one.
  //       permits: e.activeHolderPermits?.[i] ?? 1,
  //     }))
  //     state.unknownHolderPermits = e.unknownHolderPermits ?? 0
}

// ensureMutex: unchanged. ensureSemaphore: its new state also gets `unknownHolderPermits: 0`.
```

The engineer moves the body for real (copy the `useMemo` callback body into `projectSync`, with `events` in place of the hook's `events`) — it is ~150 lines and is not re-typed here to keep the diff a move.

`use-sync-events.ts` becomes:

```ts
import { useMemo } from 'react'
import { useSessionEvents } from '@/hooks/use-sessions'
import { projectSync, type SyncProjection } from '@/lib/projections/project-sync'
import type { VizEvent } from '@/types/api'

export type { Holder, MutexState, SemaphoreHolder, SemaphoreState } from '@/lib/projections/project-sync'

export type UseSyncEventsResult = SyncProjection

/**
 * Mutex and semaphore state, wait queues and deadlock warnings.
 *
 * Reads `events` when the caller passes them — the workspace passes the events
 * the page shows, so the facts follow the live stream and the replay cursor —
 * else the stored history, which live updates never invalidate. It subscribes
 * to that history either way, so mounting it changes no request (#124).
 */
export function useSyncEvents(sessionId: string, events?: readonly VizEvent[]): UseSyncEventsResult {
  const { data: stored } = useSessionEvents(sessionId)
  const source = events ?? stored
  return useMemo(() => projectSync(source), [source])
}
```

Also change `projectWaits(events: VizEvent[])` to `projectWaits(events: readonly VizEvent[])` in `src/lib/projections/project-waits.ts` (no behaviour change; Task 5 calls it with a readonly list).

- [ ] **Step 4: Run the tests**

Run: `pnpm exec vitest run src/lib/projections src/hooks/use-sync-events.test.ts src/components/sync`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/lib/projections/project-sync.ts src/lib/projections/project-sync.test.ts src/lib/projections/project-waits.ts src/hooks/use-sync-events.ts src/hooks/use-sync-events.test.ts
git commit -m "refactor(frontend): lock and permit state as a pure projection, with permits per holder and unknown holders"
```

### Task 4: The other evidence hooks read the events they are given

**Files:**
- Modify: `frontend/src/hooks/use-channel-events.ts:47-48`, `use-flow-events.ts:64-65`, `use-job-events.ts:56-57`
- Test: `frontend/src/hooks/use-channel-events.test.ts`, `use-flow-events.test.ts`, and create `frontend/src/hooks/use-job-events.test.ts`

- [ ] **Step 1: Write the failing tests.** Each existing test file mocks `useSessionEvents` the same way `use-sync-events.test.ts` does. Add to `use-channel-events.test.ts`:

```ts
  it('reads the events it is given, not the stored history, when given some', () => {
    mockedUseSessionEvents.mockReturnValue({ data: [] } as unknown as ReturnType<typeof useSessionEvents>)
    const live = [makeEvent('ChannelCreated', 1, { channelId: 'ch-1', name: 'orders', capacity: 0, channelType: 'RENDEZVOUS' })]
    const { result } = renderHook(() => useChannelEvents('s1', live), { wrapper: createWrapper() })
    expect(result.current.channels.map(c => c.channelId)).toEqual(['ch-1'])
  })
```

to `use-flow-events.test.ts` (same shape; use the file's own event helper and a `FlowCreated` with `flowId: 'f-1'`, then assert `result.current.flows.map(f => f.flowId)` equals `['f-1']`), and create `use-job-events.test.ts`:

```ts
import { describe, it, expect, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import type { VizEvent } from '@/types/api'

vi.mock('@/hooks/use-sessions', () => ({ useSessionEvents: vi.fn(() => ({ data: [] })) }))

import { useJobEvents } from './use-job-events'

describe('useJobEvents', () => {
  it('reads the events it is given, not the stored history, when given some', () => {
    const live = [
      {
        sessionId: 's1', seq: 1, tsNanos: 1, kind: 'JobStateChanged', coroutineId: 'c-1', jobId: 'j-1',
        parentCoroutineId: null, scopeId: 'sc', label: 'a', isActive: true, isCompleted: false, isCancelled: false, childrenCount: 0,
      },
    ] as unknown as VizEvent[]
    const { result } = renderHook(() => useJobEvents('s1', live))
    expect(result.current.jobs).toHaveLength(1)
  })
})
```

(Before writing the job test, read `use-job-events.ts` and give the `JobStateChanged` fixture exactly the fields its reducer reads; adjust the expectation if `jobs` is keyed differently.)

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm exec vitest run src/hooks/use-channel-events.test.ts src/hooks/use-flow-events.test.ts src/hooks/use-job-events.test.ts`
Expected: FAIL — the second argument is ignored (empty results), and TypeScript-in-vitest does not stop the run.

- [ ] **Step 3: Implement** — in each hook, the same three-line change (shown for channels):

```ts
/**
 * … (existing doc) Reads `events` when given — the events the page shows —
 * else the stored history; it subscribes to that history either way (#124).
 */
export function useChannelEvents(sessionId: string, given?: readonly VizEvent[]): UseChannelEventsResult {
  const { data: stored } = useSessionEvents(sessionId)
  const events = given ?? stored
```

Import `VizEvent` as a type where the file does not yet. The rest of each hook reads `events` already and is unchanged.

- [ ] **Step 4: Run the tests**

Run: `pnpm exec vitest run src/hooks`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/hooks/use-channel-events.ts src/hooks/use-flow-events.ts src/hooks/use-job-events.ts src/hooks/use-channel-events.test.ts src/hooks/use-flow-events.test.ts src/hooks/use-job-events.test.ts
git commit -m "feat(frontend): channel, flow and job evidence read the events they are given"
```

### Task 5: `selection.ts` — what an event is about

**Files:**
- Create: `frontend/src/lib/selection.ts`
- Create: `frontend/src/lib/selection.test.ts`

- [ ] **Step 1: Write the failing tests**

```ts
/** Unit tests for the page's one selection (S-2): what each event is about, and what a selection's events are. */
import { describe, it, expect } from 'vitest'
import type { VizEvent } from '@/types/api'
import { eventConcerns, eventSubject, eventTitle, resourceNames } from './selection'

function ev(kind: string, fields: Record<string, unknown> = {}): VizEvent {
  return { sessionId: 's', seq: 1, tsNanos: 1, kind, ...fields } as unknown as VizEvent
}

const lock = { mutexId: 'm-1', mutexLabel: 'inventory-lock' }

describe('eventSubject', () => {
  it('a coroutine event is about its coroutine', () => {
    expect(eventSubject(ev('coroutine.suspended', { coroutineId: 'c-1', resourceId: 'm-1' }))).toEqual({ kind: 'coroutine', id: 'c-1' })
  })

  it.each([
    ['MutexLockRequested', { requesterId: 'c-2' }],
    ['MutexLockAcquired', { acquirerId: 'c-2' }],
    ['MutexUnlocked', { releaserId: 'c-2' }],
    ['MutexTryLockFailed', { requesterId: 'c-2' }],
  ])('%s is about the requester, acquirer or releaser', (kind, who) => {
    expect(eventSubject(ev(kind, { ...lock, ...who }))).toEqual({ kind: 'coroutine', id: 'c-2' })
  })

  it.each([
    ['SemaphoreAcquireRequested', { requesterId: 'c-3' }],
    ['SemaphorePermitAcquired', { acquirerId: 'c-3' }],
    ['SemaphorePermitReleased', { releaserId: 'c-3', holderUnknown: true }],
  ])('%s is about its coroutine', (kind, who) => {
    expect(eventSubject(ev(kind, { semaphoreId: 'sem-1', ...who }))).toEqual({ kind: 'coroutine', id: 'c-3' })
  })

  it('an event about a resource only selects the resource', () => {
    expect(eventSubject(ev('MutexCreated', { ...lock, ownerCoroutineId: null }))).toEqual({ kind: 'resource', resource: 'mutex', id: 'm-1' })
    expect(eventSubject(ev('MutexQueueChanged', { ...lock, waitingCoroutineIds: [] }))).toEqual({ kind: 'resource', resource: 'mutex', id: 'm-1' })
    expect(eventSubject(ev('SemaphoreStateChanged', { semaphoreId: 'sem-1' }))).toEqual({ kind: 'resource', resource: 'semaphore', id: 'sem-1' })
    expect(eventSubject(ev('ChannelCreated', { channelId: 'ch-1', name: 'orders' }))).toEqual({ kind: 'resource', resource: 'channel', id: 'ch-1' })
  })

  it('a release made outside any coroutine is about the lock, not a coroutine named "unknown"', () => {
    expect(eventSubject(ev('MutexUnlocked', { ...lock, releaserId: 'unknown' }))).toEqual({ kind: 'resource', resource: 'mutex', id: 'm-1' })
    expect(eventSubject(ev('SemaphorePermitReleased', { semaphoreId: 'sem-1', releaserId: 'unknown' }))).toEqual({ kind: 'resource', resource: 'semaphore', id: 'sem-1' })
    expect(eventSubject(ev('MutexLockRequested', { ...lock, requesterId: 'unknown-12345' }))).toEqual({ kind: 'resource', resource: 'mutex', id: 'm-1' })
  })

  it('a deadlock or a coroutine-less flow event selects nothing', () => {
    expect(eventSubject(ev('DeadlockDetected', { involvedCoroutines: ['c-1', 'c-2'] }))).toBeNull()
    expect(eventSubject(ev('FlowOperatorApplied', { flowId: 'f-1', coroutineId: null }))).toBeNull()
  })
})

describe('eventConcerns', () => {
  it('a coroutine\'s events include the lock and permit events about it', () => {
    const sel = { kind: 'coroutine', id: 'c-2' } as const
    expect(eventConcerns(ev('coroutine.started', { coroutineId: 'c-2' }), sel)).toBe(true)
    expect(eventConcerns(ev('MutexLockAcquired', { ...lock, acquirerId: 'c-2' }), sel)).toBe(true)
    expect(eventConcerns(ev('MutexLockAcquired', { ...lock, acquirerId: 'c-9' }), sel)).toBe(false)
    expect(eventConcerns(ev('MutexCreated', lock), sel)).toBe(false)
  })

  it('a resource\'s events are those naming it, and the suspensions waiting for it', () => {
    const sel = { kind: 'resource', resource: 'mutex', id: 'm-1' } as const
    expect(eventConcerns(ev('MutexLockAcquired', { ...lock, acquirerId: 'c-2' }), sel)).toBe(true)
    expect(eventConcerns(ev('coroutine.suspended', { coroutineId: 'c-2', waitKind: 'MUTEX', resourceId: 'm-1' }), sel)).toBe(true)
    expect(eventConcerns(ev('MutexCreated', { mutexId: 'm-2' }), sel)).toBe(false)
    expect(eventConcerns(ev('coroutine.started', { coroutineId: 'c-2' }), sel)).toBe(false)
  })
})

describe('resourceNames', () => {
  it('names locks, semaphores and channels by their labels', () => {
    const names = resourceNames([
      ev('MutexCreated', lock),
      ev('SemaphoreCreated', { semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway' }),
      ev('ChannelCreated', { channelId: 'ch-1', name: 'orders' }),
      ev('MutexCreated', { mutexId: 'm-2', mutexLabel: null }),
    ])
    expect(Object.fromEntries(names)).toEqual({ 'm-1': 'inventory-lock', 'sem-1': 'payment-gateway', 'ch-1': 'orders' })
  })
})

describe('eventTitle', () => {
  it('names a coroutine event by its coroutine', () => {
    expect(eventTitle(ev('coroutine.started', { coroutineId: 'c-1', label: 'order-1' }))).toBe('order-1')
  })

  it('names a lock or permit event by who and what', () => {
    expect(eventTitle(ev('MutexLockAcquired', { ...lock, acquirerId: 'c-2', acquirerLabel: 'order-2' }))).toBe('order-2 · inventory-lock')
    expect(eventTitle(ev('MutexCreated', lock))).toBe('inventory-lock')
    expect(eventTitle(ev('SemaphorePermitReleased', { semaphoreId: 'sem-1', semaphoreLabel: null, releaserId: 'c-3', releaserLabel: null }))).toBe('c-3 · sem-1')
  })
})
```

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm exec vitest run src/lib/selection.test.ts`
Expected: FAIL — `Failed to resolve import "./selection"`.

- [ ] **Step 3: Implement** — `src/lib/selection.ts`:

```ts
/**
 * The page's one selection (spec S-2): a coroutine, or a resource — a lock, a semaphore or a channel.
 * Groups (S-12) join it with Part 6.
 *
 * What an event is about: its coroutine; for lock and permit events, the requester, acquirer or releaser;
 * otherwise the resource it names. A wrapper used outside any coroutine records the coroutine as
 * "unknown" (or "unknown-<nanos>"): that names nobody, so such an event is about its resource.
 */
import type { VizEvent } from '@/types/api'

export type ResourceKind = 'mutex' | 'semaphore' | 'channel'

export type Selection =
  | { kind: 'coroutine'; id: string }
  | { kind: 'resource'; resource: ResourceKind; id: string }

/** The fields of the events this module reads; each kind carries some of them. */
interface Fields {
  kind: string
  label?: string | null
  coroutineId?: string | null
  requesterId?: string
  requesterLabel?: string | null
  acquirerId?: string
  acquirerLabel?: string | null
  releaserId?: string
  releaserLabel?: string | null
  mutexId?: string
  mutexLabel?: string | null
  semaphoreId?: string
  semaphoreLabel?: string | null
  channelId?: string
  name?: string | null
  resourceId?: string | null
}

const fieldsOf = (event: VizEvent) => event as unknown as Fields

/** A coroutine id that names a coroutine; the wrappers write "unknown…" when there was none. */
function namesCoroutine(id: string | null | undefined): id is string {
  return !!id && id !== 'unknown' && !id.startsWith('unknown-')
}

const RESOURCE_ID: Record<ResourceKind, 'mutexId' | 'semaphoreId' | 'channelId'> = {
  mutex: 'mutexId',
  semaphore: 'semaphoreId',
  channel: 'channelId',
}

/** What the event is about (S-2), or null when it is about nothing one can select. */
export function eventSubject(event: VizEvent): Selection | null {
  const f = fieldsOf(event)
  const who = f.coroutineId ?? f.requesterId ?? f.acquirerId ?? f.releaserId
  if (namesCoroutine(who)) return { kind: 'coroutine', id: who }
  if (f.mutexId) return { kind: 'resource', resource: 'mutex', id: f.mutexId }
  if (f.semaphoreId) return { kind: 'resource', resource: 'semaphore', id: f.semaphoreId }
  if (f.channelId) return { kind: 'resource', resource: 'channel', id: f.channelId }
  return null
}

/** Whether the event belongs in the Events tab while this is selected. */
export function eventConcerns(event: VizEvent, selection: Selection): boolean {
  if (selection.kind === 'coroutine') {
    const subject = eventSubject(event)
    return subject?.kind === 'coroutine' && subject.id === selection.id
  }
  const f = fieldsOf(event)
  return f[RESOURCE_ID[selection.resource]] === selection.id || f.resourceId === selection.id
}

/** Resource id -> its label, for every lock, semaphore and channel the events name with one. */
export function resourceNames(events: readonly VizEvent[]): Map<string, string> {
  const names = new Map<string, string>()
  for (const event of events) {
    const f = fieldsOf(event)
    if (f.mutexId && f.mutexLabel) names.set(f.mutexId, f.mutexLabel)
    else if (f.semaphoreId && f.semaphoreLabel) names.set(f.semaphoreId, f.semaphoreLabel)
    else if (f.kind === 'ChannelCreated' && f.channelId && f.name) names.set(f.channelId, f.name)
  }
  return names
}

/** A row's title in the event list: its coroutine, and for lock and permit events the resource too. */
export function eventTitle(event: VizEvent): string {
  const f = fieldsOf(event)
  const who =
    f.label ?? f.requesterLabel ?? f.acquirerLabel ?? f.releaserLabel ?? f.coroutineId ?? f.requesterId ?? f.acquirerId ?? f.releaserId
  const what = f.mutexLabel ?? f.mutexId ?? f.semaphoreLabel ?? f.semaphoreId
  return [who, what].filter(Boolean).join(' · ')
}
```

- [ ] **Step 4: Run the tests**

Run: `pnpm exec vitest run src/lib/selection.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/lib/selection.ts src/lib/selection.test.ts
git commit -m "feat(frontend): one selection — what each event is about, and a selection's events"
```

### Task 6: `activity.ts` — the second line

**Files:**
- Create: `frontend/src/lib/activity.ts`
- Create: `frontend/src/lib/activity.test.ts`

- [ ] **Step 1: Write the failing tests**

```ts
/** Unit tests for the node's second line (S-6): what a coroutine waits for or holds. */
import { describe, it, expect } from 'vitest'
import type { VizEvent } from '@/types/api'
import { ordinal, projectActivity } from './activity'

let seq = 0
function ev(kind: string, fields: Record<string, unknown> = {}): VizEvent {
  seq += 1
  return { sessionId: 's', seq, tsNanos: seq, kind, ...fields } as unknown as VizEvent
}
const lock = { mutexId: 'm-1', mutexLabel: 'inventory-lock' }
const sem = { semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway' }
const waitFor = (id: string, waitKind: string, resourceId: string | null) =>
  ev('coroutine.suspended', { coroutineId: id, waitKind, resourceId, reason: waitKind.toLowerCase() })
const queue = (ids: string[]) => ev('MutexQueueChanged', { ...lock, waitingCoroutineIds: ids, waitingLabels: ids })
const permits = (holders: string[], counts: number[], waiting: string[] = [], unknown = 0) =>
  ev('SemaphoreStateChanged', {
    ...sem, availablePermits: 0, totalPermits: 3, activeHolders: holders, activeHolderLabels: holders,
    activeHolderPermits: counts, waitingCoroutines: waiting, waitingLabels: waiting, unknownHolderPermits: unknown,
  })

describe('ordinal', () => {
  it.each([[1, '1st'], [2, '2nd'], [3, '3rd'], [4, '4th'], [11, '11th'], [12, '12th'], [13, '13th'], [21, '21st'], [102, '102nd']])(
    '%i is %s', (n, text) => expect(ordinal(n)).toBe(text),
  )
})

describe('projectActivity', () => {
  it('a lock holder holds it, neutrally', () => {
    const lines = projectActivity([ev('MutexCreated', lock), ev('MutexLockAcquired', { ...lock, acquirerId: 'c-1' })])
    expect(lines.get('c-1')).toEqual({ text: 'holds inventory-lock', waiting: false })
  })

  it('a waiter waits for the lock at its place in the latest queue', () => {
    const lines = projectActivity([
      ev('MutexCreated', lock), ev('MutexLockAcquired', { ...lock, acquirerId: 'c-1' }),
      queue(['c-2', 'c-3']), waitFor('c-2', 'MUTEX', 'm-1'), waitFor('c-3', 'MUTEX', 'm-1'),
    ])
    expect(lines.get('c-2')).toEqual({ text: 'waits for inventory-lock · 1st', waiting: true })
    expect(lines.get('c-3')?.text).toBe('waits for inventory-lock · 2nd')
  })

  it('a resume ends the wait, and the next holder holds the lock', () => {
    const lines = projectActivity([
      ev('MutexCreated', lock), ev('MutexLockAcquired', { ...lock, acquirerId: 'c-1' }), queue(['c-2']),
      waitFor('c-2', 'MUTEX', 'm-1'), ev('MutexUnlocked', { ...lock, releaserId: 'c-1' }),
      ev('coroutine.resumed', { coroutineId: 'c-2' }), ev('MutexLockAcquired', { ...lock, acquirerId: 'c-2' }), queue([]),
    ])
    expect(lines.has('c-1')).toBe(false)
    expect(lines.get('c-2')).toEqual({ text: 'holds inventory-lock', waiting: false })
  })

  it('reads the queue alone from an older producer that records no wait kind', () => {
    const lines = projectActivity([ev('MutexCreated', lock), queue(['c-2'])])
    expect(lines.get('c-2')?.text).toBe('waits for inventory-lock · 1st')
  })

  it('names a lock by its id when it has no label', () => {
    const lines = projectActivity([ev('MutexLockAcquired', { mutexId: 'mutex-7', mutexLabel: null, acquirerId: 'c-1' })])
    expect(lines.get('c-1')?.text).toBe('holds mutex-7')
  })

  it('permits are counted, never numbered', () => {
    const lines = projectActivity([ev('SemaphoreCreated', { ...sem, totalPermits: 3 }), permits(['c-1', 'c-2'], [1, 2])])
    expect(lines.get('c-1')?.text).toBe('holds a payment-gateway permit')
    expect(lines.get('c-2')?.text).toBe('holds 2 payment-gateway permits')
  })

  it('a permit waiter waits for a permit at its place', () => {
    const lines = projectActivity([
      ev('SemaphoreCreated', { ...sem, totalPermits: 1 }), permits(['c-1'], [1], ['c-4', 'c-5']),
      waitFor('c-4', 'SEMAPHORE', 'sem-1'), waitFor('c-5', 'SEMAPHORE', 'sem-1'),
    ])
    expect(lines.get('c-5')).toEqual({ text: 'waits for a payment-gateway permit · 2nd', waiting: true })
  })

  it('says "an" before a vowel', () => {
    const lines = projectActivity([
      ev('SemaphoreStateChanged', {
        semaphoreId: 'sem-2', semaphoreLabel: 'inventory', availablePermits: 0, totalPermits: 1,
        activeHolders: ['c-1'], activeHolderLabels: ['c-1'], waitingCoroutines: [], waitingLabels: [],
      }),
    ])
    expect(lines.get('c-1')?.text).toBe('holds an inventory permit')
  })

  it('channel waits say which side and which channel', () => {
    const lines = projectActivity([
      ev('ChannelCreated', { channelId: 'ch-1', name: 'orders', capacity: 0, channelType: 'RENDEZVOUS' }),
      waitFor('c-1', 'CHANNEL_SEND', 'ch-1'), waitFor('c-2', 'CHANNEL_RECEIVE', 'ch-1'),
    ])
    expect(lines.get('c-1')?.text).toBe('waits to send to orders')
    expect(lines.get('c-2')?.text).toBe('waits to receive from orders')
  })

  it('a wait whose resource is not named says what kind it is', () => {
    const lines = projectActivity([
      waitFor('c-1', 'MUTEX', null), waitFor('c-2', 'SEMAPHORE', null),
      waitFor('c-3', 'CHANNEL_SEND', null), waitFor('c-4', 'CHANNEL_RECEIVE', null),
    ])
    expect([1, 2, 3, 4].map(i => lines.get(`c-${i}`)?.text)).toEqual([
      'waits for a lock', 'waits for a permit', 'waits to send', 'waits to receive',
    ])
  })

  it('a coroutine holding one lock while waiting for another says the wait first', () => {
    const lines = projectActivity([
      ev('MutexLockAcquired', { mutexId: 'm-a', mutexLabel: 'lock-a', acquirerId: 'c-1' }),
      ev('MutexLockAcquired', { mutexId: 'm-b', mutexLabel: 'lock-b', acquirerId: 'c-2' }),
      ev('MutexQueueChanged', { mutexId: 'm-b', mutexLabel: 'lock-b', waitingCoroutineIds: ['c-1'], waitingLabels: ['c-1'] }),
      waitFor('c-1', 'MUTEX', 'm-b'),
    ])
    expect(lines.get('c-1')).toEqual({ text: 'waits for lock-b · 1st, holds lock-a', waiting: true })
  })

  it('a delay is no wait', () => {
    expect(projectActivity([ev('coroutine.suspended', { coroutineId: 'c-1', reason: 'delay' })]).has('c-1')).toBe(false)
  })
})
```

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm exec vitest run src/lib/activity.test.ts`
Expected: FAIL — `Failed to resolve import "./activity"`.

- [ ] **Step 3: Implement** — `src/lib/activity.ts`:

```ts
/**
 * A node's second line (spec S-6): what a coroutine waits for or holds, as the lock, permit and channel
 * events show it — "waits for inventory-lock · 1st", "holds a payment-gateway permit".
 *
 * A wait is the suspension a wrapper recorded with its kind and resource (S-7, `projectWaits`); an older
 * producer records none, so the latest observed queue also says who waits. The place in the queue is the
 * latest observed one. Permits are counted, never numbered. A wait is drawn in the Suspended colour; a hold
 * alone is neutral, because a held lock is normal work.
 */
import type { VizEvent, WaitKind } from '@/types/api'
import { projectSync, type Holder } from './projections/project-sync'
import { projectWaits } from './projections/project-waits'
import { resourceNames } from './selection'

export interface ActivityLine {
  /** The facts, the wait first, joined with ", ". */
  text: string
  /** It waits for something (drawn as a wait); false when it only holds. */
  waiting: boolean
}

/** 1st, 2nd, 3rd, 4th … 11th, 12th, 13th … 21st. */
export function ordinal(n: number): string {
  const lastTwo = n % 100
  if (lastTwo >= 11 && lastTwo <= 13) return `${n}th`
  switch (n % 10) {
    case 1:
      return `${n}st`
    case 2:
      return `${n}nd`
    case 3:
      return `${n}rd`
    default:
      return `${n}th`
  }
}

/** "a payment-gateway permit", "an inventory permit". */
function aPermit(name: string): string {
  return `${/^[aeiou]/i.test(name) ? 'an' : 'a'} ${name} permit`
}

/** " · 2nd" when the coroutine is in the queue, else nothing. */
function place(coroutineId: string, queue: readonly Holder[] | undefined): string {
  const index = queue?.findIndex(w => w.id === coroutineId) ?? -1
  return index >= 0 ? ` · ${ordinal(index + 1)}` : ''
}

/** Coroutine id -> its second line, for every coroutine that waits for or holds something. */
export function projectActivity(events: readonly VizEvent[]): Map<string, ActivityLine> {
  const sync = projectSync(events)
  const names = resourceNames(events)
  const nameOf = (id: string) => names.get(id) ?? id
  const lockQueues = new Map(sync.mutexes.map(m => [m.mutexId, m.waitQueue]))
  const permitQueues = new Map(sync.semaphores.map(s => [s.semaphoreId, s.waitQueue]))

  const describeWait = (coroutineId: string, kind: WaitKind, resourceId: string | null): string => {
    const name = resourceId ? nameOf(resourceId) : null
    switch (kind) {
      case 'MUTEX':
        return `waits for ${name ?? 'a lock'}${resourceId ? place(coroutineId, lockQueues.get(resourceId)) : ''}`
      case 'SEMAPHORE':
        return `waits for ${name ? aPermit(name) : 'a permit'}${resourceId ? place(coroutineId, permitQueues.get(resourceId)) : ''}`
      case 'CHANNEL_SEND':
        return name ? `waits to send to ${name}` : 'waits to send'
      case 'CHANNEL_RECEIVE':
        return name ? `waits to receive from ${name}` : 'waits to receive'
      default:
        return 'waits'
    }
  }

  const waits = new Map<string, string>()
  for (const [coroutineId, wait] of projectWaits(events)) {
    waits.set(coroutineId, describeWait(coroutineId, wait.waitKind, wait.resourceId))
  }
  // An older producer records no wait kind: the latest queue still says who waits, and where.
  for (const m of sync.mutexes) {
    m.waitQueue.forEach((w, i) => {
      if (!waits.has(w.id)) waits.set(w.id, `waits for ${nameOf(m.mutexId)} · ${ordinal(i + 1)}`)
    })
  }
  for (const s of sync.semaphores) {
    s.waitQueue.forEach((w, i) => {
      if (!waits.has(w.id)) waits.set(w.id, `waits for ${aPermit(nameOf(s.semaphoreId))} · ${ordinal(i + 1)}`)
    })
  }

  const holds = new Map<string, string[]>()
  const hold = (coroutineId: string, text: string) => holds.set(coroutineId, [...(holds.get(coroutineId) ?? []), text])
  for (const m of sync.mutexes) {
    if (m.isLocked && m.currentHolder) hold(m.currentHolder, `holds ${nameOf(m.mutexId)}`)
  }
  for (const s of sync.semaphores) {
    const name = nameOf(s.semaphoreId)
    for (const h of s.activeHolders) {
      hold(h.id, h.permits === 1 ? `holds ${aPermit(name)}` : `holds ${h.permits} ${name} permits`)
    }
  }

  const lines = new Map<string, ActivityLine>()
  for (const coroutineId of new Set([...waits.keys(), ...holds.keys()])) {
    const wait = waits.get(coroutineId)
    lines.set(coroutineId, {
      text: [...(wait ? [wait] : []), ...(holds.get(coroutineId) ?? [])].join(', '),
      waiting: wait !== undefined,
    })
  }
  return lines
}
```

- [ ] **Step 4: Run the tests**

Run: `pnpm exec vitest run src/lib/activity.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/lib/activity.ts src/lib/activity.test.ts
git commit -m "feat(frontend): a coroutine's second line — what it waits for or holds"
```

### Task 7: The second line on graph cards, list rows and the main view

**Files:**
- Modify: `frontend/src/components/CoroutineTreeGraph.tsx` (props, `NODE_HEIGHT`, `NodeCard`)
- Modify: `frontend/src/components/CoroutineTree.tsx` (props threaded to `TreeNode`)
- Modify: `frontend/src/components/workspace/CoroutineCanvas.tsx` (prop passed to both views)
- Test: `CoroutineTreeGraph.test.tsx`, `CoroutineTree.test.tsx`, `workspace/CoroutineCanvas.test.tsx`

- [ ] **Step 1: Write the failing tests.** In `CoroutineTreeGraph.test.tsx` (use the file's existing coroutine fixture helper; shown here as `coroutine(...)`):

```tsx
  it('shows what a coroutine waits for on a second line, in the Suspended colour', () => {
    const activity = new Map([['c-1', { text: 'waits for inventory-lock · 1st', waiting: true }]])
    render(<CoroutineTreeGraph coroutines={[coroutine({ id: 'c-1', state: CoroutineState.SUSPENDED })]} activity={activity} />)
    const line = within(screen.getByTestId('graph-node-c-1')).getByTestId('activity-line')
    expect(line).toHaveTextContent('waits for inventory-lock · 1st')
    expect(line.className).toContain('text-warning')
  })

  it('shows a hold neutrally, and no line for a coroutine that holds or waits for nothing', () => {
    const activity = new Map([['c-1', { text: 'holds inventory-lock', waiting: false }]])
    render(
      <CoroutineTreeGraph
        coroutines={[coroutine({ id: 'c-1' }), coroutine({ id: 'c-2' })]}
        activity={activity}
      />,
    )
    const line = within(screen.getByTestId('graph-node-c-1')).getByTestId('activity-line')
    expect(line.className).toContain('text-default-600')
    expect(line.className).not.toContain('text-warning')
    expect(within(screen.getByTestId('graph-node-c-2')).queryByTestId('activity-line')).toBeNull()
  })
```

(import `within` from `@testing-library/react`). In `CoroutineTree.test.tsx`:

```tsx
  it('shows the second line under the id line', () => {
    const activity = new Map([['c-1', { text: 'holds a payment-gateway permit', waiting: false }]])
    render(<CoroutineTree coroutines={[makeCoroutine()]} activity={activity} />)
    expect(screen.getByTestId('activity-line')).toHaveTextContent('holds a payment-gateway permit')
  })
```

In `CoroutineCanvas.test.tsx`, make the two view mocks record the prop and add:

```tsx
vi.mock('../CoroutineTreeGraph', () => ({
  CoroutineTreeGraph: ({ activity }: { activity?: Map<string, unknown> }) => (
    <div data-testid="coroutine-tree-graph" data-activity={activity?.size ?? 0} />
  ),
}))
vi.mock('../CoroutineTree', () => ({
  CoroutineTree: ({ activity }: { activity?: Map<string, unknown> }) => (
    <div data-testid="coroutine-tree" data-activity={activity?.size ?? 0} />
  ),
}))
```

```tsx
  it('passes the second lines to the graph and the list', async () => {
    const activity = new Map([['c-1', { text: 'holds x', waiting: false }]])
    renderCanvas({ activity })
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-activity', '1')
  })
```

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm exec vitest run src/components/CoroutineTreeGraph.test.tsx src/components/CoroutineTree.test.tsx src/components/workspace/CoroutineCanvas.test.tsx`
Expected: FAIL — no `activity-line`; `data-activity` is `0`.

- [ ] **Step 3: Implement.**

`CoroutineTreeGraph.tsx`: import `type { ActivityLine } from '@/lib/activity'`; add to `CoroutineTreeGraphProps`:

```ts
  /** Each coroutine's second line: what it waits for or holds (S-6). */
  activity?: ReadonlyMap<string, ActivityLine>
```

destructure it, pass `activity={activity?.get(id)}` to `NodeCard`; `NodeCardProps` gains `activity?: ActivityLine`. Height:

```ts
/**
 * Card size and spacing the layout reserves; the card is drawn at exactly this size. The height
 * holds the tallest card (measured): border + padding 36, a two-line name, the state and the
 * second line (S-6) 80 + 12, four detail rows 92, the progress bar of a running coroutine 16 — 236 px.
 */
const NODE_WIDTH = 280
const NODE_HEIGHT = 240
```

In `NodeCard`, after the state line `<div className="text-xs text-default-500">…</div>`:

```tsx
            {/* What it waits for or holds (S-6): a wait in the Suspended colour, a hold neutral. */}
            {activity && (
              <div
                data-testid="activity-line"
                className={activity.waiting ? 'truncate text-xs text-warning' : 'truncate text-xs text-default-600'}
                title={activity.text}
              >
                {activity.text}
              </div>
            )}
```

`CoroutineTree.tsx`: same prop on `CoroutineTreeProps` (documented the same way), passed to every `TreeNode` (and on to children); in `TreeNode`, after `<div className="text-xs text-default-500">{node.id} • Job: {node.jobId}</div>`:

```tsx
                  {activity?.get(node.id) && (
                    <div
                      data-testid="activity-line"
                      className={
                        activity.get(node.id)!.waiting ? 'text-xs text-warning' : 'text-xs text-default-600'
                      }
                    >
                      {activity.get(node.id)!.text}
                    </div>
                  )}
```

(bind `const line = activity?.get(node.id)` at the top of `TreeNode` and use `line` instead of the repeated lookups.)

`CoroutineCanvas.tsx`: add `activity?: ReadonlyMap<string, ActivityLine>` to the props (doc: "Each coroutine's second line (S-6); shown in every mode, read from the same events as the tree."), destructure, pass `activity={activity}` to both `CoroutineTreeGraph` and `CoroutineTree`.

- [ ] **Step 4: Run the tests**

Run: `pnpm exec vitest run src/components/CoroutineTreeGraph.test.tsx src/components/CoroutineTree.test.tsx src/components/workspace/CoroutineCanvas.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/components/CoroutineTreeGraph.tsx src/components/CoroutineTree.tsx src/components/workspace/CoroutineCanvas.tsx src/components/CoroutineTreeGraph.test.tsx src/components/CoroutineTree.test.tsx src/components/workspace/CoroutineCanvas.test.tsx
git commit -m "feat(frontend): every node says what it waits for or holds, on a second line"
```

### Task 8: Locks and permits — live, selectable names, holders with counts, "holder unknown"

**Files:**
- Create: `frontend/src/components/sync/CoroutineName.tsx`
- Modify: `frontend/src/components/sync/SyncPanel.tsx`, `MutexStateIndicator.tsx`, `SemaphoreGauge.tsx`, `WaitQueueList.tsx`
- Test: `frontend/src/components/sync/SyncPanel.test.tsx`

- [ ] **Step 1: Write the failing tests** — append to `SyncPanel.test.tsx` (it already wraps in a `QueryClientProvider` and mocks `apiClient.getSessionEvents`; `mockedApiClient.getSessionEvents.mockResolvedValue([])` keeps the stored history empty):

```tsx
describe('SyncPanel — live facts and one selection (S-2, S-6)', () => {
  const lockEvents = [
    makeSyncEvent('MutexCreated', 1, { mutexId: 'm-1', mutexLabel: 'inventory-lock', ownerCoroutineId: null }),
    makeSyncEvent('MutexLockAcquired', 2, { mutexId: 'm-1', mutexLabel: 'inventory-lock', acquirerId: 'c-1', acquirerLabel: 'order-1', waitDurationNanos: 0 }),
    makeSyncEvent('MutexQueueChanged', 3, { mutexId: 'm-1', mutexLabel: 'inventory-lock', waitingCoroutineIds: ['c-2'], waitingLabels: ['order-2'] }),
  ]
  const permitEvents = [
    makeSyncEvent('SemaphoreCreated', 4, { semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', totalPermits: 3 }),
    makeSyncEvent('SemaphoreStateChanged', 5, {
      semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', availablePermits: 0, totalPermits: 3,
      activeHolders: ['c-3'], activeHolderLabels: ['order-3'], activeHolderPermits: [2], unknownHolderPermits: 1,
      waitingCoroutines: [], waitingLabels: [],
    }),
  ]

  beforeEach(() => {
    mockedApiClient.getSessionEvents.mockResolvedValue([])
  })

  it('reads the events it is given, so it follows the live stream', () => {
    render(<SyncPanel sessionId="session-1" events={lockEvents} />, { wrapper: createWrapper() })
    expect(screen.getByTestId('mutex-state')).toHaveTextContent('order-1')
  })

  it('lists a semaphore\'s holders with their permits, and permits whose holder is unknown', () => {
    render(<SyncPanel sessionId="session-1" events={permitEvents} />, { wrapper: createWrapper() })
    const held = screen.getByTestId('semaphore-holders')
    expect(held).toHaveTextContent('order-3 (2 permits)')
    expect(held).toHaveTextContent('1 permit, holder unknown')
  })

  it('selects a holder or a waiter by name', async () => {
    const onSelectCoroutine = vi.fn()
    render(<SyncPanel sessionId="session-1" events={lockEvents} onSelectCoroutine={onSelectCoroutine} />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: 'order-1' }))
    await userEvent.click(screen.getByRole('button', { name: 'order-2' }))
    expect(onSelectCoroutine.mock.calls).toEqual([['c-1'], ['c-2']])
  })

  it('marks the selected coroutine and rings the selected resource', () => {
    render(
      <SyncPanel sessionId="session-1" events={[...lockEvents, ...permitEvents]} selectedCoroutineId="c-1" selectedResourceId="sem-1" onSelectCoroutine={vi.fn()} />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByRole('button', { name: 'order-1' })).toHaveAttribute('aria-current', 'true')
    expect(screen.getByRole('button', { name: 'order-2' })).not.toHaveAttribute('aria-current')
    expect(screen.getByTestId('resource-sem-1')).toHaveAttribute('data-selected', 'true')
    expect(screen.getByTestId('resource-m-1')).toHaveAttribute('data-selected', 'false')
  })
})
```

(import `userEvent` from `@testing-library/user-event` if the file does not.)

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm exec vitest run src/components/sync/SyncPanel.test.tsx`
Expected: FAIL — the stored (empty) history is read; no `semaphore-holders`; no name buttons.

- [ ] **Step 3: Implement.**

`CoroutineName.tsx`:

```tsx
interface CoroutineNameProps {
  id: string
  label: string | null
  /** Selects this coroutine (S-2); without it the name is plain text. */
  onSelect?: (id: string) => void
  /** It is the page's selection. */
  selected?: boolean
}

/** A coroutine's name in the Locks and permits section: a button that selects it, marked when selected. */
export function CoroutineName({ id, label, onSelect, selected = false }: CoroutineNameProps) {
  const name = label || id
  const marked = selected ? 'rounded-small bg-primary/10 px-1 font-semibold' : ''
  if (!onSelect) return <span className={`font-mono ${marked}`}>{name}</span>
  return (
    <button
      type="button"
      aria-current={selected ? 'true' : undefined}
      onClick={() => onSelect(id)}
      className={`font-mono text-primary hover:underline ${marked}`}
    >
      {name}
    </button>
  )
}
```

`WaitQueueList.tsx`: props gain `onSelect?: (id: string) => void` and `selectedId?: string | null`; the entry's `<span className="font-mono truncate">{entry.label || entry.id}</span>` becomes `<span className="truncate"><CoroutineName id={entry.id} label={entry.label} onSelect={onSelect} selected={entry.id === selectedId} /></span>`.

`MutexStateIndicator.tsx`: props gain `selected?: boolean`, `selectedCoroutineId?: string | null`, `onSelectCoroutine?: (id: string) => void`. The outer `motion.div` gets `data-testid={`resource-${mutex.mutexId}`}`, `data-selected={selected ? 'true' : 'false'}`, and `className={selected ? 'rounded-xl ring-2 ring-primary' : 'rounded-xl'}`. The holder value becomes:

```tsx
              <div className="font-semibold">
                {mutex.currentHolder ? (
                  <CoroutineName
                    id={mutex.currentHolder}
                    label={mutex.currentHolderLabel}
                    onSelect={onSelectCoroutine}
                    selected={mutex.currentHolder === selectedCoroutineId}
                  />
                ) : (
                  'None'
                )}
              </div>
```

`SemaphoreGauge.tsx`: the same three props; the `Card` is wrapped in `<div data-testid={`resource-${semaphore.semaphoreId}`} data-selected={…} className={selected ? 'rounded-large ring-2 ring-primary' : ''}>`; in `CardBody`, before the waiting chip:

```tsx
        {/* Who holds the permits (S-6): counted, never numbered; a permit that came back from a
            coroutine that held none has no known holder (S-7). */}
        <div data-testid="semaphore-holders" className="text-xs">
          <span className="text-default-500">Held by </span>
          {semaphore.activeHolders.length === 0 && semaphore.unknownHolderPermits === 0 ? (
            <span>none</span>
          ) : (
            <span className="inline-flex flex-wrap items-baseline gap-x-2">
              {semaphore.activeHolders.map(h => (
                <span key={h.id}>
                  <CoroutineName id={h.id} label={h.label} onSelect={onSelectCoroutine} selected={h.id === selectedCoroutineId} />
                  {h.permits > 1 && <span className="text-default-500"> ({h.permits} permits)</span>}
                </span>
              ))}
              {semaphore.unknownHolderPermits > 0 && (
                <span className="text-default-500">
                  {semaphore.unknownHolderPermits === 1 ? '1 permit' : `${semaphore.unknownHolderPermits} permits`}, holder unknown
                </span>
              )}
            </span>
          )}
        </div>
```

`SyncPanel.tsx`:

```tsx
interface SyncPanelProps {
  sessionId: string
  /** The events the page shows (live, paused or at the replay cursor); the stored history when absent. */
  events?: readonly VizEvent[]
  /** A lock or semaphore the page selected: ringed and scrolled into view (S-2). */
  selectedResourceId?: string | null
  /** The selected coroutine, marked where it holds or waits. */
  selectedCoroutineId?: string | null
  /** Selects a coroutine by its name (S-2). */
  onSelectCoroutine?: (id: string) => void
}

export function SyncPanel({ sessionId, events, selectedResourceId = null, selectedCoroutineId = null, onSelectCoroutine }: SyncPanelProps) {
  const { mutexes, semaphores, deadlocks, warnings } = useSyncEvents(sessionId, events)
  // Bring a selected resource into view; the section was unfolded in the same render.
  const panelRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!selectedResourceId) return
    const el = panelRef.current?.querySelector(`[data-testid="resource-${CSS.escape(selectedResourceId)}"]`)
    el?.scrollIntoView?.({ block: 'nearest' })
  }, [selectedResourceId])
  …
```

`ref={panelRef}` goes on the non-empty `motion.div` root. Each `MutexStateIndicator` gets `selected={mutex.mutexId === selectedResourceId} selectedCoroutineId={selectedCoroutineId} onSelectCoroutine={onSelectCoroutine}`; each `WaitQueueList` gets `onSelect={onSelectCoroutine} selectedId={selectedCoroutineId}`; each `SemaphoreGauge` the same three as the mutex. (`CSS.escape` exists in jsdom ≥ 20 and every browser; if `tsc` or jsdom objects, fall back to a `Map<string, HTMLElement>` of refs.)

- [ ] **Step 4: Run the tests**

Run: `pnpm exec vitest run src/components/sync`
Expected: PASS (the earlier tests in the file still pass: they pass no `events`).

- [ ] **Step 5: Commit**

```bash
git add src/components/sync
git commit -m "feat(frontend): locks and permits follow the page's events; names select; holder unknown said plainly"
```

### Task 9: Channels, flows and jobs read the page's events; a selected channel is shown

**Files:**
- Modify: `frontend/src/components/channels/ChannelPanel.tsx`, `flow/FlowPanel.tsx`, `jobs/JobPanel.tsx`
- Test: `ChannelPanel.test.tsx`, `FlowPanel.test.tsx`, `JobPanel.test.tsx`

- [ ] **Step 1: Write the failing tests.** In each panel test (they mock the API the same way `SyncPanel.test.tsx` does) add a "reads the events it is given" test passing an `events` list with one resource while the stored history is empty, asserting it renders (channel: `screen.getByTestId('channel-selector-ch-1')`; flow and job: whatever element the file's existing "renders …" test asserts for one flow / one job). In `ChannelPanel.test.tsx` also:

```tsx
  it('shows the channel the page selected', () => {
    const events = [
      makeChannelEvent('ChannelCreated', 1, { channelId: 'ch-1', name: 'orders', capacity: 0, channelType: 'RENDEZVOUS' }),
      makeChannelEvent('ChannelCreated', 2, { channelId: 'ch-2', name: 'invoices', capacity: 0, channelType: 'RENDEZVOUS' }),
    ]
    render(<ChannelPanel sessionId="s" events={events} focusChannelId="ch-2" />, { wrapper: createWrapper() })
    expect(screen.getByText('Buffer State: invoices')).toBeInTheDocument()
  })
```

(use the file's own event-helper name.)

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm exec vitest run src/components/channels src/components/flow src/components/jobs`
Expected: FAIL.

- [ ] **Step 3: Implement.** Each panel's props gain `events?: readonly VizEvent[]` (doc as in `SyncPanel`) passed as the hook's second argument. `ChannelPanel` also gains `focusChannelId?: string | null` ("A channel the page selected (S-2): shown until the user picks another here.") and follows it with the render-phase pattern the workspace uses:

```tsx
  const [seenFocus, setSeenFocus] = useState<string | null>(null)
  if ((focusChannelId ?? null) !== seenFocus) {
    setSeenFocus(focusChannelId ?? null)
    if (focusChannelId) setSelectedChannelId(focusChannelId)
  }
```

- [ ] **Step 4: Run the tests**

Run: `pnpm exec vitest run src/components/channels src/components/flow src/components/jobs`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/components/channels src/components/flow src/components/jobs
git commit -m "feat(frontend): channel, flow and job evidence follow the page's events; a selected channel is shown"
```

### Task 10: Evidence passes the events and the selection; a resource unfolds its section

**Files:**
- Modify: `frontend/src/components/workspace/EvidencePanels.tsx`
- Test: `frontend/src/components/workspace/EvidencePanels.test.tsx`

- [ ] **Step 1: Write the failing tests.** Make the four panel mocks record their props:

```tsx
vi.mock('../channels/ChannelPanel', () => ({
  ChannelPanel: (p: { events?: unknown[]; focusChannelId?: string | null }) => (
    <div data-testid="channel-panel" data-events={p.events?.length ?? 'stored'} data-focus={p.focusChannelId ?? ''} />
  ),
}))
vi.mock('../sync/SyncPanel', () => ({
  SyncPanel: (p: { events?: unknown[]; selectedResourceId?: string | null; selectedCoroutineId?: string | null }) => (
    <div data-testid="sync-panel" data-events={p.events?.length ?? 'stored'} data-resource={p.selectedResourceId ?? ''} data-coroutine={p.selectedCoroutineId ?? ''} />
  ),
}))
```

(FlowPanel and JobPanel the same with `data-events`.) Then:

```tsx
describe('EvidencePanels — the page\'s events and selection (S-2)', () => {
  const synced = { categories: categories({ hasSyncPrimitives: true, hasChannels: true }), counts: { ...none, locks: 1, channels: 1 } }
  const events = [{ seq: 1 }, { seq: 2 }] as unknown as VizEvent[]

  it('gives every event-backed panel the page\'s events', () => {
    renderPanels({ ...synced, events })
    expect(screen.getByTestId('sync-panel')).toHaveAttribute('data-events', '2')
    expect(screen.getByTestId('channel-panel')).toHaveAttribute('data-events', '2')
  })

  it('unfolds Locks and permits for a selected lock, and passes the lock on', () => {
    const { rerender } = renderPanels({ ...synced, events })
    expect(screen.getByRole('button', { name: /Locks and permits/ })).toHaveAttribute('aria-expanded', 'false')
    rerender(panels({ ...synced, events, selection: { kind: 'resource', resource: 'mutex', id: 'm-1' } }))
    expect(screen.getByRole('button', { name: /Locks and permits/ })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('sync-panel')).toHaveAttribute('data-resource', 'm-1')
  })

  it('unfolds Channels for a selected channel and shows it', () => {
    const { rerender } = renderPanels({ ...synced, events })
    rerender(panels({ ...synced, events, selection: { kind: 'resource', resource: 'channel', id: 'ch-1' } }))
    expect(screen.getByRole('button', { name: /Channels/ })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('channel-panel')).toHaveAttribute('data-focus', 'ch-1')
  })

  it('passes a selected coroutine to Locks and permits without unfolding anything', () => {
    renderPanels({ ...synced, events, selection: { kind: 'coroutine', id: 'c-1' } })
    expect(screen.getByTestId('sync-panel')).toHaveAttribute('data-coroutine', 'c-1')
    expect(screen.getByRole('button', { name: /Locks and permits/ })).toHaveAttribute('aria-expanded', 'false')
  })

  it('follows the replay cursor, so Locks and permits carries no live-data warning in replay', async () => {
    renderPanels({ ...synced, events, replayActive: true })
    await userEvent.click(screen.getByRole('button', { name: /Locks and permits/ }))
    expect(within(screen.getByTestId('evidence-section-Locks and permits')).queryByTestId('live-data-notice')).toBeNull()
  })
})
```

Refactor `renderPanels` into `panels(props)` (returns the element) and `renderPanels(props) => render(panels(props))`, and import `VizEvent` as a type.

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm exec vitest run src/components/workspace/EvidencePanels.test.tsx`
Expected: FAIL — `data-events` reads `stored`; sections stay folded; the notice is shown.

- [ ] **Step 3: Implement** — `EvidencePanels.tsx`. Props gain:

```ts
  /** The events the page shows: live, paused, or at the replay cursor. The panels follow them. */
  events?: readonly VizEvent[]
  /** The page's one selection (S-2). A resource unfolds its section; a coroutine is marked in it. */
  selection?: Selection | null
  /** Selects a coroutine named in Locks and permits. */
  onSelectCoroutine?: (id: string) => void
```

After the `open` state, the render-phase sync (the pattern `SessionWorkspace` uses for its opening tab):

```ts
  // A resource selected anywhere on the page unfolds its section (S-2). Each
  // selection is a new object, so choosing the same lock again re-opens it.
  const [seenSelection, setSeenSelection] = useState<Selection | null>(selection)
  if (selection !== seenSelection) {
    setSeenSelection(selection)
    const section = selection?.kind === 'resource' ? (selection.resource === 'channel' ? 'Channels' : 'Locks and permits') : null
    if (section && !open.has(section)) setOpen(prev => new Set(prev).add(section))
  }
  const selectedResourceId = selection?.kind === 'resource' ? selection.id : null
  const selectedCoroutineId = selection?.kind === 'coroutine' ? selection.id : null
```

The capability panels become:

```tsx
      panel: (
        <SyncPanel
          sessionId={sessionId}
          events={events}
          selectedResourceId={selection?.kind === 'resource' && selection.resource !== 'channel' ? selectedResourceId : null}
          selectedCoroutineId={selectedCoroutineId}
          onSelectCoroutine={onSelectCoroutine}
        />
      ),
      …
      panel: <ChannelPanel sessionId={sessionId} events={events} focusChannelId={selection?.kind === 'resource' && selection.resource === 'channel' ? selection.id : null} />,
      …
      panel: <FlowPanel sessionId={sessionId} events={events} />,
      …
      panel: <JobPanel sessionId={sessionId} events={events} />,
```

and the captured `Section` gets `replayActive={false}` with the comment `{/* Read from the page's events, so it follows the replay cursor like Threads (D-17). */}`. The `Dispatchers` section keeps `replayActive={replayActive}`. Update the component's doc comment: "Folded sections are hidden, not unmounted: every panel subscribes at page load … Locks and permits, Channels, Flows and Jobs read the events the page shows, so they refresh live and follow the replay cursor."

- [ ] **Step 4: Run the tests**

Run: `pnpm exec vitest run src/components/workspace/EvidencePanels.test.tsx`
Expected: PASS (the existing "warns that an opened panel is not following the replay cursor" test still passes on Dispatchers).

- [ ] **Step 5: Commit**

```bash
git add src/components/workspace/EvidencePanels.tsx src/components/workspace/EvidencePanels.test.tsx
git commit -m "feat(frontend): a selected lock, permit or channel opens its Evidence section"
```

### Task 11: Event rows select what they are about; the Events tab follows the selection

**Files:**
- Modify: `frontend/src/components/EventsList.tsx`
- Create: `frontend/src/components/EventsList.test.tsx`
- Modify: `frontend/src/components/workspace/EventsPanel.tsx`
- Test: `frontend/src/components/workspace/EventsPanel.test.tsx` (rewritten for the new props)

- [ ] **Step 1: Write the failing tests.** `EventsList.test.tsx`:

```tsx
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import type { VizEvent } from '@/types/api'
import { eventSubject } from '@/lib/selection'
import { EventsList } from './EventsList'

vi.mock('framer-motion', async () => {
  const actual = await vi.importActual<typeof import('framer-motion')>('framer-motion')
  return {
    ...actual,
    motion: { div: ({ children, ...p }: Record<string, unknown>) => <div {...(p as object)}>{children as ReactNode}</div> },
    AnimatePresence: ({ children }: { children: ReactNode }) => <>{children}</>,
  }
})

const acquired = {
  sessionId: 's', seq: 7, tsNanos: 7, kind: 'MutexLockAcquired',
  mutexId: 'm-1', mutexLabel: 'inventory-lock', acquirerId: 'c-2', acquirerLabel: 'order-2', waitDurationNanos: 0,
} as unknown as VizEvent
const created = { sessionId: 's', seq: 6, tsNanos: 6, kind: 'MutexCreated', mutexId: 'm-1', mutexLabel: 'inventory-lock', ownerCoroutineId: null } as unknown as VizEvent
const deadlock = { sessionId: 's', seq: 8, tsNanos: 8, kind: 'DeadlockDetected', involvedCoroutines: [] } as unknown as VizEvent

describe('EventsList', () => {
  it('names a lock event by who and what', () => {
    render(<EventsList events={[acquired]} />)
    expect(screen.getByText('order-2 · inventory-lock')).toBeInTheDocument()
  })

  it('makes each row about something selectable a button that selects it (S-2)', async () => {
    const onSelect = vi.fn()
    render(<EventsList events={[created, acquired, deadlock]} subjectOf={eventSubject} onSelect={onSelect} />)
    await userEvent.click(screen.getByRole('button', { name: /Select order-2 · inventory-lock/ }))
    await userEvent.click(screen.getByRole('button', { name: /Select inventory-lock/ }))
    expect(onSelect.mock.calls).toEqual([
      [{ kind: 'coroutine', id: 'c-2' }],
      [{ kind: 'resource', resource: 'mutex', id: 'm-1' }],
    ])
    // A deadlock is about no one thing: not a button.
    expect(screen.getAllByRole('button', { name: /^Select / })).toHaveLength(2)
  })

  it('keeps rows presentational without onSelect', () => {
    render(<EventsList events={[acquired]} />)
    expect(screen.queryByRole('button', { name: /^Select / })).toBeNull()
  })
})
```

`EventsPanel.test.tsx` — replace the three tests with:

```tsx
vi.mock('../EventsList', () => ({
  EventsList: ({ events, onSelect }: { events: VizEvent[]; onSelect?: unknown }) => (
    <div data-testid="events-list" data-count={events.length} data-selectable={String(!!onSelect)} />
  ),
}))

const lockEvent = { sessionId: 's-1', seq: 5, tsNanos: 5, kind: 'MutexLockAcquired', mutexId: 'm-1', acquirerId: 'c-1' } as unknown as VizEvent
const EVENTS: VizEvent[] = [event(1, 'c-1'), event(2, 'c-2'), event(3, 'c-1'), event(4), lockEvent]

describe('EventsPanel', () => {
  it('lists the whole session when nothing is selected', () => {
    render(<EventsPanel events={EVENTS} selection={null} selectionLabel={null} />)
    expect(screen.getByTestId('events-scope')).toHaveTextContent('All 5 events in this session')
    expect(screen.getByTestId('events-list')).toHaveAttribute('data-count', '5')
    expect(screen.queryByRole('button', { name: 'All events' })).toBeNull()
  })

  it('narrows to the selected coroutine, its lock and permit events included', () => {
    render(<EventsPanel events={EVENTS} selection={{ kind: 'coroutine', id: 'c-1' }} selectionLabel="fetchUser" />)
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events for fetchUser · 3')
    expect(screen.getByTestId('events-list')).toHaveAttribute('data-count', '3')
  })

  it('narrows to a selected resource', () => {
    render(<EventsPanel events={EVENTS} selection={{ kind: 'resource', resource: 'mutex', id: 'm-1' }} selectionLabel="inventory-lock" />)
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events about inventory-lock · 1')
  })

  it('goes back to all events, clearing the selection', async () => {
    const onShowAll = vi.fn()
    render(<EventsPanel events={EVENTS} selection={{ kind: 'coroutine', id: 'c-1' }} selectionLabel="c-1" onShowAll={onShowAll} />)
    await userEvent.click(screen.getByRole('button', { name: 'All events' }))
    expect(onShowAll).toHaveBeenCalled()
  })

  it('makes rows selectable only when the page offers selection', () => {
    const { rerender } = render(<EventsPanel events={EVENTS} selection={null} selectionLabel={null} />)
    expect(screen.getByTestId('events-list')).toHaveAttribute('data-selectable', 'false')
    rerender(<EventsPanel events={EVENTS} selection={null} selectionLabel={null} subjectOf={() => null} onSelect={vi.fn()} />)
    expect(screen.getByTestId('events-list')).toHaveAttribute('data-selectable', 'true')
  })
})
```

(import `userEvent`.)

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm exec vitest run src/components/EventsList.test.tsx src/components/workspace/EventsPanel.test.tsx`
Expected: FAIL — no title, no buttons, unknown props.

- [ ] **Step 3: Implement.**

`EventsList.tsx`: import `eventTitle` and `type Selection` from `@/lib/selection`. Props:

```ts
interface EventsListProps {
  events: VizEvent[]
  /** What a row is about (S-2); null keeps that row presentational. */
  subjectOf?: (event: VizEvent) => Selection | null
  /** Selects what a row is about. Without it every row is presentational. */
  onSelect?: (selection: Selection) => void
}
```

In the row, compute `const subject = onSelect && subjectOf ? subjectOf(event) : null` and `const title = eventTitle(event)`. The `Card` gains, when `subject` is non-null:

```tsx
                {...(subject && onSelect
                  ? { isPressable: true, 'aria-label': `Select ${title} (event #${event.seq})`, onPress: () => onSelect(subject) }
                  : {})}
```

(plus `w-full` on its className so a pressable card keeps the row's width). The title fallback `{event.label || event.coroutineId}` becomes `{title}`, and the `ID:` line renders only when `event.coroutineId` is set:

```tsx
                        {event.coroutineId && (
                          <div className="text-xs text-default-500 mt-1">
                            ID: <code className="font-mono">{event.coroutineId}</code>
                            {event.parentCoroutineId && (
                              <> • Parent: <code className="font-mono">{event.parentCoroutineId}</code></>
                            )}
                          </div>
                        )}
```

`EventsPanel.tsx`:

```tsx
import { useMemo } from 'react'
import { EventsList } from '../EventsList'
import { eventConcerns, type Selection } from '@/lib/selection'
import type { VizEvent } from '@/types/api'

interface EventsPanelProps {
  /** The session's events, from the live snapshot or the replay cursor. */
  events: VizEvent[]
  /** The page's one selection (S-2). Null = the whole session. */
  selection: Selection | null
  /** What to call it: the coroutine's or the resource's name. */
  selectionLabel: string | null
  /** What a row is about; with `onSelect`, rows become buttons (live view only, PD-01). */
  subjectOf?: (event: VizEvent) => Selection | null
  onSelect?: (selection: Selection) => void
  /** Clears the selection, back to every event. */
  onShowAll?: () => void
}

/**
 * The Events tab (spec S-4): the raw event stream, scoped to the page's
 * selection — a coroutine's events, the lock and permit events about it
 * included, or a resource's. A row selects what it is about (S-2).
 */
export function EventsPanel({ events, selection, selectionLabel, subjectOf, onSelect, onShowAll }: EventsPanelProps) {
  const scoped = useMemo(
    () => (selection ? events.filter(e => eventConcerns(e, selection)) : events),
    [events, selection],
  )
  const who = selection ? (selectionLabel ?? selection.id) : null

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-baseline gap-x-2">
        <p data-testid="events-scope" className="text-xs text-default-500">
          {who ? (
            <>
              {selection?.kind === 'resource' ? 'Events about ' : 'Events for '}
              <span className="font-mono text-default-700">{who}</span> · {scoped.length}
            </>
          ) : (
            `All ${events.length} events in this session`
          )}
        </p>
        {selection && onShowAll && (
          <button type="button" onClick={onShowAll} className="ml-auto text-xs text-primary hover:underline">
            All events
          </button>
        )}
      </div>
      <EventsList events={scoped} subjectOf={subjectOf} onSelect={onSelect} />
    </div>
  )
}
```

- [ ] **Step 4: Run the tests**

Run: `pnpm exec vitest run src/components/EventsList.test.tsx src/components/workspace/EventsPanel.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/components/EventsList.tsx src/components/EventsList.test.tsx src/components/workspace/EventsPanel.tsx src/components/workspace/EventsPanel.test.tsx
git commit -m "feat(frontend): an event row selects what it is about; Events follows the selection"
```

### Task 12: The Inspector says what the coroutine waits for or holds

**Files:**
- Modify: `frontend/src/components/workspace/Inspector/Inspector.tsx`
- Test: `frontend/src/components/workspace/Inspector/Inspector.test.tsx`

- [ ] **Step 1: Write the failing test** (using the file's `coroutine()` helper and its render pattern, with `getCoroutineTimeline` resolving the file's `timeline()` fixture):

```tsx
  it('says under the heading what the coroutine waits for or holds (S-6)', async () => {
    getCoroutineTimeline.mockResolvedValue(timeline())
    render(
      <Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} activity={{ text: 'waits for inventory-lock · 1st', waiting: true }} />,
      { wrapper: createWrapper() },
    )
    const line = screen.getByTestId('inspector-activity')
    expect(line).toHaveTextContent('waits for inventory-lock · 1st')
    expect(line.className).toContain('text-warning')
  })

  it('has no such line when the coroutine waits for and holds nothing', () => {
    getCoroutineTimeline.mockResolvedValue(timeline())
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} />, { wrapper: createWrapper() })
    expect(screen.queryByTestId('inspector-activity')).toBeNull()
  })
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm exec vitest run src/components/workspace/Inspector/Inspector.test.tsx`
Expected: FAIL — no `inspector-activity`.

- [ ] **Step 3: Implement** — prop `activity?: ActivityLine | null` ("What the selected coroutine waits for or holds (S-6), from the page's events."); after the heading row's closing `</div>`:

```tsx
        {activity && (
          <p
            data-testid="inspector-activity"
            className={activity.waiting ? 'text-sm text-warning' : 'text-sm text-default-600'}
          >
            {activity.text}
          </p>
        )}
```

- [ ] **Step 4: Run the test**

Run: `pnpm exec vitest run src/components/workspace/Inspector/Inspector.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/components/workspace/Inspector/Inspector.tsx src/components/workspace/Inspector/Inspector.test.tsx
git commit -m "feat(frontend): the Inspector says what the coroutine waits for or holds"
```

### Task 13: One selection in the workspace

**Files:**
- Modify: `frontend/src/components/SessionWorkspace.tsx`
- Create: `frontend/src/components/SessionWorkspace.selection.test.tsx`
- Modify: `frontend/src/components/SessionWorkspace.test.tsx` (the `EventsList` mock keeps working; nothing else should change — run it)

- [ ] **Step 1: Write the failing integration tests** — `SessionWorkspace.selection.test.tsx` renders the real list view, event list, Inspector, Evidence and Locks and permits; it mocks only the network and heavy leaves:

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { SessionWorkspace } from './SessionWorkspace'
import type { CoroutineState, SessionSnapshot, VizEvent } from '@/types/api'

vi.mock('@/hooks/use-sessions', () => ({
  useSession: vi.fn(),
  useSessionEvents: vi.fn(() => ({ data: [], isPending: false, isError: false })),
  useDeleteSession: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
  useCreateSession: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
}))
vi.mock('@/hooks/use-event-stream', () => ({ useEventStream: vi.fn() }))
vi.mock('@/hooks/use-scenarios', () => ({ useRunScenario: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })) }))
vi.mock('@/lib/api-client', () => ({
  apiClient: {
    getThreadActivity: vi.fn(() => Promise.resolve({})),
    getCoroutineTimeline: vi.fn(() => new Promise(() => {})),
    getMetrics: vi.fn(() => new Promise(() => {})),
    getSessionEvents: vi.fn(() => Promise.resolve([])),
  },
}))
vi.mock('./DispatcherOverview', () => ({ DispatcherOverview: () => null }))
vi.mock('./SessionMetrics', () => ({ SessionMetrics: () => null }))
vi.mock('./export/ExportMenu', () => ({ ExportMenu: () => null }))
vi.mock('./replay/RecordConfirmModal', () => ({ RecordConfirmModal: () => null }))
vi.mock('@tanstack/react-router', () => ({ useNavigate: vi.fn(() => vi.fn()) }))
vi.mock('@/lib/animation-throttle', () => ({ useAnimationSlot: () => false }))
vi.mock('framer-motion', async () => {
  const actual = await vi.importActual<typeof import('framer-motion')>('framer-motion')
  const pass = ({ children, ...p }: Record<string, unknown>) => <div {...(p as object)}>{children as ReactNode}</div>
  return { ...actual, motion: { div: pass, span: pass, button: pass, circle: () => null }, AnimatePresence: ({ children }: { children: ReactNode }) => <>{children}</>, LayoutGroup: ({ children }: { children: ReactNode }) => <>{children}</> }
})

import { useSession } from '@/hooks/use-sessions'
import { useEventStream } from '@/hooks/use-event-stream'

const lock = { mutexId: 'm-1', mutexLabel: 'inventory-lock' }
let seq = 0
const ev = (kind: string, f: Record<string, unknown>) => ({ sessionId: 'session-1', seq: ++seq, tsNanos: seq * 1000, kind, ...f }) as unknown as VizEvent
const co = (kind: string, id: string, f: Record<string, unknown> = {}) =>
  ev(kind, { coroutineId: id, jobId: `j-${id}`, parentCoroutineId: null, scopeId: 's', label: id === 'c-1' ? 'order-1' : 'order-2', ...f })

function session(states: [CoroutineState, CoroutineState]): SessionSnapshot {
  return {
    sessionId: 'session-1', coroutineCount: 2, eventCount: 6,
    coroutines: [
      { id: 'c-1', jobId: 'j-c-1', parentId: null, scopeId: 's', label: 'order-1', state: states[0] },
      { id: 'c-2', jobId: 'j-c-2', parentId: null, scopeId: 's', label: 'order-2', state: states[1] },
    ],
  }
}

const contended = [
  ev('MutexCreated', { ...lock, ownerCoroutineId: null }),
  co('coroutine.started', 'c-1'),
  ev('MutexLockAcquired', { ...lock, acquirerId: 'c-1', acquirerLabel: 'order-1', waitDurationNanos: 0 }),
  co('coroutine.started', 'c-2'),
  ev('MutexQueueChanged', { ...lock, waitingCoroutineIds: ['c-2'], waitingLabels: ['order-2'] }),
  co('coroutine.suspended', 'c-2', { waitKind: 'MUTEX', resourceId: 'm-1', reason: 'mutex' }),
]
const handedOver = [
  ...contended,
  ev('MutexUnlocked', { ...lock, releaserId: 'c-1', releaserLabel: 'order-1', nextWaiterId: 'c-2', holdDurationNanos: 1 }),
  co('coroutine.resumed', 'c-2'),
  ev('MutexLockAcquired', { ...lock, acquirerId: 'c-2', acquirerLabel: 'order-2', waitDurationNanos: 1 }),
  ev('MutexQueueChanged', { ...lock, waitingCoroutineIds: [], waitingLabels: [] }),
]

function stream(events: VizEvent[]) {
  vi.mocked(useEventStream).mockReturnValue({
    events, isConnected: true, error: null, clearEvents: vi.fn(), droppedCount: 0, receivedCount: events.length, reconnect: vi.fn(),
  } as unknown as ReturnType<typeof useEventStream>)
}

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{children}</QueryClientProvider>
}

async function openList() {
  await userEvent.click(screen.getByRole('button', { name: 'List' }))
}
const card = (name: string) => screen.getByRole('button', { name: `Open source for ${name}` })

describe('SessionWorkspace — one selection, live facts (S-2, S-6)', () => {
  beforeEach(() => {
    localStorage.clear()
    seq = 0
    vi.mocked(useSession).mockReturnValue({ data: session(['ACTIVE' as CoroutineState, 'SUSPENDED' as CoroutineState]), isLoading: false, refetch: vi.fn() } as unknown as ReturnType<typeof useSession>)
  })

  it('each node says what it waits for or holds, and the lines follow new events without a reload', async () => {
    stream(contended)
    const { rerender } = render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await openList()
    expect(within(card('order-1')).getByTestId('activity-line')).toHaveTextContent('holds inventory-lock')
    expect(within(card('order-2')).getByTestId('activity-line')).toHaveTextContent('waits for inventory-lock · 1st')

    stream(handedOver)
    rerender(<SessionWorkspace sessionId="session-1" startLive />)
    expect(within(card('order-1')).queryByTestId('activity-line')).toBeNull()
    expect(within(card('order-2')).getByTestId('activity-line')).toHaveTextContent('holds inventory-lock')
  })

  it('Evidence lock facts follow new events too', async () => {
    stream(contended)
    const { rerender } = render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await userEvent.click(screen.getByRole('tab', { name: /Evidence/ }))
    await userEvent.click(screen.getByRole('button', { name: /Locks and permits/ }))
    expect(screen.getByTestId('mutex-state')).toHaveTextContent('order-1')
    stream(handedOver)
    rerender(<SessionWorkspace sessionId="session-1" startLive />)
    expect(within(screen.getByTestId('mutex-state')).getByRole('button', { name: 'order-2' })).toBeInTheDocument()
  })

  it('an event row selects its coroutine everywhere: the tree, the Events scope, the Inspector', async () => {
    stream(contended)
    render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await openList()
    await userEvent.click(screen.getByRole('tab', { name: 'Events' }))
    await userEvent.click(screen.getByRole('button', { name: /Select order-1 · inventory-lock/ }))
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events for order-1')
    expect(card('order-1').className).toContain('ring-2')
    await userEvent.click(screen.getByRole('tab', { name: 'Inspector' }))
    expect(screen.getByRole('heading', { name: /order-1/ })).toBeInTheDocument()
    expect(screen.getByTestId('inspector-activity')).toHaveTextContent('holds inventory-lock')
  })

  it('a resource-only event selects the lock and opens its Evidence section', async () => {
    stream(contended)
    render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await userEvent.click(screen.getByRole('tab', { name: 'Events' }))
    await userEvent.click(screen.getByRole('button', { name: /Select inventory-lock \(event #1\)/ }))
    expect(screen.getByRole('tab', { name: /Evidence/ })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('button', { name: /Locks and permits/ })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('resource-m-1')).toHaveAttribute('data-selected', 'true')
  })

  it('a node click selects the coroutine and opens the Inspector; a holder name in Evidence selects it too', async () => {
    stream(contended)
    render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await openList()
    await userEvent.click(card('order-2'))
    expect(screen.getByRole('tab', { name: 'Inspector' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('inspector-activity')).toHaveTextContent('waits for inventory-lock · 1st')
    await userEvent.click(screen.getByRole('tab', { name: /Evidence/ }))
    await userEvent.click(screen.getByRole('button', { name: /Locks and permits/ }))
    await userEvent.click(within(screen.getByTestId('mutex-state')).getByRole('button', { name: 'order-1' }))
    expect(card('order-1').className).toContain('ring-2')
    expect(screen.getByRole('tab', { name: /Evidence/ })).toHaveAttribute('aria-selected', 'true')
  })
})
```

(If the real `CoroutineTree` card's accessible name is not `Open source for …` once HeroUI renders it, match it the way `SessionWorkspace.reachability.test.tsx` finds its card.)

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm exec vitest run src/components/SessionWorkspace.selection.test.tsx`
Expected: FAIL — no second lines, no selectable rows, Evidence reads the (empty) stored history.

- [ ] **Step 3: Implement** — `SessionWorkspace.tsx`:

Imports: `useCallback`; `{ projectActivity }` from `@/lib/activity`; `{ eventSubject, resourceNames, type Selection }` from `@/lib/selection`.

Replace the `selectedCoroutineId` state (and its comment) with:

```ts
  // The page's one selection (S-2): a coroutine or a resource, read by the
  // tree, the Inspector, Evidence and Events. Set only in the live view (PD-01).
  const [selection, setSelection] = useState<Selection | null>(null)
  const selectedCoroutineId = selection?.kind === 'coroutine' ? selection.id : null
```

The clearing effect becomes:

```ts
  useEffect(() => {
    if (selectedCoroutineId && !panelCoroutines.some(c => c.id === selectedCoroutineId)) {
      setSelection(null)
    }
  }, [selectedCoroutineId, panelCoroutines])
```

After `evidenceCounts`:

```ts
  // What each coroutine waits for or holds (S-6), and every resource's name,
  // from the same events as the rest of the page: live, paused or at the replay cursor.
  const activity = useMemo(() => projectActivity(panelEvents), [panelEvents])
  const names = useMemo(() => resourceNames(panelEvents), [panelEvents])
  // An event about a coroutine the snapshot does not hold selects nothing:
  // the selection would be cleared at once.
  const coroutineIds = useMemo(() => new Set(panelCoroutines.map(c => c.id)), [panelCoroutines])
  const subjectOf = useCallback(
    (event: VizEvent) => {
      const subject = eventSubject(event)
      return subject?.kind === 'coroutine' && !coroutineIds.has(subject.id) ? null : subject
    },
    [coroutineIds],
  )
  const selectionLabel =
    selection === null
      ? null
      : selection.kind === 'coroutine'
        ? (selectedCoroutine?.label ?? selection.id)
        : (names.get(selection.id) ?? selection.id)
```

(import `VizEvent` as a type alongside `ThreadActivity`; place `selectionLabel` after `selectedCoroutine`.)

After `selectDockTab`:

```ts
  // A selection made inside a tab keeps you there; a resource opens its
  // Evidence section (S-2), which EvidencePanels unfolds and scrolls to.
  const selectFromEvent = (next: Selection) => {
    setSelection(next)
    if (next.kind === 'resource') selectDockTab('evidence')
  }
  const selectCoroutine = (id: string) => setSelection({ kind: 'coroutine', id })
```

Wiring:
- `CoroutineCanvas`: `activity={activity}`; the `onSelect` callback body becomes `selectCoroutine(id); selectDockTab('inspector')`.
- `Inspector`: `activity={selectedCoroutine ? (activity.get(selectedCoroutine.id) ?? null) : null}`; `onClearSelection={isLiveView ? () => setSelection(null) : undefined}`.
- `EvidencePanels`: `events={panelEvents} selection={isLiveView ? selection : null} onSelectCoroutine={isLiveView ? selectCoroutine : undefined}`.
- `EventsPanel`:

```tsx
              <EventsPanel
                events={panelEvents}
                selection={isLiveView ? selection : null}
                selectionLabel={selectionLabel}
                subjectOf={isLiveView ? subjectOf : undefined}
                onSelect={isLiveView ? selectFromEvent : undefined}
                onShowAll={() => setSelection(null)}
              />
```

- [ ] **Step 4: Run the workspace tests**

Run: `pnpm exec vitest run src/components/SessionWorkspace`
Expected: PASS, including `SessionWorkspace.test.tsx` ("shows LiveDataNotice on a projection-backed tab while replaying" still finds the Dispatchers notice) and the reachability and request-budget tests. If the request-budget test counts `useSessionEvents` observers, it is unchanged: every panel still subscribes.

- [ ] **Step 5: Commit**

```bash
git add src/components/SessionWorkspace.tsx src/components/SessionWorkspace.selection.test.tsx
git commit -m "feat(frontend): one selection across tree, tabs and events; facts follow the live stream"
```

### Task 14: Gates, browser check, PR

- [ ] **Step 1: Gates** — from `frontend/`: `pnpm exec tsc --noEmit && pnpm lint && pnpm test && pnpm build`. Expected: all green; tests ≈ 1,074 + the new ones. If the build regenerates `backend/docs/index.html`, `git checkout -- ../backend/docs/index.html`.

- [ ] **Step 2: Browser check** on backend **18501** and frontend **13501** only (`JAVA_HOME=/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home`; start sessions at least 7 s apart). Check, at 1440 and 900 px, dark and light:
  - clicking a node selects it in the tree, the Inspector (second line under the heading) and the Events scope;
  - clicking an event row selects its coroutine; a lock or permit event row selects the requester / acquirer / releaser; a `MutexCreated` / `SemaphoreCreated` / `ChannelCreated` row opens Evidence at its section with the resource ringed;
  - during a live Mutex, Semaphore, Combined and Channels demo, the node second lines ("waits for … · 1st", "holds …", "holds a … permit", "waits to send to …") and the Evidence lock/permit facts (holder, queue, "Held by", "holder unknown" where it occurs) change without a reload.
  Screenshots to `/private/tmp/claude-501/-Users-jirihermann-Documents-workspace-vizcore/9e9c5bc5-1b4c-412c-966d-6cf483ca2458/scratchpad/part4-shots/`. Stop the servers.

- [ ] **Step 3: Push and open the PR** to `main` with this plan, in #294's structure (What / Tests / Browser check / Codex review / Known, not in this PR). Do not merge.
