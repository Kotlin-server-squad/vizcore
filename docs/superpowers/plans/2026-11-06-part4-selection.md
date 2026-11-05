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
  A wait comes from the suspension the wrapper recorded (`waitKind`, Part 2), until the coroutine's next resume, suspension or end. **A queue alone is not taken for a wait**: a waiter that has resumed stays listed until the next queue event, and an older producer records no wait kind — no line is better than a wrong one (its queue still shows in Evidence). A resource is named by its label, else its id. A wait line uses the Suspended colour (`text-warning`, readable in both themes); a hold alone is neutral (`text-default-600`): a held lock is normal work (S-6).
- **"holder unknown"** (S-6/S-7). `unknownHolderPermits` counts *listed* permits beyond those in use: a permit came back from a coroutine that held none, so one of the listed holders no longer holds one, and which is unknown. Then Locks and permits reads "Held by holder unknown — 1 of 3 permits in use; last recorded holders order-3, order-4. A permit came back from a coroutine that held none.", and each listed holder's second line reads "may hold a payment-gateway permit · holder unknown". Otherwise the holders are listed with their counts ("order-3 (2 permits), order-4").
- **The projections read events in seq order** (`inSeqOrder`), not arrival order: the live stream accepts an event whose seq is lower than one it holds, and a suspension that arrives after its own resume must not read as a wait. Already-ordered input (the usual case) is not copied.
- **A late release keeps the new holder.** `VizMutex.unlock` releases the lock before it records `MutexUnlocked`, so the waiter it woke can record `MutexLockAcquired` first — possibly several handovers in a row. Each holder displaced by an acquisition joins a per-lock multiset of pending releases; a release found there is consumed and frees nothing; any other release frees the lock. A coroutine that acquires again leaves the multiset (it records its release before it can acquire again), so a lost release cannot swallow a later real one.
- **Permit counts come from state snapshots only; between snapshots the holders are unsettled.** A semaphore's `SemaphoreStateChanged` follows each acquire and release, but a stream flush or a replay cursor can fall between the two, and whether a snapshot already counted an operation cannot be told from the totals: VizSemaphore records a holder before the acquisition event and drops it before the release event, and a semaphore can start with permits taken (`acquiredPermits`). So any acquire or release since the last snapshot marks that semaphore's holder list **unsettled** until the next snapshot (an acquirer not listed yet is added to the list; no count changes). While unsettled, every listed holder's node reads "may hold a … permit · holder unknown", and Evidence reads "holder unknown — N of M permits in use; last recorded holders …" with no per-holder counts. A snapshot or release with every permit free means nobody holds one (a snapshot copies its holders before it reads the free permits). Exact attribution between snapshots is not attempted.
- **Permits in use with no recorded holder** (taken at creation, `acquiredPermits`, or before the retained history) read "holder unknown — 3 of 3 permits in use; no holder was recorded", or "order-3, and 1 permit with no recorded holder"; "Held by none" only when no permit is in use.
- **Every reducer reads in seq order:** locks and permits, waits, channels, flows and jobs.
- **In replay, Evidence reads the cursor:** which sections have anything (`categorizeEvents` over the cursor's events) as well as their contents; the rung keeps the whole session's categories.
- **Selecting a resource reveals it every time:** the section unfolds, and the lock, semaphore or channel is ringed and scrolled into view — again when the same resource is selected again (a reveal key that changes with every selection).
- **One selection, also for channels.** The Channels section's own channel picker selects the channel on the page (Events then shows its events); the page's selection decides which channel the section shows.
- **A selection that is gone is cleared:** a coroutine no longer in the snapshot (as before), and a resource no longer named by the page's events, a retained suspension waiting for it included (Clear events, a history trimmed past it). Clear events also clears the selection.
- **Only what the page can select is a button.** Holder and waiter names in Locks and permits are buttons only for coroutines in the snapshot (a `CoroutinePicker` with `canSelect`), like event rows; a wrapper used outside any coroutine records "unknown…", which reads "outside any coroutine", in rows and in Evidence. Channel rows are named by the channel's name.
- **Evidence follows the page's events.** `useSyncEvents`, `useChannelEvents`, `useFlowEvents` and `useJobEvents` take the events the workspace passes (`panelEvents`), falling back to the stored history when none are passed; they still subscribe to it, so the request profile (#124) is unchanged. In replay these sections therefore follow the cursor, like Threads, and lose their "showing current data" notice; Dispatchers, still a server read, keeps it.
- **The graph card grows 16 px** (224 → 240) to hold the second line under the state; the list view adds it under the id line.
- **Not in this part:** the paused view still reads the history loaded with the page (as Evidence did before); "Refresh" re-reads the snapshot only. Part 8 binds every read to one moment.

## Also in this PR (decided by the user)

- **Metric tiles** read "Active0", "Peak6": label and value sit in a `justify-between` row with no gap inside a card that shrinks to its content. The tile becomes a `<dl>` (term, definition) with a gap.
- **Design doc, S-3**, amended with two decisions made while building Part 3: Export stays its own menu next to "⋯" (a menu inside a menu is awkward); T-2's "last data" and start time wait for Part 7, because they need the health API's wall-clock observation time, not the browser's clock.

## Review history

- **Codex plan review R1 — request changes** (8 P1, 2 P2), all taken: unknown-holder permits read the wrong way round; a queue alone resurrected ended waits; a late `MutexUnlocked` freed a lock already handed over; arrival order was trusted; permit operations did not move holders between snapshots; the Channels picker kept a second selection; a resource selection never went stale; an optional `selection` did not type-check in `useState`; channel rows had empty titles; Evidence names offered unselectable coroutines. The decisions above and Tasks 3, 5, 6, 8, 9, 10, 11 and 13 carry the fixes; Tasks 1–13 are already built on `feat/session-selection`, so this plan shows the code as built.
- **Codex plan review R2 — request changes** (2 P1, 5 P2, 1 P3), all taken: one remembered previous holder was not enough for several handovers; a snapshot that already counted an acquisition was counted again; channel, flow and job reducers still read arrival order; "may hold" with every permit free, and "Held by none" with permits in use; a resource selected again was not revealed again, and the focused channel was not marked; a resource a retained suspension waits for was cleared as gone; in replay, Evidence presence read events beyond the cursor; the `unknownHolderPermits` comment had the old meaning.
- **Codex plan review R3 — request changes** (1 P1, 1 P2): reconciling operations with permit totals still misattributed permits (a semaphore created with a permit taken; excess ambiguous holders), and a snapshot with every permit free could still yield "may hold". Fixed as above (approved by the coordinator): counts from snapshots only, operations list unconfirmed acquirers, every-permit-free clears holders after snapshots too. The confirmation review found one more P1/P2 of the same kind (kept counts after a release still said "holds"; a second acquisition by a listed holder produced a "no recorded holder" count); fixed by the "unsettled until the next snapshot" rule above, approved by the coordinator as honest by construction, with no further plan rounds.

## File structure

| File | Change | Responsibility |
|---|---|---|
| `src/components/SessionMetrics.tsx` (+ test) | modify | tiles as `<dl>` with a gap |
| `docs/superpowers/specs/2026-11-05-session-dock-design.md` | modify | S-3 amendment, ownership row |
| `src/lib/projections/project-sync.ts` (+ test) | create | pure mutex/semaphore reducer, in seq order; holder permit counts; unknown-holder permits; late release; permit operations |
| `src/lib/projections/in-seq-order.ts` | create | events in seq order, without copying ordered input |
| `src/hooks/use-sync-events.ts` (+ test) | modify | thin hook over `projectSync`; optional `events` |
| `src/hooks/use-channel-events.ts`, `use-flow-events.ts`, `use-job-events.ts` (+ tests) | modify | optional `events` |
| `src/lib/projections/project-waits.ts` (+ test) | modify | accepts a readonly list; reduces in seq order |
| `src/lib/selection.ts` (+ test) | create | `Selection`, what an event is about, which events concern a selection, resource names and ids, a row's title, `namesCoroutine` |
| `src/lib/activity.ts` (+ test) | create | the second line per coroutine; `ordinal` |
| `src/components/CoroutineTreeGraph.tsx` (+ test) | modify | second line on the card; card 240 px |
| `src/components/CoroutineTree.tsx` (+ test) | modify | second line in the list |
| `src/components/workspace/CoroutineCanvas.tsx` (+ test) | modify | passes `activity` through |
| `src/components/sync/CoroutineName.tsx` | create | a coroutine's name as a select button when the page can select it, marked when selected; `CoroutinePicker` |
| `src/components/sync/SyncPanel.tsx`, `MutexStateIndicator.tsx`, `SemaphoreGauge.tsx`, `WaitQueueList.tsx` (+ test) | modify | events in; selected resource ringed and scrolled to; names select; holders with permit counts and "holder unknown" |
| `src/components/channels/ChannelPanel.tsx`, `flow/FlowPanel.tsx`, `jobs/JobPanel.tsx` (+ tests) | modify | events in; ChannelPanel shows the page's channel and selects on the page |
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

- [ ] **Step 1: Write the failing tests** — `src/lib/projections/project-sync.test.ts`:

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
    const { semaphores } = projectSync([
      created,
      stateChanged({ activeHolders: ['c-1'], activeHolderLabels: ['order-1'], unknownHolderPermits: 1 }),
    ])
    expect(semaphores[0]!.unknownHolderPermits).toBe(1)
  })

  it('tracks the lock holder and the latest queue of a mutex', () => {
    const { mutexes } = projectSync([
      event('MutexCreated', { mutexId: 'm-1', mutexLabel: 'inventory-lock', ownerCoroutineId: null }),
      event('MutexLockAcquired', { mutexId: 'm-1', mutexLabel: 'inventory-lock', acquirerId: 'c-1', acquirerLabel: 'order-1', waitDurationNanos: 0 }),
      event('MutexQueueChanged', { mutexId: 'm-1', mutexLabel: 'inventory-lock', waitingCoroutineIds: ['c-2', 'c-3'], waitingLabels: ['order-2', 'order-3'] }),
    ])
    expect(mutexes[0]).toMatchObject({
      isLocked: true,
      currentHolder: 'c-1',
      waitQueue: [{ id: 'c-2', label: 'order-2' }, { id: 'c-3', label: 'order-3' }],
    })
  })

  describe('a lock handed over', () => {
    const m = { mutexId: 'm-1', mutexLabel: 'inventory-lock' }
    const acquired = (who: string) => event('MutexLockAcquired', { ...m, acquirerId: who, acquirerLabel: who, waitDurationNanos: 0 })
    const unlocked = (who: string) => event('MutexUnlocked', { ...m, releaserId: who, releaserLabel: who, nextWaiterId: null, holdDurationNanos: 0 })

    it('the ordinary handoff: released, then acquired by the next', () => {
      const [mutex] = projectSync([acquired('c-1'), unlocked('c-1'), acquired('c-2')]).mutexes
      expect(mutex).toMatchObject({ isLocked: true, currentHolder: 'c-2' })
    })

    it('the previous holder\'s release recorded after the next acquisition does not free the lock', () => {
      // VizMutex unlocks before it records the release, so the waiter it woke can record its acquisition first.
      const [mutex] = projectSync([acquired('c-1'), acquired('c-2'), unlocked('c-1')]).mutexes
      expect(mutex).toMatchObject({ isLocked: true, currentHolder: 'c-2' })
    })

    it('several handovers recorded before their releases leave the last holder holding', () => {
      const events = [acquired('c-1'), acquired('c-2'), acquired('c-3'), unlocked('c-1'), unlocked('c-2')]
      expect(projectSync(events).mutexes[0]).toMatchObject({ isLocked: true, currentHolder: 'c-3' })
      expect(projectSync([...events, unlocked('c-3')]).mutexes[0]).toMatchObject({ isLocked: false })
    })

    it('a coroutine that acquires again is no longer waiting to record an old release', () => {
      // c-1's release after the handover was never recorded; when c-1 holds the lock again, its release frees it.
      const events = [acquired('c-1'), acquired('c-2'), unlocked('c-2'), acquired('c-1'), unlocked('c-1')]
      expect(projectSync(events).mutexes[0]).toMatchObject({ isLocked: false, currentHolder: null })
    })

    it('a release by some other coroutine frees it', () => {
      const [mutex] = projectSync([acquired('c-1'), unlocked('c-9')]).mutexes
      expect(mutex).toMatchObject({ isLocked: false, currentHolder: null })
    })
  })

  it('reduces in seq order whatever order the events arrived in', () => {
    const m = { mutexId: 'm-1', mutexLabel: 'inventory-lock' }
    const a = event('MutexLockAcquired', { ...m, acquirerId: 'c-1', acquirerLabel: 'c-1', waitDurationNanos: 0 })
    const u = event('MutexUnlocked', { ...m, releaserId: 'c-1', releaserLabel: 'c-1', nextWaiterId: null, holdDurationNanos: 0 })
    const arrived = [u, a]
    expect(projectSync(arrived).mutexes[0]).toMatchObject({ isLocked: false })
    expect(arrived).toEqual([u, a])
  })

  describe('permits between two state snapshots', () => {
    // A snapshot is the only source of permit counts. An acquire or release between two snapshots only lists
    // an acquirer not listed yet and unsettles the list: whether a snapshot already counted an operation cannot be told
    // from the totals (VizSemaphore records a holder before the acquisition event, and drops it before the
    // release event; a semaphore can start with permits taken).
    const s = { semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway' }
    const acquired = (who: string, remainingPermits = 1) =>
      event('SemaphorePermitAcquired', { ...s, acquirerId: who, acquirerLabel: who, remainingPermits, waitDurationNanos: 0 })
    const released = (who: string, holderUnknown = false, newAvailablePermits = 2) =>
      event('SemaphorePermitReleased', { ...s, releaserId: who, releaserLabel: who, newAvailablePermits, holdDurationNanos: 0, holderUnknown })

    it('an acquirer not listed yet is listed, the list is unsettled, and it leaves the queue', () => {
      const queued = stateChanged({ activeHolders: [], activeHolderLabels: [], waitingCoroutines: ['c-1'], waitingLabels: ['c-1'] })
      const [sem] = projectSync([created, queued, acquired('c-1')]).semaphores
      expect(sem!.activeHolders).toEqual([{ id: 'c-1', label: 'c-1', permits: 1 }])
      expect(sem!.unsettled).toBe(true)
      expect(sem!.waitQueue).toEqual([])
    })

    it('a release while A and B are listed unsettles the list: who still holds is unknown', () => {
      const [sem] = projectSync([
        created,
        stateChanged({ availablePermits: 1, activeHolderPermits: [1, 1] }),
        released('c-1', false, 2),
      ]).semaphores
      expect(sem!.unsettled).toBe(true)
    })

    it('a snapshot settles the list again', () => {
      expect(projectSync([created]).semaphores[0]!.unsettled).toBe(false)
      const [sem] = projectSync([created, acquired('c-1'), stateChanged({ availablePermits: 1, activeHolderPermits: [1, 1] })]).semaphores
      expect(sem!.unsettled).toBe(false)
    })

    it('a listed acquirer keeps its count: the snapshot may have counted this permit already', () => {
      // Three permits, one taken at creation: the snapshot lists c-1 and c-2 with one each, then c-2's own
      // acquisition event arrives. Its count stays one.
      const [sem] = projectSync([
        created,
        stateChanged({ availablePermits: 0, activeHolderPermits: [1, 1] }),
        acquired('c-2', 0),
      ]).semaphores
      expect(sem!.activeHolders).toEqual([
        { id: 'c-1', label: 'order-1', permits: 1 },
        { id: 'c-2', label: 'order-2', permits: 1 },
      ])
    })

    it('a release changes no counts until the next snapshot', () => {
      const [sem] = projectSync([
        created,
        stateChanged({ availablePermits: 2, activeHolderPermits: [1, 1], unknownHolderPermits: 1 }),
        released('c-1', false, 2),
      ]).semaphores
      expect(sem!.activeHolders.map(h => h.id)).toEqual(['c-1', 'c-2'])
      expect(sem!.unknownHolderPermits).toBe(1)
    })

    it('a release by a coroutine that held none changes no counts either', () => {
      const [sem] = projectSync([
        created,
        stateChanged({ availablePermits: 1, activeHolderPermits: [1, 1] }),
        released('c-9', true, 2),
      ]).semaphores
      expect(sem!.activeHolders.map(h => h.permits)).toEqual([1, 1])
      expect(sem!.unknownHolderPermits).toBe(0)
    })

    it('when a release frees every permit, nobody holds one', () => {
      const two = event('SemaphoreCreated', { ...s, totalPermits: 2 })
      const [sem] = projectSync([two, acquired('c-1', 1), released('c-9', true, 2)]).semaphores
      expect(sem!.activeHolders).toEqual([])
      expect(sem!.unknownHolderPermits).toBe(0)
    })

    it('a snapshot with every permit free means nobody holds one, whatever it lists', () => {
      // VizSemaphore copies the holders before it reads the free permits; a release can fall between.
      const [sem] = projectSync([
        created,
        stateChanged({ availablePermits: 3, activeHolders: ['c-1'], activeHolderLabels: ['c-1'], activeHolderPermits: [1], unknownHolderPermits: 1 }),
      ]).semaphores
      expect(sem!.activeHolders).toEqual([])
      expect(sem!.unknownHolderPermits).toBe(0)
    })

    it('the next snapshot confirms or replaces what the operations listed', () => {
      const [sem] = projectSync([
        created,
        acquired('c-1'),
        stateChanged({ availablePermits: 2, activeHolders: ['c-1'], activeHolderLabels: ['c-1'], activeHolderPermits: [1] }),
      ]).semaphores
      expect(sem!.activeHolders).toEqual([{ id: 'c-1', label: 'c-1', permits: 1 }])
      expect(sem!.unsettled).toBe(false)
    })
  })
})
```

In `use-sync-events.test.ts`, the holders gain `permits: 1` in the "updates semaphore state" expectation, and a new test:

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

`project-waits.test.ts` "keeps each coroutine apart…" builds its events in seq order (it built the later suspension first and relied on array order).

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm exec vitest run src/lib/projections/project-sync.test.ts src/hooks/use-sync-events.test.ts`
Expected: FAIL — `Failed to resolve import "./project-sync"`; the hook tests fail on `permits` and on the `live` list being ignored.

- [ ] **Step 3: Implement.** `src/lib/projections/in-seq-order.ts`:

```ts
import type { VizEvent } from '@/types/api'

/**
 * The events in seq order. The live stream appends events as they arrive, and accepts one whose seq is lower
 * than one already held (use-event-stream.ts); the projections reduce in order, so an earlier event that
 * arrived late must not undo a later one (a suspension after its own resume). Returns the same array, not a
 * copy, when it is already in order — the usual case — and never sorts the caller's array in place.
 */
export function inSeqOrder(events: readonly VizEvent[]): readonly VizEvent[] {
  for (let i = 1; i < events.length; i++) {
    if (events[i]!.seq < events[i - 1]!.seq) return [...events].sort((a, b) => a.seq - b.seq)
  }
  return events
}
```

`src/lib/projections/project-sync.ts` (today's reducer from `use-sync-events.ts`, moved, with the changes the header names):

```ts
/**
 * Pure reducer: VizEvent[] -> what each mutex and semaphore looks like after these events (spec S-6).
 *
 * Moved out of useSyncEvents so the Evidence section and the tree's second line read one derivation, from
 * whichever events the page shows: the live stream's, the stored history's, or the replay cursor's.
 * Queues are the latest observed queue. Permits are counted per holder, never numbered; permits that came
 * back from a coroutine that held none have no known holder (S-7) and are counted apart.
 *
 * Reduced in seq order, whatever order the events arrived in. VizMutex unlocks before it records the release,
 * so the waiter it woke can record its acquisition first — several times in a row — and a displaced holder's
 * late release must not free the lock. A semaphore's state snapshot is the only source of permit counts:
 * VizSemaphore records a holder before the acquisition event and drops it before the release event, and a
 * semaphore can start with permits taken, so whether a snapshot already counted an operation cannot be told
 * from the totals. An acquire or release between two snapshots (a stream flush or a replay cursor can fall
 * there) therefore only lists an acquirer not listed yet and changes no count, and marks the list unsettled:
 * no holder is named for certain until the next snapshot replaces the list. With every permit free, nobody
 * holds one.
 */
import { SYNC_EVENT_KINDS } from '@/types/api'
import { inSeqOrder } from './in-seq-order'
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
  /**
   * Listed permits beyond those in use (S-7): a permit came back from a coroutine that held none, so some
   * listed holder no longer holds one, and which is unknown. Not permits held by nobody known.
   */
  unknownHolderPermits: number
  /**
   * An acquire or release was recorded since the last state snapshot: who holds what is not settled (whether
   * that snapshot already counted the operation cannot be told), so no holder is named for certain and no
   * count is trusted until the next snapshot.
   */
  unsettled: boolean
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
  const syncEvents: SyncEvent[] = []
  const mutexStates = new Map<string, MutexState>()
  const semaphoreStates = new Map<string, SemaphoreState>()
  const deadlocks: DeadlockDetected[] = []
  const warnings: PotentialDeadlockWarning[] = []

  if (!events || events.length === 0) {
    return {
      syncEvents: [],
      mutexes: [],
      semaphores: [],
      deadlocks: [],
      warnings: [],
      hasDeadlock: false,
    }
  }

  // Per lock, holders displaced by a later acquisition whose own release is still to be recorded: VizMutex
  // records a release after the lock has passed on. A multiset, as several handovers can come first.
  const pendingReleases = new Map<string, string[]>()

  for (const event of inSeqOrder(events)) {
    if (!SYNC_EVENT_KINDS.has(event.kind)) continue

    const syncEvent = event as unknown as SyncEvent
    syncEvents.push(syncEvent)

    switch (syncEvent.kind) {
      case 'MutexCreated': {
        const e = syncEvent as MutexCreated
        mutexStates.set(e.mutexId, {
          mutexId: e.mutexId,
          label: e.mutexLabel,
          isLocked: false,
          currentHolder: e.ownerCoroutineId,
          currentHolderLabel: null,
          waitQueue: [],
          contentionCount: 0,
          lockAcquisitions: 0,
        })
        break
      }
      case 'MutexLockRequested': {
        const e = syncEvent as MutexLockRequested
        ensureMutex(mutexStates, e.mutexId, e.mutexLabel)
        const state = mutexStates.get(e.mutexId)!
        if (e.isLocked) {
          state.contentionCount++
        }
        break
      }
      case 'MutexLockAcquired': {
        const e = syncEvent as MutexLockAcquired
        ensureMutex(mutexStates, e.mutexId, e.mutexLabel)
        const state = mutexStates.get(e.mutexId)!
        // A coroutine records its release before it can acquire again: none of its own is pending any more.
        const pending = (pendingReleases.get(e.mutexId) ?? []).filter(id => id !== e.acquirerId)
        if (state.isLocked && state.currentHolder && state.currentHolder !== e.acquirerId) pending.push(state.currentHolder)
        pendingReleases.set(e.mutexId, pending)
        state.isLocked = true
        state.currentHolder = e.acquirerId
        state.currentHolderLabel = e.acquirerLabel
        state.lockAcquisitions++
        // Remove from wait queue
        state.waitQueue = state.waitQueue.filter((w) => w.id !== e.acquirerId)
        break
      }
      case 'MutexUnlocked': {
        const e = syncEvent as MutexUnlocked
        ensureMutex(mutexStates, e.mutexId, e.mutexLabel)
        const state = mutexStates.get(e.mutexId)!
        // A displaced holder's release, recorded after the next acquisition: the lock is not free.
        const pending = pendingReleases.get(e.mutexId) ?? []
        const late = pending.indexOf(e.releaserId)
        if (late >= 0) {
          pendingReleases.set(e.mutexId, pending.filter((_, i) => i !== late))
          break
        }
        state.isLocked = false
        state.currentHolder = null
        state.currentHolderLabel = null
        break
      }
      case 'MutexTryLockFailed': {
        const e = syncEvent as MutexTryLockFailed
        ensureMutex(mutexStates, e.mutexId, e.mutexLabel)
        mutexStates.get(e.mutexId)!.contentionCount++
        break
      }
      case 'MutexQueueChanged': {
        const e = syncEvent as MutexQueueChanged
        ensureMutex(mutexStates, e.mutexId, e.mutexLabel)
        const state = mutexStates.get(e.mutexId)!
        state.waitQueue = e.waitingCoroutineIds.map((id, i) => ({
          id,
          label: e.waitingLabels[i] ?? null,
        }))
        break
      }
      case 'SemaphoreCreated': {
        const e = syncEvent as SemaphoreCreated
        semaphoreStates.set(e.semaphoreId, {
          semaphoreId: e.semaphoreId,
          label: e.semaphoreLabel,
          totalPermits: e.totalPermits,
          availablePermits: e.totalPermits,
          activeHolders: [],
          unknownHolderPermits: 0,
          unsettled: false,
          waitQueue: [],
        })
        break
      }
      case 'SemaphoreAcquireRequested': {
        const e = syncEvent as SemaphoreAcquireRequested
        ensureSemaphore(semaphoreStates, e.semaphoreId, e.semaphoreLabel)
        break
      }
      case 'SemaphorePermitAcquired': {
        const e = syncEvent as SemaphorePermitAcquired
        ensureSemaphore(semaphoreStates, e.semaphoreId, e.semaphoreLabel)
        const state = semaphoreStates.get(e.semaphoreId)!
        state.availablePermits = e.remainingPermits
        // An acquirer not listed yet is listed; no count changes (a snapshot may already have counted this
        // permit), and the list is unsettled until the next snapshot replaces it.
        if (!state.activeHolders.some(h => h.id === e.acquirerId)) {
          state.activeHolders = [...state.activeHolders, { id: e.acquirerId, label: e.acquirerLabel, permits: 1 }]
        }
        state.unsettled = true
        state.waitQueue = state.waitQueue.filter(w => w.id !== e.acquirerId)
        clearWhenAllFree(state)
        break
      }
      case 'SemaphorePermitReleased': {
        const e = syncEvent as SemaphorePermitReleased
        ensureSemaphore(semaphoreStates, e.semaphoreId, e.semaphoreLabel)
        const state = semaphoreStates.get(e.semaphoreId)!
        state.availablePermits = e.newAvailablePermits
        // No count changes (a snapshot may already have taken this permit back): the list is unsettled until
        // the next snapshot. Only a release that frees every permit is certain: then nobody holds one.
        state.unsettled = true
        clearWhenAllFree(state)
        break
      }
      case 'SemaphoreTryAcquireFailed': {
        const e = syncEvent as SemaphoreTryAcquireFailed
        ensureSemaphore(semaphoreStates, e.semaphoreId, e.semaphoreLabel)
        break
      }
      case 'SemaphoreStateChanged': {
        const e = syncEvent as SemaphoreStateChanged
        ensureSemaphore(semaphoreStates, e.semaphoreId, e.semaphoreLabel)
        const state = semaphoreStates.get(e.semaphoreId)!
        state.availablePermits = e.availablePermits
        state.totalPermits = e.totalPermits
        state.activeHolders = e.activeHolders.map((id, i) => ({
          id,
          label: e.activeHolderLabels[i] ?? null,
          // An older producer sends no counts: each listed holder held one.
          permits: e.activeHolderPermits?.[i] ?? 1,
        }))
        state.unknownHolderPermits = e.unknownHolderPermits ?? 0
        state.unsettled = false
        clearWhenAllFree(state)
        state.waitQueue = e.waitingCoroutines.map((id, i) => ({
          id,
          label: e.waitingLabels[i] ?? null,
        }))
        break
      }
      case 'DeadlockDetected': {
        deadlocks.push(syncEvent as DeadlockDetected)
        break
      }
      case 'PotentialDeadlockWarning': {
        warnings.push(syncEvent as PotentialDeadlockWarning)
        break
      }
    }
  }

  return {
    syncEvents,
    mutexes: Array.from(mutexStates.values()),
    semaphores: Array.from(semaphoreStates.values()),
    deadlocks,
    warnings,
    hasDeadlock: deadlocks.length > 0,
  }
}

/**
 * With every permit free, nobody holds one, whatever was listed (a snapshot copies its holders before it reads
 * the free permits, so a release can fall between). Needs the total, which a trimmed history may lack
 * (`SemaphoreCreated` gone): then the list stands until the next snapshot.
 */
function clearWhenAllFree(state: SemaphoreState) {
  if (state.totalPermits > 0 && state.availablePermits >= state.totalPermits) {
    state.activeHolders = []
    state.unknownHolderPermits = 0
    state.unsettled = false
  }
}

function ensureMutex(map: Map<string, MutexState>, id: string, label: string | null) {
  if (!map.has(id)) {
    map.set(id, {
      mutexId: id,
      label,
      isLocked: false,
      currentHolder: null,
      currentHolderLabel: null,
      waitQueue: [],
      contentionCount: 0,
      lockAcquisitions: 0,
    })
  }
}

function ensureSemaphore(map: Map<string, SemaphoreState>, id: string, label: string | null) {
  if (!map.has(id)) {
    map.set(id, {
      semaphoreId: id,
      label,
      totalPermits: 0,
      availablePermits: 0,
      activeHolders: [],
      unknownHolderPermits: 0,
      unsettled: false,
      waitQueue: [],
    })
  }
}
```

`src/hooks/use-sync-events.ts`:

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

`project-waits.ts` reduces `inSeqOrder(events)` and takes a readonly list:

```diff
diff --git a/frontend/src/lib/projections/project-waits.ts b/frontend/src/lib/projections/project-waits.ts
index 406640dd..17a86d84 100644
--- a/frontend/src/lib/projections/project-waits.ts
+++ b/frontend/src/lib/projections/project-waits.ts
@@ -10,6 +10,7 @@
  */
 
 import type { CoroutineSuspendedEvent, VizEvent, WaitKind } from '@/types/api'
+import { inSeqOrder } from './in-seq-order'
 
 export interface WaitFact {
   waitKind: WaitKind
@@ -33,10 +34,11 @@ const ENDS_WAIT: ReadonlySet<string> = new Set([
  * @param events - VizEvents in seq order (typically the replay `visibleEvents` slice)
  * @returns coroutineId -> its current wait, for coroutines waiting on a wrapper now
  */
-export function projectWaits(events: VizEvent[]): Map<string, WaitFact> {
+export function projectWaits(events: readonly VizEvent[]): Map<string, WaitFact> {
   const waits = new Map<string, WaitFact>()
 
-  for (const event of events) {
+  // In seq order: a suspension that arrived after its own resume is over (inSeqOrder).
+  for (const event of inSeqOrder(events)) {
     if (!ENDS_WAIT.has(event.kind) || !('coroutineId' in event)) continue
     waits.delete(event.coroutineId)
 
```

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

- [ ] **Step 3: Implement** — each hook reads `given ?? stored`, in seq order; `useEventCategories` gains the pure `categorizeEvents` (Task 13 uses it in replay). Each test file also gains a "reduces in seq order, whatever order the events arrived in" test.

```diff
diff --git a/frontend/src/hooks/use-channel-events.ts b/frontend/src/hooks/use-channel-events.ts
index 06c92475..c9d7e8b7 100644
--- a/frontend/src/hooks/use-channel-events.ts
+++ b/frontend/src/hooks/use-channel-events.ts
@@ -1,5 +1,7 @@
 import { useMemo } from 'react'
 import { useSessionEvents } from '@/hooks/use-sessions'
+import { inSeqOrder } from '@/lib/projections/in-seq-order'
+import type { VizEvent } from '@/types/api'
 import { CHANNEL_EVENT_KINDS } from '@/types/api'
 import type {
   ChannelCreated,
@@ -43,9 +45,14 @@ export interface UseChannelEventsResult {
 /**
  * Filters session events to only channel events,
  * groups by channelId, and tracks per-channel state.
+ *
+ * Reads `given` when passed — the events the page shows, so the facts follow
+ * the live stream and the replay cursor — else the stored history, which live
+ * updates never invalidate. It subscribes to that history either way (#124).
  */
-export function useChannelEvents(sessionId: string): UseChannelEventsResult {
-  const { data: events } = useSessionEvents(sessionId)
+export function useChannelEvents(sessionId: string, given?: readonly VizEvent[]): UseChannelEventsResult {
+  const { data: stored } = useSessionEvents(sessionId)
+  const events = given ?? stored
 
   return useMemo(() => {
     const channelEvents: ChannelEvent[] = []
@@ -62,7 +69,8 @@ export function useChannelEvents(sessionId: string): UseChannelEventsResult {
       }
     }
 
-    for (const event of events) {
+    // In seq order: the live stream can deliver an earlier event late (inSeqOrder).
+    for (const event of inSeqOrder(events)) {
       if (!CHANNEL_EVENT_KINDS.has(event.kind)) continue
 
       const channelEvent = event as unknown as ChannelEvent
diff --git a/frontend/src/hooks/use-event-categories.ts b/frontend/src/hooks/use-event-categories.ts
index c2724301..467b3a5d 100644
--- a/frontend/src/hooks/use-event-categories.ts
+++ b/frontend/src/hooks/use-event-categories.ts
@@ -32,43 +32,42 @@ export function useEventCategories(
 ): EventCategories {
   const { data: stored } = useSessionEvents(sessionId)
 
-  return useMemo(() => {
-    const result: EventCategories = {
-      hasChannels: false,
-      hasFlowOps: false,
-      hasSyncPrimitives: false,
-      hasJobs: false,
-      hasValidation: true, // Validation tab is always available
-    }
+  return useMemo(() => categorizeEvents(stored, liveEvents), [stored, liveEvents])
+}
 
-    for (const event of eventsOf(stored, liveEvents)) {
-      const kind = event.kind
-      if (!result.hasChannels && CHANNEL_EVENT_KINDS.has(kind)) {
-        result.hasChannels = true
-      }
-      if (!result.hasFlowOps && FLOW_EVENT_KINDS.has(kind)) {
-        result.hasFlowOps = true
-      }
-      if (!result.hasSyncPrimitives && SYNC_EVENT_KINDS.has(kind)) {
-        result.hasSyncPrimitives = true
-      }
-      if (!result.hasJobs && JOB_EVENT_KINDS.has(kind)) {
-        result.hasJobs = true
-      }
-      // Early exit if all categories found
-      if (result.hasChannels && result.hasFlowOps && result.hasSyncPrimitives && result.hasJobs) {
-        break
-      }
-    }
+/**
+ * Which event categories the given lists hold. Pure: the replay view runs it
+ * over the cursor's events, so Evidence never reports what the cursor has not
+ * reached yet.
+ */
+export function categorizeEvents(...lists: ReadonlyArray<readonly VizEvent[] | undefined>): EventCategories {
+  const result: EventCategories = {
+    hasChannels: false,
+    hasFlowOps: false,
+    hasSyncPrimitives: false,
+    hasJobs: false,
+    hasValidation: true, // Validation tab is always available
+  }
 
-    return result
-  }, [stored, liveEvents])
-}
+  for (const list of lists) for (const event of list ?? []) {
+    const kind = event.kind
+    if (!result.hasChannels && CHANNEL_EVENT_KINDS.has(kind)) {
+      result.hasChannels = true
+    }
+    if (!result.hasFlowOps && FLOW_EVENT_KINDS.has(kind)) {
+      result.hasFlowOps = true
+    }
+    if (!result.hasSyncPrimitives && SYNC_EVENT_KINDS.has(kind)) {
+      result.hasSyncPrimitives = true
+    }
+    if (!result.hasJobs && JOB_EVENT_KINDS.has(kind)) {
+      result.hasJobs = true
+    }
+    // Early exit if all categories found
+    if (result.hasChannels && result.hasFlowOps && result.hasSyncPrimitives && result.hasJobs) {
+      return result
+    }
+  }
 
-function* eventsOf(
-  stored: readonly VizEvent[] | undefined,
-  live: readonly VizEvent[] | undefined,
-): Generator<VizEvent> {
-  if (stored) yield* stored
-  if (live) yield* live
+  return result
 }
diff --git a/frontend/src/hooks/use-flow-events.ts b/frontend/src/hooks/use-flow-events.ts
index 18248100..e2d202b9 100644
--- a/frontend/src/hooks/use-flow-events.ts
+++ b/frontend/src/hooks/use-flow-events.ts
@@ -1,5 +1,7 @@
 import { useMemo } from 'react'
 import { useSessionEvents } from '@/hooks/use-sessions'
+import { inSeqOrder } from '@/lib/projections/in-seq-order'
+import type { VizEvent } from '@/types/api'
 import { FLOW_EVENT_KINDS } from '@/types/api'
 import type {
   FlowCreated,
@@ -60,9 +62,14 @@ export interface UseFlowEventsResult {
 /**
  * Filters session events to flow events, groups by flowId,
  * and tracks operator chains, backpressure, and value traces.
+ *
+ * Reads `given` when passed — the events the page shows, so the facts follow
+ * the live stream and the replay cursor — else the stored history, which live
+ * updates never invalidate. It subscribes to that history either way (#124).
  */
-export function useFlowEvents(sessionId: string): UseFlowEventsResult {
-  const { data: events } = useSessionEvents(sessionId)
+export function useFlowEvents(sessionId: string, given?: readonly VizEvent[]): UseFlowEventsResult {
+  const { data: stored } = useSessionEvents(sessionId)
+  const events = given ?? stored
 
   return useMemo(() => {
     const flowEvents: FlowOperatorEvent[] = []
@@ -79,7 +86,8 @@ export function useFlowEvents(sessionId: string): UseFlowEventsResult {
       }
     }
 
-    for (const event of events) {
+    // In seq order: the live stream can deliver an earlier event late (inSeqOrder).
+    for (const event of inSeqOrder(events)) {
       if (!FLOW_EVENT_KINDS.has(event.kind)) continue
 
       const flowEvent = event as unknown as FlowOperatorEvent
diff --git a/frontend/src/hooks/use-job-events.ts b/frontend/src/hooks/use-job-events.ts
index 863cd042..69bfca1c 100644
--- a/frontend/src/hooks/use-job-events.ts
+++ b/frontend/src/hooks/use-job-events.ts
@@ -1,5 +1,6 @@
 import { useMemo } from 'react'
 import { useSessionEvents } from '@/hooks/use-sessions'
+import { inSeqOrder } from '@/lib/projections/in-seq-order'
 import { JOB_EVENT_KINDS } from '@/types/api'
 import type {
   JobStateChangedEvent,
@@ -52,9 +53,14 @@ function deriveStatus(isActive: boolean, isCompleted: boolean, isCancelled: bool
 /**
  * Filters session events to job events, tracks job states,
  * parent-child relationships, and waiting-for-children blocking.
+ *
+ * Reads `given` when passed — the events the page shows, so the facts follow
+ * the live stream and the replay cursor — else the stored history, which live
+ * updates never invalidate. It subscribes to that history either way (#124).
  */
-export function useJobEvents(sessionId: string): UseJobEventsResult {
-  const { data: events } = useSessionEvents(sessionId)
+export function useJobEvents(sessionId: string, given?: readonly VizEvent[]): UseJobEventsResult {
+  const { data: stored } = useSessionEvents(sessionId)
+  const events = given ?? stored
 
   return useMemo(() => {
     const jobEvents: VizEvent[] = []
@@ -71,7 +77,8 @@ export function useJobEvents(sessionId: string): UseJobEventsResult {
       }
     }
 
-    for (const event of events) {
+    // In seq order: the live stream can deliver an earlier event late (inSeqOrder).
+    for (const event of inSeqOrder(events)) {
       if (!JOB_EVENT_KINDS.has(event.kind)) continue
 
       jobEvents.push(event)
```

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
import { eventConcerns, eventSubject, eventTitle, namesCoroutine, resourceIds, resourceNames } from './selection'

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

  it('names a channel event by its channel, from the page\'s names', () => {
    const names = new Map([['ch-1', 'orders']])
    expect(eventTitle(ev('ChannelCreated', { channelId: 'ch-1', name: 'orders' }))).toBe('orders')
    expect(eventTitle(ev('ChannelClosed', { channelId: 'ch-1', cause: null }), names)).toBe('orders')
    expect(eventTitle(ev('ChannelBufferStateChanged', { channelId: 'ch-1', currentSize: 1, capacity: 2 }))).toBe('ch-1')
    expect(eventTitle(ev('ChannelSendStarted', { channelId: 'ch-1', coroutineId: 'c-1' }), names)).toBe('c-1 · orders')
  })

  it('says "outside any coroutine" for a wrapper used outside one', () => {
    expect(eventTitle(ev('MutexUnlocked', { ...lock, releaserId: 'unknown', releaserLabel: null }))).toBe('outside any coroutine · inventory-lock')
  })
})

describe('namesCoroutine', () => {
  it('is false for the ids the wrappers write when there was no coroutine', () => {
    expect(namesCoroutine('c-1')).toBe(true)
    expect(namesCoroutine('unknown')).toBe(false)
    expect(namesCoroutine('unknown-123')).toBe(false)
    expect(namesCoroutine(null)).toBe(false)
  })
})

describe('resourceIds', () => {
  it('lists every lock, semaphore and channel the events name, labelled or not', () => {
    const ids = resourceIds([
      ev('MutexCreated', { mutexId: 'm-2', mutexLabel: null }),
      ev('SemaphoreStateChanged', { semaphoreId: 'sem-1' }),
      ev('ChannelClosed', { channelId: 'ch-1' }),
      ev('coroutine.started', { coroutineId: 'c-1' }),
    ])
    expect([...ids].sort()).toEqual(['ch-1', 'm-2', 'sem-1'])
  })

  it('counts a resource a retained suspension still waits for', () => {
    const ids = resourceIds([ev('coroutine.suspended', { coroutineId: 'c-1', waitKind: 'MUTEX', resourceId: 'm-9' })])
    expect([...ids]).toEqual(['m-9'])
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
export function namesCoroutine(id: string | null | undefined): id is string {
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

/**
 * Every lock, semaphore and channel the events name, a suspension waiting for one included (as
 * `eventConcerns` counts it): a selected resource no longer among them is gone.
 */
export function resourceIds(events: readonly VizEvent[]): Set<string> {
  const ids = new Set<string>()
  for (const event of events) {
    const f = fieldsOf(event)
    const id = f.mutexId ?? f.semaphoreId ?? f.channelId ?? f.resourceId
    if (id) ids.add(id)
  }
  return ids
}

/** A wrapper used outside any coroutine names no coroutine; the title says so rather than "unknown-123". */
const OUTSIDE = 'outside any coroutine'

/**
 * A row's title in the event list: its coroutine, and for lock, permit and channel events the resource too.
 * A channel is named from `names` (the page's resource names, `resourceNames`), else by its id.
 */
export function eventTitle(event: VizEvent, names: ReadonlyMap<string, string> = new Map()): string {
  const f = fieldsOf(event)
  const id = f.coroutineId ?? f.requesterId ?? f.acquirerId ?? f.releaserId
  const label = f.label ?? f.requesterLabel ?? f.acquirerLabel ?? f.releaserLabel
  const who = label ?? (id == null ? null : namesCoroutine(id) ? id : OUTSIDE)
  const channel = f.channelId ? (f.kind === 'ChannelCreated' && f.name ? f.name : (names.get(f.channelId) ?? f.channelId)) : null
  const what = f.mutexLabel ?? f.mutexId ?? f.semaphoreLabel ?? f.semaphoreId ?? channel
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

  it('a queue alone is no wait: the line needs the suspension the wrapper recorded', () => {
    // An older producer records no wait kind; and a waiter that has resumed can still be listed until the
    // next queue event. Neither may read as waiting.
    const lines = projectActivity([ev('MutexCreated', lock), queue(['c-2'])])
    expect(lines.has('c-2')).toBe(false)
    const resumed = projectActivity([ev('MutexCreated', lock), queue(['c-2']), waitFor('c-2', 'MUTEX', 'm-1'), ev('coroutine.resumed', { coroutineId: 'c-2' })])
    expect(resumed.has('c-2')).toBe(false)
  })

  it('a wait that arrived after its own resume is still over (seq order, not arrival order)', () => {
    const suspended = waitFor('c-2', 'MUTEX', 'm-1')
    const resumed = ev('coroutine.resumed', { coroutineId: 'c-2' })
    expect(projectActivity([resumed, suspended]).has('c-2')).toBe(false)
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

  it('when a permit came back from a coroutine that held none, its holder is unknown', () => {
    const lines = projectActivity([
      ev('SemaphoreCreated', { ...sem, totalPermits: 3 }),
      ev('SemaphoreStateChanged', {
        ...sem, availablePermits: 2, totalPermits: 3, activeHolders: ['c-1', 'c-2'], activeHolderLabels: ['c-1', 'c-2'],
        activeHolderPermits: [1, 1], waitingCoroutines: [], waitingLabels: [], unknownHolderPermits: 1,
      }),
    ])
    expect(lines.get('c-1')).toEqual({ text: 'may hold a payment-gateway permit · holder unknown', waiting: false })
    expect(lines.get('c-2')?.text).toBe('may hold a payment-gateway permit · holder unknown')
  })

  it('a release since the last snapshot leaves every listed holder "may hold": who released is not settled', () => {
    const lines = projectActivity([
      ev('SemaphoreCreated', { ...sem, totalPermits: 3 }),
      ev('SemaphoreStateChanged', {
        ...sem, availablePermits: 1, totalPermits: 3, activeHolders: ['c-1', 'c-2'], activeHolderLabels: ['c-1', 'c-2'],
        activeHolderPermits: [1, 1], waitingCoroutines: [], waitingLabels: [],
      }),
      ev('SemaphorePermitReleased', { ...sem, releaserId: 'c-1', releaserLabel: 'c-1', newAvailablePermits: 2, holdDurationNanos: 0 }),
    ])
    expect(lines.get('c-1')?.text).toBe('may hold a payment-gateway permit · holder unknown')
    expect(lines.get('c-2')?.text).toBe('may hold a payment-gateway permit · holder unknown')
  })

  it('a holder listed only by its acquisition, before a snapshot confirms it, may hold the permit', () => {
    const lines = projectActivity([
      ev('SemaphoreCreated', { ...sem, totalPermits: 3 }),
      ev('SemaphorePermitAcquired', { ...sem, acquirerId: 'c-1', acquirerLabel: 'c-1', remainingPermits: 2, waitDurationNanos: 0 }),
    ])
    expect(lines.get('c-1')).toEqual({ text: 'may hold a payment-gateway permit · holder unknown', waiting: false })
  })

  it('no one holds a permit when a snapshot has every permit free', () => {
    const lines = projectActivity([
      ev('SemaphoreCreated', { ...sem, totalPermits: 3 }),
      ev('SemaphoreStateChanged', {
        ...sem, availablePermits: 3, totalPermits: 3, activeHolders: ['c-1'], activeHolderLabels: ['c-1'],
        activeHolderPermits: [1], waitingCoroutines: [], waitingLabels: [], unknownHolderPermits: 1,
      }),
    ])
    expect(lines.has('c-1')).toBe(false)
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
 * A wait is the suspension a wrapper recorded with its kind and resource (S-7, `projectWaits`), until the
 * coroutine's next resume, suspension or end. A queue alone is not taken for a wait: a waiter that has resumed
 * stays listed until the next queue event, and an older producer records no wait kind — no line is better
 * than a wrong one. The place in the queue is the latest observed one. Permits are counted, never numbered;
 * when a permit came back from a coroutine that held none, or an acquire or release since the last state
 * snapshot left the holders unsettled, which listed holder holds what is unknown, and the line says so. A wait is drawn in the Suspended colour; a hold alone is neutral, because a held
 * lock is normal work.
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

  const holds = new Map<string, string[]>()
  const hold = (coroutineId: string, text: string) => holds.set(coroutineId, [...(holds.get(coroutineId) ?? []), text])
  for (const m of sync.mutexes) {
    if (m.isLocked && m.currentHolder) hold(m.currentHolder, `holds ${nameOf(m.mutexId)}`)
  }
  for (const s of sync.semaphores) {
    const name = nameOf(s.semaphoreId)
    // A permit came back from a coroutine that held none (S-7), or an acquire or release since the last
    // snapshot left the list unsettled: which listed holder holds what is unknown, so none is named for certain.
    const ambiguous = s.unknownHolderPermits > 0 || s.unsettled
    for (const h of s.activeHolders) {
      const permits = h.permits === 1 ? aPermit(name) : `${h.permits} ${name} permits`
      hold(h.id, ambiguous ? `may hold ${permits} · holder unknown` : `holds ${permits}`)
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

- [ ] **Step 1: Write the failing tests** — append to `SyncPanel.test.tsx` (import `userEvent`; the semaphore fixture in the `SemaphoreGauge` test gains `unknownHolderPermits: 0`):

```tsx
describe('SyncPanel — live facts and one selection (S-2, S-6)', () => {
  const lockEvents = [
    makeSyncEvent('MutexCreated', 1, { mutexId: 'm-1', mutexLabel: 'inventory-lock', ownerCoroutineId: null }),
    makeSyncEvent('MutexLockAcquired', 2, { mutexId: 'm-1', mutexLabel: 'inventory-lock', acquirerId: 'c-1', acquirerLabel: 'order-1', waitDurationNanos: 0 }),
    makeSyncEvent('MutexQueueChanged', 3, { mutexId: 'm-1', mutexLabel: 'inventory-lock', waitingCoroutineIds: ['c-2'], waitingLabels: ['order-2'] }),
  ]
  const permits = (fields: Record<string, unknown>) => [
    makeSyncEvent('SemaphoreCreated', 4, { semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', totalPermits: 3 }),
    makeSyncEvent('SemaphoreStateChanged', 5, {
      semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', totalPermits: 3,
      activeHolders: ['c-3', 'c-4'], activeHolderLabels: ['order-3', 'order-4'], waitingCoroutines: [], waitingLabels: [],
      ...fields,
    }),
  ]
  const anyone = (select = vi.fn()) => ({ select, canSelect: () => true })

  beforeEach(() => {
    mockedApiClient.getSessionEvents.mockResolvedValue([])
  })

  it('reads the events it is given, so it follows the live stream', () => {
    render(<SyncPanel sessionId="session-1" events={lockEvents} />, { wrapper: createWrapper() })
    expect(screen.getByTestId('mutex-state')).toHaveTextContent('order-1')
  })

  it('lists a semaphore\'s holders with their permits', () => {
    render(<SyncPanel sessionId="session-1" events={permits({ availablePermits: 0, activeHolderPermits: [2, 1] })} />, {
      wrapper: createWrapper(),
    })
    const held = screen.getByTestId('semaphore-holders')
    expect(held).toHaveTextContent('order-3 (2 permits)')
    expect(held).toHaveTextContent('order-4')
    expect(held).not.toHaveTextContent('holder unknown')
  })

  it('says "holder unknown" when a permit came back from a coroutine that held none (S-6, S-7)', () => {
    // Two permits listed, one in use: one of the two was released by somebody else, and which is unknown.
    render(
      <SyncPanel sessionId="session-1" events={permits({ availablePermits: 2, activeHolderPermits: [1, 1], unknownHolderPermits: 1 })} />,
      { wrapper: createWrapper() },
    )
    const held = screen.getByTestId('semaphore-holders')
    expect(held).toHaveTextContent('holder unknown')
    expect(held).toHaveTextContent('1 of 3 permits in use')
    expect(held).toHaveTextContent('last recorded holders order-3, order-4')
  })

  it('says the holder is unknown when permits are in use with no holder recorded', () => {
    // A semaphore created with permits already taken (acquiredPermits) lists no holder for them.
    render(
      <SyncPanel sessionId="session-1" events={permits({ availablePermits: 0, activeHolders: [], activeHolderLabels: [] })} />,
      { wrapper: createWrapper() },
    )
    const held = screen.getByTestId('semaphore-holders')
    expect(held).toHaveTextContent('holder unknown')
    expect(held).toHaveTextContent('3 of 3 permits in use; no holder was recorded')
    expect(held).not.toHaveTextContent('none')
  })

  it('names the recorded holders and counts the permits no holder was recorded for', () => {
    render(
      <SyncPanel sessionId="session-1" events={permits({ availablePermits: 1, activeHolders: ['c-3'], activeHolderLabels: ['order-3'], activeHolderPermits: [1] })} />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByTestId('semaphore-holders')).toHaveTextContent('order-3, and 1 permit with no recorded holder')
  })

  it('after an acquire or release since the last snapshot, says "holder unknown" and counts nothing per holder', () => {
    // A acquires twice between snapshots: two permits in use, both acquisitions name order-3; no
    // "no recorded holder" count is guessed.
    const acquire = (seq: number, remainingPermits: number) =>
      makeSyncEvent('SemaphorePermitAcquired', seq, {
        semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', acquirerId: 'c-3', acquirerLabel: 'order-3', remainingPermits, waitDurationNanos: 0,
      })
    const events = [
      makeSyncEvent('SemaphoreCreated', 1, { semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', totalPermits: 3 }),
      makeSyncEvent('SemaphoreStateChanged', 2, {
        semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', totalPermits: 3, availablePermits: 3,
        activeHolders: [], activeHolderLabels: [], waitingCoroutines: [], waitingLabels: [],
      }),
      acquire(3, 2),
      acquire(4, 1),
    ]
    render(<SyncPanel sessionId="session-1" events={events} />, { wrapper: createWrapper() })
    const held = screen.getByTestId('semaphore-holders')
    expect(held).toHaveTextContent('holder unknown — 2 of 3 permits in use; last recorded holders order-3')
    expect(held).not.toHaveTextContent('no recorded holder')
    expect(held).not.toHaveTextContent('permits)')
  })

  it('selects a holder or a waiter by name', async () => {
    const select = vi.fn()
    render(<SyncPanel sessionId="session-1" events={lockEvents} picker={anyone(select)} />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: 'order-1' }))
    await userEvent.click(screen.getByRole('button', { name: 'order-2' }))
    expect(select.mock.calls).toEqual([['c-1'], ['c-2']])
  })

  it('offers no button for a coroutine the page cannot select, and names no coroutine for "unknown"', () => {
    const events = [
      makeSyncEvent('MutexCreated', 1, { mutexId: 'm-1', mutexLabel: 'inventory-lock', ownerCoroutineId: null }),
      makeSyncEvent('MutexLockAcquired', 2, { mutexId: 'm-1', mutexLabel: 'inventory-lock', acquirerId: 'unknown-42', acquirerLabel: null, waitDurationNanos: 0 }),
      makeSyncEvent('MutexQueueChanged', 3, { mutexId: 'm-1', mutexLabel: 'inventory-lock', waitingCoroutineIds: ['c-gone'], waitingLabels: ['gone'] }),
    ]
    render(
      <SyncPanel sessionId="session-1" events={events} picker={{ select: vi.fn(), canSelect: id => id === 'c-1' }} />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByTestId('mutex-state')).toHaveTextContent('outside any coroutine')
    expect(screen.getByText('gone')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'gone' })).toBeNull()
  })

  it('scrolls the selected resource into view, again when it is selected again', () => {
    const scrollIntoView = vi.fn()
    Element.prototype.scrollIntoView = scrollIntoView
    const wrapper = createWrapper()
    const { rerender } = render(
      <SyncPanel sessionId="session-1" events={lockEvents} selectedResourceId="m-1" revealKey={{}} />,
      { wrapper },
    )
    expect(scrollIntoView).toHaveBeenCalledTimes(1)
    expect(scrollIntoView.mock.contexts[0]).toBe(screen.getByTestId('resource-m-1'))
    rerender(<SyncPanel sessionId="session-1" events={lockEvents} selectedResourceId="m-1" revealKey={{}} />)
    expect(scrollIntoView).toHaveBeenCalledTimes(2)
  })

  it('marks the selected coroutine and rings the selected resource', () => {
    render(
      <SyncPanel
        sessionId="session-1"
        events={[...lockEvents, ...permits({ availablePermits: 0, activeHolderPermits: [2, 1] })]}
        selectedCoroutineId="c-1"
        selectedResourceId="sem-1"
        picker={anyone()}
      />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByRole('button', { name: 'order-1' })).toHaveAttribute('aria-current', 'true')
    expect(screen.getByRole('button', { name: 'order-2' })).not.toHaveAttribute('aria-current')
    expect(screen.getByTestId('resource-sem-1')).toHaveAttribute('data-selected', 'true')
    expect(screen.getByTestId('resource-m-1')).toHaveAttribute('data-selected', 'false')
  })
})
```

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm exec vitest run src/components/sync/SyncPanel.test.tsx`
Expected: FAIL — the stored (empty) history is read; no `semaphore-holders`; no name buttons.

- [ ] **Step 3: Implement.** `CoroutineName.tsx`:

```tsx
import { namesCoroutine } from '@/lib/selection'

/** How Locks and permits selects a coroutine by its name (S-2): only one the page can select. */
export interface CoroutinePicker {
  select: (id: string) => void
  /** False for a coroutine the session snapshot does not hold: selecting it would be cleared at once. */
  canSelect: (id: string) => boolean
}

interface CoroutineNameProps {
  id: string
  label: string | null
  /** Without it (replay, the shared view) the name is plain text. */
  picker?: CoroutinePicker
  /** It is the page's selection. */
  selected?: boolean
}

/**
 * A coroutine's name in the Locks and permits section: a button that selects it when the page can, marked
 * when selected. A wrapper used outside any coroutine records "unknown…": that names nobody, and says so.
 */
export function CoroutineName({ id, label, picker, selected = false }: CoroutineNameProps) {
  const name = label || (namesCoroutine(id) ? id : 'outside any coroutine')
  const marked = selected ? ' rounded-small bg-primary/10 px-1 font-semibold' : ''
  if (!picker || !namesCoroutine(id) || !picker.canSelect(id)) {
    return <span className={`font-mono${marked}`}>{name}</span>
  }
  return (
    <button
      type="button"
      aria-current={selected ? 'true' : undefined}
      onClick={() => picker.select(id)}
      className={`font-mono text-primary hover:underline${marked}`}
    >
      {name}
    </button>
  )
}
```

The other four files:

```diff
diff --git a/frontend/src/components/sync/MutexStateIndicator.tsx b/frontend/src/components/sync/MutexStateIndicator.tsx
index 08b432b2..754bb3de 100644
--- a/frontend/src/components/sync/MutexStateIndicator.tsx
+++ b/frontend/src/components/sync/MutexStateIndicator.tsx
@@ -1,12 +1,24 @@
 import { Card, CardBody, CardHeader, Chip } from '@heroui/react'
 import { motion } from 'framer-motion'
 import type { MutexState } from '@/hooks/use-sync-events'
+import { CoroutineName, type CoroutinePicker } from './CoroutineName'
 
 interface MutexStateIndicatorProps {
   mutex: MutexState
+  /** The page selected this lock (S-2): ringed. */
+  selected?: boolean
+  /** The page's selected coroutine, marked when it holds the lock. */
+  selectedCoroutineId?: string | null
+  /** Selects the holder, when the page can (S-2). */
+  picker?: CoroutinePicker
 }
 
-export function MutexStateIndicator({ mutex }: MutexStateIndicatorProps) {
+export function MutexStateIndicator({
+  mutex,
+  selected = false,
+  selectedCoroutineId = null,
+  picker,
+}: MutexStateIndicatorProps) {
   // A held lock is normal work, not an error (spec S-6): neutral grey, never red.
   return (
     <motion.div
@@ -28,7 +40,9 @@ export function MutexStateIndicator({ mutex }: MutexStateIndicatorProps) {
             }
       }
       transition={{ duration: 2, repeat: Infinity }}
-      className="rounded-xl"
+      data-testid={`resource-${mutex.mutexId}`}
+      data-selected={selected ? 'true' : 'false'}
+      className={selected ? 'rounded-xl ring-2 ring-primary' : 'rounded-xl'}
     >
       <Card shadow="sm" data-testid="mutex-state">
         <CardHeader className="pb-2">
@@ -59,10 +73,17 @@ export function MutexStateIndicator({ mutex }: MutexStateIndicatorProps) {
           <div className="grid grid-cols-3 gap-3 text-xs">
             <div>
               <div className="text-default-500">Holder</div>
-              <div className="font-mono font-semibold">
-                {mutex.currentHolder
-                  ? mutex.currentHolderLabel || mutex.currentHolder
-                  : 'None'}
+              <div className="font-semibold">
+                {mutex.currentHolder ? (
+                  <CoroutineName
+                    id={mutex.currentHolder}
+                    label={mutex.currentHolderLabel}
+                    picker={picker}
+                    selected={mutex.currentHolder === selectedCoroutineId}
+                  />
+                ) : (
+                  'None'
+                )}
               </div>
             </div>
             <div>
diff --git a/frontend/src/components/sync/SemaphoreGauge.tsx b/frontend/src/components/sync/SemaphoreGauge.tsx
index 017dabec..8cfe8b21 100644
--- a/frontend/src/components/sync/SemaphoreGauge.tsx
+++ b/frontend/src/components/sync/SemaphoreGauge.tsx
@@ -1,9 +1,16 @@
 import { Card, CardBody, CardHeader, Chip } from '@heroui/react'
 import { motion } from 'framer-motion'
 import type { SemaphoreState } from '@/hooks/use-sync-events'
+import { CoroutineName, type CoroutinePicker } from './CoroutineName'
 
 interface SemaphoreGaugeProps {
   semaphore: SemaphoreState
+  /** The page selected this semaphore (S-2): ringed. */
+  selected?: boolean
+  /** The page's selected coroutine, marked when it holds a permit. */
+  selectedCoroutineId?: string | null
+  /** Selects a holder, when the page can (S-2). */
+  picker?: CoroutinePicker
 }
 
 function getGaugeColor(available: number, total: number): 'success' | 'warning' | 'danger' {
@@ -14,11 +21,17 @@ function getGaugeColor(available: number, total: number): 'success' | 'warning'
   return 'success'
 }
 
-export function SemaphoreGauge({ semaphore }: SemaphoreGaugeProps) {
+export function SemaphoreGauge({
+  semaphore,
+  selected = false,
+  selectedCoroutineId = null,
+  picker,
+}: SemaphoreGaugeProps) {
   const usedPermits = semaphore.totalPermits - semaphore.availablePermits
-  const percent = semaphore.totalPermits > 0
-    ? (usedPermits / semaphore.totalPermits) * 100
-    : 0
+  const percent = semaphore.totalPermits > 0 ? (usedPermits / semaphore.totalPermits) * 100 : 0
+  const listed = semaphore.activeHolders.reduce((sum, h) => sum + h.permits, 0)
+  // Permits in use that no listed holder accounts for (taken at creation, or before the retained history).
+  const unrecorded = Math.max(0, usedPermits - listed)
   const color = getGaugeColor(semaphore.availablePermits, semaphore.totalPermits)
 
   // SVG arc calculation
@@ -27,80 +40,152 @@ export function SemaphoreGauge({ semaphore }: SemaphoreGaugeProps) {
   const dashOffset = circumference - dashArray
 
   return (
-    <Card shadow="sm" data-testid="semaphore-gauge">
-      <CardHeader className="pb-2">
-        <div className="flex w-full items-center justify-between">
-          <div>
-            <div className="font-semibold text-sm">
-              {semaphore.label || semaphore.semaphoreId}
+    <div
+      data-testid={`resource-${semaphore.semaphoreId}`}
+      data-selected={selected ? 'true' : 'false'}
+      className={selected ? 'rounded-large ring-2 ring-primary' : undefined}
+    >
+      <Card shadow="sm" data-testid="semaphore-gauge">
+        <CardHeader className="pb-2">
+          <div className="flex w-full items-center justify-between">
+            <div>
+              <div className="font-semibold text-sm">
+                {semaphore.label || semaphore.semaphoreId}
+              </div>
+              <div className="text-xs text-default-500 font-mono">{semaphore.semaphoreId}</div>
             </div>
-            <div className="text-xs text-default-500 font-mono">{semaphore.semaphoreId}</div>
+            <Chip size="sm" variant="flat" color={color}>
+              {semaphore.availablePermits} / {semaphore.totalPermits} available
+            </Chip>
           </div>
-          <Chip size="sm" variant="flat" color={color}>
-            {semaphore.availablePermits} / {semaphore.totalPermits} available
-          </Chip>
-        </div>
-      </CardHeader>
-      <CardBody className="pt-0 space-y-3">
-        {/* Circular gauge representation using progress bar */}
-        <div className="flex items-center justify-center" data-testid="gauge-visual">
-          <div className="relative w-24 h-24">
-            <svg viewBox="0 0 100 100" className="w-full h-full -rotate-90">
-              {/* Background circle */}
-              <circle
-                cx="50"
-                cy="50"
-                r="40"
-                fill="none"
-                stroke="currentColor"
-                strokeWidth="8"
-                className="text-default-200"
-              />
-              {/* Used permits arc - animated */}
-              <motion.circle
-                cx="50"
-                cy="50"
-                r="40"
-                fill="none"
-                stroke="currentColor"
-                strokeWidth="8"
-                strokeDasharray={circumference}
-                initial={{ strokeDashoffset: circumference }}
-                animate={{ strokeDashoffset: dashOffset }}
-                transition={{ duration: 0.8, ease: 'easeOut' }}
-                className={
-                  color === 'danger'
-                    ? 'text-danger'
-                    : color === 'warning'
-                      ? 'text-warning'
-                      : 'text-success'
-                }
-                strokeLinecap="round"
-              />
-            </svg>
-            <div className="absolute inset-0 flex flex-col items-center justify-center">
-              <motion.div
-                className="text-lg font-bold"
-                initial={{ scale: 0.8, opacity: 0 }}
-                animate={{ scale: 1, opacity: 1 }}
-                transition={{ type: 'spring', stiffness: 300, damping: 25, delay: 0.3 }}
-              >
-                {usedPermits}
-              </motion.div>
-              <div className="text-[10px] text-default-400">used</div>
+        </CardHeader>
+        <CardBody className="pt-0 space-y-3">
+          {/* Circular gauge representation using progress bar */}
+          <div className="flex items-center justify-center" data-testid="gauge-visual">
+            <div className="relative w-24 h-24">
+              <svg viewBox="0 0 100 100" className="w-full h-full -rotate-90">
+                {/* Background circle */}
+                <circle
+                  cx="50"
+                  cy="50"
+                  r="40"
+                  fill="none"
+                  stroke="currentColor"
+                  strokeWidth="8"
+                  className="text-default-200"
+                />
+                {/* Used permits arc - animated */}
+                <motion.circle
+                  cx="50"
+                  cy="50"
+                  r="40"
+                  fill="none"
+                  stroke="currentColor"
+                  strokeWidth="8"
+                  strokeDasharray={circumference}
+                  initial={{ strokeDashoffset: circumference }}
+                  animate={{ strokeDashoffset: dashOffset }}
+                  transition={{ duration: 0.8, ease: 'easeOut' }}
+                  className={
+                    color === 'danger'
+                      ? 'text-danger'
+                      : color === 'warning'
+                        ? 'text-warning'
+                        : 'text-success'
+                  }
+                  strokeLinecap="round"
+                />
+              </svg>
+              <div className="absolute inset-0 flex flex-col items-center justify-center">
+                <motion.div
+                  className="text-lg font-bold"
+                  initial={{ scale: 0.8, opacity: 0 }}
+                  animate={{ scale: 1, opacity: 1 }}
+                  transition={{ type: 'spring', stiffness: 300, damping: 25, delay: 0.3 }}
+                >
+                  {usedPermits}
+                </motion.div>
+                <div className="text-[10px] text-default-400">used</div>
+              </div>
             </div>
           </div>
-        </div>
 
-        {/* Waiting info */}
-        {semaphore.waitQueue.length > 0 && (
-          <div className="text-xs">
-            <Chip size="sm" variant="flat" color="warning">
-              {semaphore.waitQueue.length} waiting
-            </Chip>
+          {/* Who holds the permits (S-6): counted, never numbered. When a permit came back from a
+              coroutine that held none (S-7), more permits are listed than are in use, and which listed
+              holder still holds one is unknown; when fewer are listed (permits taken at creation), the
+              rest have no recorded holder. Either way the line says so rather than guess. */}
+          <div data-testid="semaphore-holders" className="text-xs">
+            <span className="text-default-500">Held by </span>
+            {usedPermits <= 0 ? (
+              <span>none</span>
+            ) : semaphore.unknownHolderPermits > 0 || semaphore.unsettled ? (
+              <>
+                <span className="font-semibold">holder unknown</span>
+                <span className="text-default-500">
+                  {' '}
+                  — {usedPermits} of {semaphore.totalPermits} permits in use;{' '}
+                  {semaphore.activeHolders.length > 0 ? 'last recorded holders ' : 'no holder recorded yet'}
+                </span>
+                <HolderNames semaphore={semaphore} picker={picker} selectedCoroutineId={selectedCoroutineId} />
+                {semaphore.unknownHolderPermits > 0 && (
+                  <span className="text-default-500">. A permit came back from a coroutine that held none.</span>
+                )}
+              </>
+            ) : semaphore.activeHolders.length === 0 ? (
+              <>
+                <span className="font-semibold">holder unknown</span>
+                <span className="text-default-500">
+                  {' '}
+                  — {usedPermits} of {semaphore.totalPermits} permits in use; no holder was recorded
+                </span>
+              </>
+            ) : (
+              <>
+                <HolderNames semaphore={semaphore} picker={picker} selectedCoroutineId={selectedCoroutineId} counts />
+                {unrecorded > 0 && (
+                  <span className="text-default-500">
+                    , and {unrecorded === 1 ? '1 permit' : `${unrecorded} permits`} with no recorded holder
+                  </span>
+                )}
+              </>
+            )}
           </div>
-        )}
-      </CardBody>
-    </Card>
+
+          {/* Waiting info */}
+          {semaphore.waitQueue.length > 0 && (
+            <div className="text-xs">
+              <Chip size="sm" variant="flat" color="warning">
+                {semaphore.waitQueue.length} waiting
+              </Chip>
+            </div>
+          )}
+        </CardBody>
+      </Card>
+    </div>
+  )
+}
+
+/** The listed holders, comma-separated; with `counts`, a holder of several permits says how many. */
+function HolderNames({
+  semaphore,
+  picker,
+  selectedCoroutineId,
+  counts = false,
+}: {
+  semaphore: SemaphoreState
+  picker?: CoroutinePicker
+  selectedCoroutineId: string | null
+  counts?: boolean
+}) {
+  return (
+    <>
+      {semaphore.activeHolders.map((h, i) => (
+        <span key={h.id}>
+          {i > 0 && ', '}
+          <CoroutineName id={h.id} label={h.label} picker={picker} selected={h.id === selectedCoroutineId} />
+          {counts && h.permits > 1 && <span className="text-default-500"> ({h.permits} permits)</span>}
+        </span>
+      ))}
+    </>
   )
 }
diff --git a/frontend/src/components/sync/SyncPanel.tsx b/frontend/src/components/sync/SyncPanel.tsx
index 10d5c6a1..541d039a 100644
--- a/frontend/src/components/sync/SyncPanel.tsx
+++ b/frontend/src/components/sync/SyncPanel.tsx
@@ -1,3 +1,4 @@
+import { useEffect, useRef } from 'react'
 import { Card, CardBody, Divider } from '@heroui/react'
 import { motion } from 'framer-motion'
 import { useSyncEvents } from '@/hooks/use-sync-events'
@@ -5,13 +6,39 @@ import { MutexStateIndicator } from './MutexStateIndicator'
 import { SemaphoreGauge } from './SemaphoreGauge'
 import { WaitQueueList } from './WaitQueueList'
 import { DeadlockWarning } from './DeadlockWarning'
+import type { CoroutinePicker } from './CoroutineName'
+import type { VizEvent } from '@/types/api'
 
 interface SyncPanelProps {
   sessionId: string
+  /** The events the page shows (live, paused or at the replay cursor); the stored history when absent. */
+  events?: readonly VizEvent[]
+  /** A lock or semaphore the page selected: ringed and scrolled into view (S-2). */
+  selectedResourceId?: string | null
+  /** The selected coroutine, marked where it holds or waits. */
+  selectedCoroutineId?: string | null
+  /** Selects a coroutine by its name, when the page can (S-2). */
+  picker?: CoroutinePicker
+  /** Changes with every selection, so selecting the same resource again scrolls to it again. */
+  revealKey?: unknown
 }
 
-export function SyncPanel({ sessionId }: SyncPanelProps) {
-  const { mutexes, semaphores, deadlocks, warnings } = useSyncEvents(sessionId)
+export function SyncPanel({
+  sessionId,
+  events,
+  selectedResourceId = null,
+  selectedCoroutineId = null,
+  picker,
+  revealKey,
+}: SyncPanelProps) {
+  const { mutexes, semaphores, deadlocks, warnings } = useSyncEvents(sessionId, events)
+  // Bring a selected resource into view; its section was unfolded in the same render.
+  const panelRef = useRef<HTMLDivElement>(null)
+  useEffect(() => {
+    if (!selectedResourceId) return
+    const el = panelRef.current?.querySelector(`[data-testid="resource-${CSS.escape(selectedResourceId)}"]`)
+    el?.scrollIntoView?.({ block: 'nearest' })
+  }, [selectedResourceId, revealKey])
 
   if (mutexes.length === 0 && semaphores.length === 0 && deadlocks.length === 0 && warnings.length === 0) {
     return (
@@ -36,6 +63,7 @@ export function SyncPanel({ sessionId }: SyncPanelProps) {
 
   return (
     <motion.div
+      ref={panelRef}
       className="space-y-4 mt-2"
       data-testid="sync-panel"
       initial={{ opacity: 0 }}
@@ -64,10 +92,20 @@ export function SyncPanel({ sessionId }: SyncPanelProps) {
               animate={{ opacity: 1, y: 0 }}
               transition={{ duration: 0.3, delay: index * 0.05 }}
             >
-              <MutexStateIndicator mutex={mutex} />
+              <MutexStateIndicator
+                mutex={mutex}
+                selected={mutex.mutexId === selectedResourceId}
+                selectedCoroutineId={selectedCoroutineId}
+                picker={picker}
+              />
               {mutex.waitQueue.length > 0 && (
                 <div className="ml-4">
-                  <WaitQueueList queue={mutex.waitQueue} title="Mutex Wait Queue" />
+                  <WaitQueueList
+                    queue={mutex.waitQueue}
+                    title="Mutex Wait Queue"
+                    picker={picker}
+                    selectedId={selectedCoroutineId}
+                  />
                 </div>
               )}
             </motion.div>
@@ -97,10 +135,20 @@ export function SyncPanel({ sessionId }: SyncPanelProps) {
                 animate={{ opacity: 1, y: 0 }}
                 transition={{ duration: 0.3, delay: index * 0.05 }}
               >
-                <SemaphoreGauge semaphore={sem} />
+                <SemaphoreGauge
+                  semaphore={sem}
+                  selected={sem.semaphoreId === selectedResourceId}
+                  selectedCoroutineId={selectedCoroutineId}
+                  picker={picker}
+                />
                 {sem.waitQueue.length > 0 && (
                   <div className="ml-4">
-                    <WaitQueueList queue={sem.waitQueue} title="Semaphore Wait Queue" />
+                    <WaitQueueList
+                      queue={sem.waitQueue}
+                      title="Semaphore Wait Queue"
+                      picker={picker}
+                      selectedId={selectedCoroutineId}
+                    />
                   </div>
                 )}
               </motion.div>
diff --git a/frontend/src/components/sync/WaitQueueList.tsx b/frontend/src/components/sync/WaitQueueList.tsx
index 20ceb1b1..0986257a 100644
--- a/frontend/src/components/sync/WaitQueueList.tsx
+++ b/frontend/src/components/sync/WaitQueueList.tsx
@@ -1,5 +1,6 @@
 import { Chip } from '@heroui/react'
 import { motion, AnimatePresence } from 'framer-motion'
+import { CoroutineName, type CoroutinePicker } from './CoroutineName'
 
 interface WaitQueueEntry {
   id: string
@@ -9,9 +10,13 @@ interface WaitQueueEntry {
 interface WaitQueueListProps {
   queue: WaitQueueEntry[]
   title?: string
+  /** Selects a waiter, when the page can (S-2). */
+  picker?: CoroutinePicker
+  /** The page's selected coroutine, marked in the queue. */
+  selectedId?: string | null
 }
 
-export function WaitQueueList({ queue, title = 'Wait Queue' }: WaitQueueListProps) {
+export function WaitQueueList({ queue, title = 'Wait Queue', picker, selectedId = null }: WaitQueueListProps) {
   if (queue.length === 0) {
     return (
       <div className="text-xs text-default-400" data-testid="wait-queue-empty">
@@ -36,7 +41,9 @@ export function WaitQueueList({ queue, title = 'Wait Queue' }: WaitQueueListProp
               transition={{ duration: 0.2, delay: idx * 0.03 }}
             >
               <Chip size="sm" variant="flat" color="warning">#{idx + 1}</Chip>
-              <span className="font-mono truncate">{entry.label || entry.id}</span>
+              <span className="truncate">
+                <CoroutineName id={entry.id} label={entry.label} picker={picker} selected={entry.id === selectedId} />
+              </span>
             </motion.div>
           ))}
         </AnimatePresence>
```

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

- [ ] **Step 3: Implement.** The page's selection decides which channel is shown, ringed and scrolled to; a pick in the panel goes to the page (`onSelectChannel`), so there is one selection; without it (replay, shared view) the pick stays local:

```diff
diff --git a/frontend/src/components/channels/ChannelPanel.tsx b/frontend/src/components/channels/ChannelPanel.tsx
index 44c357fa..20260719 100644
--- a/frontend/src/components/channels/ChannelPanel.tsx
+++ b/frontend/src/components/channels/ChannelPanel.tsx
@@ -1,4 +1,4 @@
-import { useState } from 'react'
+import { useEffect, useRef, useState } from 'react'
 import { Card, CardBody, CardHeader, Chip, Divider } from '@heroui/react'
 import { motion, AnimatePresence } from 'framer-motion'
 import { FiRadio, FiHash } from 'react-icons/fi'
@@ -6,19 +6,50 @@ import { useChannelEvents } from '@/hooks/use-channel-events'
 import { ChannelBufferGauge } from './ChannelBufferGauge'
 import { ChannelTimeline } from './ChannelTimeline'
 import { ChannelProducerConsumer } from './ChannelProducerConsumer'
+import type { VizEvent } from '@/types/api'
 
 interface ChannelPanelProps {
   sessionId: string
+  /** The events the page shows (live, paused or at the replay cursor); the stored history when absent. */
+  events?: readonly VizEvent[]
+  /** The channel the page selected (S-2). While set it is the one shown: the page holds one selection. */
+  focusChannelId?: string | null
+  /**
+   * Picking a channel here selects it on the page (S-2). Without it (replay,
+   * the shared view) the pick stays local to this panel.
+   */
+  onSelectChannel?: (channelId: string) => void
+  /** Changes with every selection, so selecting the same channel again scrolls to it again. */
+  revealKey?: unknown
 }
 
-export function ChannelPanel({ sessionId }: ChannelPanelProps) {
-  const { channels, eventsByChannel, bufferStates } = useChannelEvents(sessionId)
-  const [selectedChannelId, setSelectedChannelId] = useState<string | null>(null)
+export function ChannelPanel({
+  sessionId,
+  events,
+  focusChannelId = null,
+  onSelectChannel,
+  revealKey,
+}: ChannelPanelProps) {
+  const { channels, eventsByChannel, bufferStates } = useChannelEvents(sessionId, events)
+  const [pickedChannelId, setPickedChannelId] = useState<string | null>(null)
+  const pick = (channelId: string) => {
+    setPickedChannelId(channelId)
+    onSelectChannel?.(channelId)
+  }
+
+  // Bring the page's channel into view; its section was unfolded in the same render.
+  const listRef = useRef<HTMLDivElement>(null)
+  useEffect(() => {
+    if (!focusChannelId) return
+    const el = listRef.current?.querySelector(`[data-testid="channel-selector-${CSS.escape(focusChannelId)}"]`)
+    el?.scrollIntoView?.({ block: 'nearest' })
+  }, [focusChannelId, revealKey])
 
-  // Auto-select first channel when data arrives
+  // The page's selection wins; then the last pick here; then the first channel.
+  const wanted = focusChannelId ?? pickedChannelId
   const effectiveSelectedId =
-    selectedChannelId && channels.some((c) => c.channelId === selectedChannelId)
-      ? selectedChannelId
+    wanted && channels.some((c) => c.channelId === wanted)
+      ? wanted
       : channels.length > 0
         ? channels[0]!.channelId
         : null
@@ -70,20 +101,22 @@ export function ChannelPanel({ sessionId }: ChannelPanelProps) {
           </div>
         </CardHeader>
         <CardBody>
-          <div className="flex flex-wrap gap-2" data-testid="channel-list">
+          <div ref={listRef} className="flex flex-wrap gap-2" data-testid="channel-list">
             {channels.map((ch, index) => {
               const isSelected = ch.channelId === effectiveSelectedId
               const evtCount = eventsByChannel.get(ch.channelId)?.length ?? 0
               return (
                 <motion.button
                   key={ch.channelId}
-                  onClick={() => setSelectedChannelId(ch.channelId)}
+                  onClick={() => pick(ch.channelId)}
                   className={`flex items-center gap-2 px-3 py-2 rounded-lg border transition-colors ${
                     isSelected
                       ? 'border-primary bg-primary/10'
                       : 'border-default-200 hover:bg-default-100'
-                  }`}
+                  }${ch.channelId === focusChannelId ? ' ring-2 ring-primary' : ''}`}
                   data-testid={`channel-selector-${ch.channelId}`}
+                  // The page's selection (S-2), ringed like a selected lock.
+                  data-focused={ch.channelId === focusChannelId ? 'true' : 'false'}
                   initial={{ opacity: 0, y: 10 }}
                   animate={{ opacity: 1, y: 0 }}
                   transition={{ duration: 0.3, delay: index * 0.05 }}
diff --git a/frontend/src/components/flow/FlowPanel.tsx b/frontend/src/components/flow/FlowPanel.tsx
index 401cf95d..d77ec28e 100644
--- a/frontend/src/components/flow/FlowPanel.tsx
+++ b/frontend/src/components/flow/FlowPanel.tsx
@@ -8,9 +8,12 @@ import { FlowBackpressureIndicator } from './FlowBackpressureIndicator'
 import { FlowValueTrace } from './FlowValueTrace'
 import { SharedFlowPanel } from './SharedFlowPanel'
 import { StateFlowPanel } from './StateFlowPanel'
+import type { VizEvent } from '@/types/api'
 
 interface FlowPanelProps {
   sessionId: string
+  /** The events the page shows (live, paused or at the replay cursor); the stored history when absent. */
+  events?: readonly VizEvent[]
 }
 
 function getFlowTypeColor(flowType: string): 'primary' | 'secondary' | 'warning' | 'success' {
@@ -23,8 +26,8 @@ function getFlowTypeColor(flowType: string): 'primary' | 'secondary' | 'warning'
   }
 }
 
-export function FlowPanel({ sessionId }: FlowPanelProps) {
-  const { flows, hasBackpressure } = useFlowEvents(sessionId)
+export function FlowPanel({ sessionId, events }: FlowPanelProps) {
+  const { flows, hasBackpressure } = useFlowEvents(sessionId, events)
   const [visualMode, setVisualMode] = useState<'chips' | 'svg'>('chips')
 
   if (flows.length === 0) {
diff --git a/frontend/src/components/jobs/JobPanel.tsx b/frontend/src/components/jobs/JobPanel.tsx
index 3d1230f5..0aa067a8 100644
--- a/frontend/src/components/jobs/JobPanel.tsx
+++ b/frontend/src/components/jobs/JobPanel.tsx
@@ -4,13 +4,16 @@ import { useJobEvents } from '@/hooks/use-job-events'
 import { JobStateBadge } from './JobStateBadge'
 import { WaitingForChildrenCard } from './WaitingForChildrenCard'
 import { JobHierarchyView } from './JobHierarchyView'
+import type { VizEvent } from '@/types/api'
 
 interface JobPanelProps {
   sessionId: string
+  /** The events the page shows (live, paused or at the replay cursor); the stored history when absent. */
+  events?: readonly VizEvent[]
 }
 
-export function JobPanel({ sessionId }: JobPanelProps) {
-  const { jobs, roots, waitingForChildren } = useJobEvents(sessionId)
+export function JobPanel({ sessionId, events }: JobPanelProps) {
+  const { jobs, roots, waitingForChildren } = useJobEvents(sessionId, events)
 
   if (jobs.length === 0) {
     return (
```

(The channel tests wait with `findByText` for the detail to swap: it animates out first, `AnimatePresence mode="wait"`.)

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
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { EventCategories } from '@/hooks/use-event-categories'
import type { EvidenceCounts } from '@/lib/evidence-summary'
import type { ThreadActivity, VizEvent } from '@/types/api'
import { EvidencePanels } from './EvidencePanels'

vi.mock('../channels/ChannelPanel', () => ({
  ChannelPanel: (p: { events?: unknown[]; focusChannelId?: string | null; onSelectChannel?: (id: string) => void }) => (
    <button
      type="button"
      data-testid="channel-panel"
      data-events={p.events?.length ?? 'stored'}
      data-focus={p.focusChannelId ?? ''}
      onClick={() => p.onSelectChannel?.('ch-2')}
    />
  ),
}))
vi.mock('../flow/FlowPanel', () => ({
  FlowPanel: (p: { events?: unknown[] }) => <div data-testid="flow-panel" data-events={p.events?.length ?? 'stored'} />,
}))
vi.mock('../sync/SyncPanel', () => ({
  SyncPanel: (p: { events?: unknown[]; selectedResourceId?: string | null; selectedCoroutineId?: string | null; picker?: unknown }) => (
    <div
      data-testid="sync-panel"
      data-events={p.events?.length ?? 'stored'}
      data-resource={p.selectedResourceId ?? ''}
      data-coroutine={p.selectedCoroutineId ?? ''}
      data-picker={String(!!p.picker)}
    />
  ),
}))
vi.mock('../jobs/JobPanel', () => ({
  JobPanel: (p: { events?: unknown[] }) => <div data-testid="job-panel" data-events={p.events?.length ?? 'stored'} />,
}))
vi.mock('../ThreadTable', () => ({ ThreadTable: () => <div data-testid="thread-table" /> }))
vi.mock('../DispatcherOverview', () => ({ DispatcherOverview: () => <div data-testid="dispatcher-overview" /> }))

const none: EvidenceCounts = { locks: 0, semaphores: 0, channels: 0, flows: 0, jobEvents: 0 }
```

(FlowPanel and JobPanel the same with `data-events`.) Then:

```tsx
describe('EvidencePanels — the page\'s events and selection (S-2)', () => {
  const synced = {
    categories: categories({ hasSyncPrimitives: true, hasChannels: true, hasFlowOps: true, hasJobs: true }),
    counts: { ...none, locks: 1, channels: 1, flows: 1, jobEvents: 1 },
  }
  const events = [{ seq: 1 }, { seq: 2 }] as unknown as VizEvent[]

  it('gives every event-backed panel the page\'s events', () => {
    renderPanels({ ...synced, events })
    for (const id of ['sync-panel', 'channel-panel', 'flow-panel', 'job-panel']) {
      expect(screen.getByTestId(id)).toHaveAttribute('data-events', '2')
    }
  })

  it('unfolds Locks and permits for a selected lock, and passes the lock on', () => {
    const { rerender } = renderPanels({ ...synced, events })
    expect(screen.getByRole('button', { name: /Locks and permits/ })).toHaveAttribute('aria-expanded', 'false')
    rerender(panels({ ...synced, events, selection: { kind: 'resource', resource: 'mutex', id: 'm-1' } }))
    expect(screen.getByRole('button', { name: /Locks and permits/ })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('sync-panel')).toHaveAttribute('data-resource', 'm-1')
  })

  it('re-opens the section when the same lock is selected again after folding it', async () => {
    const { rerender } = renderPanels({ ...synced, events, selection: { kind: 'resource', resource: 'mutex', id: 'm-1' } })
    await userEvent.click(screen.getByRole('button', { name: /Locks and permits/ }))
    expect(screen.getByRole('button', { name: /Locks and permits/ })).toHaveAttribute('aria-expanded', 'false')
    rerender(panels({ ...synced, events, selection: { kind: 'resource', resource: 'mutex', id: 'm-1' } }))
    expect(screen.getByRole('button', { name: /Locks and permits/ })).toHaveAttribute('aria-expanded', 'true')
  })

  it('unfolds Channels for a selected channel, shows it, and a pick there selects on the page', async () => {
    const onSelectResource = vi.fn()
    const { rerender } = renderPanels({ ...synced, events, onSelectResource })
    rerender(panels({ ...synced, events, onSelectResource, selection: { kind: 'resource', resource: 'channel', id: 'ch-1' } }))
    expect(screen.getByRole('button', { name: /Channels/ })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('channel-panel')).toHaveAttribute('data-focus', 'ch-1')
    await userEvent.click(screen.getByTestId('channel-panel'))
    expect(onSelectResource).toHaveBeenCalledWith({ kind: 'resource', resource: 'channel', id: 'ch-2' })
  })

  it('passes a selected coroutine to Locks and permits without unfolding anything', () => {
    renderPanels({ ...synced, events, selection: { kind: 'coroutine', id: 'c-1' }, picker: { select: vi.fn(), canSelect: () => true } })
    expect(screen.getByTestId('sync-panel')).toHaveAttribute('data-coroutine', 'c-1')
    expect(screen.getByTestId('sync-panel')).toHaveAttribute('data-picker', 'true')
    expect(screen.getByRole('button', { name: /Locks and permits/ })).toHaveAttribute('aria-expanded', 'false')
  })

  it('reads the events it is given in replay too, so Locks and permits carries no live-data warning', async () => {
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

- [ ] **Step 3: Implement** — `EvidencePanels.tsx`:

```diff
diff --git a/frontend/src/components/workspace/EvidencePanels.tsx b/frontend/src/components/workspace/EvidencePanels.tsx
index 587a5f94..8d24b09e 100644
--- a/frontend/src/components/workspace/EvidencePanels.tsx
+++ b/frontend/src/components/workspace/EvidencePanels.tsx
@@ -12,7 +12,9 @@ import { plural, resourceSummary, type EvidenceCounts } from '@/lib/evidence-sum
 import { groupThreadsByName, threadSummary } from '@/lib/thread-groups'
 import type { EventCategories } from '@/hooks/use-event-categories'
 import type { Rung } from '@/lib/fidelity-rung'
-import type { ThreadActivity } from '@/types/api'
+import type { ThreadActivity, VizEvent } from '@/types/api'
+import type { Selection } from '@/lib/selection'
+import type { CoroutinePicker } from '../sync/CoroutineName'
 
 interface EvidencePanelsProps {
   sessionId: string
@@ -32,6 +34,24 @@ interface EvidencePanelsProps {
    * loading, and after a failed read, unavailable.
    */
   history?: 'ready' | 'loading' | 'unavailable'
+  /**
+   * The events the page shows: live, paused, or at the replay cursor. Locks
+   * and permits, Channels, Flows and Jobs follow them, so their facts refresh
+   * with each new event; without them each reads the stored history.
+   */
+  events?: readonly VizEvent[]
+  /** The page's one selection (S-2). A resource unfolds its section; a coroutine is marked in it. */
+  selection?: Selection | null
+  /** Selects a coroutine named in Locks and permits, when the page can (S-2). */
+  picker?: CoroutinePicker
+  /** Selects a resource on the page: a channel picked in Channels (S-2). */
+  onSelectResource?: (selection: Selection) => void
+}
+
+/** The section a selected resource lives in. */
+function sectionOf(selection: Selection | null): string | null {
+  if (selection?.kind !== 'resource') return null
+  return selection.resource === 'channel' ? 'Channels' : 'Locks and permits'
 }
 
 /**
@@ -60,7 +80,10 @@ interface Capability {
  * telling its user to swap in `VizMutex` names code that is not theirs.
  *
  * Folded sections are hidden, not unmounted: every panel subscribes at page
- * load, exactly as before, so opening one later never refetches.
+ * load, exactly as before, so opening one later never refetches. Locks and
+ * permits, Channels, Flows and Jobs read the events the page shows, so they
+ * refresh live and follow the replay cursor; a selected resource unfolds its
+ * section (S-2).
  */
 export function EvidencePanels({
   sessionId,
@@ -72,8 +95,16 @@ export function EvidencePanels({
   threadActivity,
   captureStartNanos,
   history = 'ready',
+  events,
+  selection = null,
+  picker,
+  onSelectResource,
 }: EvidencePanelsProps) {
-  const [open, setOpen] = useState<ReadonlySet<string>>(new Set())
+  // A resource already selected when Evidence mounts has its section open.
+  const [open, setOpen] = useState<ReadonlySet<string>>(() => {
+    const section = sectionOf(selection)
+    return new Set(section ? [section] : [])
+  })
   const toggle = (title: string) =>
     setOpen(prev => {
       const next = new Set(prev)
@@ -82,6 +113,17 @@ export function EvidencePanels({
       return next
     })
 
+  // A resource selected anywhere on the page unfolds its section (S-2). Each
+  // selection is a new object, so choosing the same lock again re-opens it.
+  const [seenSelection, setSeenSelection] = useState<Selection | null>(selection)
+  if (selection !== seenSelection) {
+    setSeenSelection(selection)
+    const section = sectionOf(selection)
+    if (section && !open.has(section)) setOpen(prev => new Set(prev).add(section))
+  }
+  const selectedResource = selection?.kind === 'resource' ? selection : null
+  const selectedCoroutineId = selection?.kind === 'coroutine' ? selection.id : null
+
   const groups = useMemo(() => groupThreadsByName(threadActivity), [threadActivity])
   const dispatchers = useMemo(() => {
     const names = new Set<string>()
@@ -98,7 +140,16 @@ export function EvidencePanels({
       summary: resourceSummary([plural(counts.locks, 'lock'), plural(counts.semaphores, 'semaphore')]) ?? 'captured',
       whatYouWouldSee: 'Who holds each mutex and semaphore, who is queued behind it, and for how long.',
       unlockWith: 'Swap Mutex() for VizMutex() in the code you want to watch.',
-      panel: <SyncPanel sessionId={sessionId} />,
+      panel: (
+        <SyncPanel
+          sessionId={sessionId}
+          events={events}
+          selectedResourceId={selectedResource && selectedResource.resource !== 'channel' ? selectedResource.id : null}
+          selectedCoroutineId={selectedCoroutineId}
+          picker={picker}
+          revealKey={selection}
+        />
+      ),
     },
     {
       title: 'Channels',
@@ -106,7 +157,17 @@ export function EvidencePanels({
       summary: resourceSummary([plural(counts.channels, 'channel')]) ?? 'captured',
       whatYouWouldSee: 'Send and receive pairs, buffer occupancy, and which side is waiting.',
       unlockWith: 'Create the channel with InstrumentedChannel(...).',
-      panel: <ChannelPanel sessionId={sessionId} />,
+      panel: (
+        <ChannelPanel
+          sessionId={sessionId}
+          events={events}
+          focusChannelId={selectedResource?.resource === 'channel' ? selectedResource.id : null}
+          revealKey={selection}
+          onSelectChannel={
+            onSelectResource ? id => onSelectResource({ kind: 'resource', resource: 'channel', id }) : undefined
+          }
+        />
+      ),
     },
     {
       title: 'Flows',
@@ -114,7 +175,7 @@ export function EvidencePanels({
       summary: resourceSummary([plural(counts.flows, 'flow')]) ?? 'captured',
       whatYouWouldSee: 'Emissions, operator stages, and where a slow collector is stalling the producer.',
       unlockWith: 'Wrap the flow you care about with .instrumented().',
-      panel: <FlowPanel sessionId={sessionId} />,
+      panel: <FlowPanel sessionId={sessionId} events={events} />,
     },
     {
       title: 'Jobs',
@@ -125,7 +186,7 @@ export function EvidencePanels({
       // does not promote a session to instrumented), so there is no honest
       // one-line unlock to offer for them.
       unlockWith: '',
-      panel: <JobPanel sessionId={sessionId} />,
+      panel: <JobPanel sessionId={sessionId} events={events} />,
     },
   ]
 
@@ -168,7 +229,8 @@ export function EvidencePanels({
             summary={c.summary}
             open={open.has(c.title)}
             onToggle={() => toggle(c.title)}
-            replayActive={replayActive}
+            // Read from the page's events, so it follows the replay cursor like Threads (D-17).
+            replayActive={false}
           >
             {c.panel}
           </Section>
```

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
import type { VizEvent } from '@/types/api'
import { eventSubject } from '@/lib/selection'
import { EventsList } from './EventsList'

vi.mock('@/lib/animation-throttle', () => ({ useAnimationSlot: () => false }))

const acquired = {
  sessionId: 's', seq: 7, tsNanos: 7, kind: 'MutexLockAcquired',
  mutexId: 'm-1', mutexLabel: 'inventory-lock', acquirerId: 'c-2', acquirerLabel: 'order-2', waitDurationNanos: 0,
} as unknown as VizEvent
const created = {
  sessionId: 's', seq: 6, tsNanos: 6, kind: 'MutexCreated', mutexId: 'm-1', mutexLabel: 'inventory-lock', ownerCoroutineId: null,
} as unknown as VizEvent
const closed = { sessionId: 's', seq: 9, tsNanos: 9, kind: 'ChannelClosed', channelId: 'ch-1', cause: null } as unknown as VizEvent
const deadlock = { sessionId: 's', seq: 8, tsNanos: 8, kind: 'DeadlockDetected', involvedCoroutines: [] } as unknown as VizEvent

describe('EventsList', () => {
  it('names a lock event by who and what', () => {
    render(<EventsList events={[acquired]} />)
    expect(screen.getByText('order-2 · inventory-lock')).toBeInTheDocument()
  })

  it('names a channel-only event by the channel\'s name', () => {
    render(<EventsList events={[closed]} resourceNames={new Map([['ch-1', 'orders']])} />)
    expect(screen.getByText('orders')).toBeInTheDocument()
  })

  it('makes each row about something selectable a button that selects it (S-2)', async () => {
    const onSelect = vi.fn()
    render(
      <EventsList
        events={[created, acquired, deadlock, closed]}
        subjectOf={eventSubject}
        onSelect={onSelect}
        resourceNames={new Map([['ch-1', 'orders']])}
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: 'Select order-2 · inventory-lock (event #7)' }))
    await userEvent.click(screen.getByRole('button', { name: 'Select inventory-lock (event #6)' }))
    await userEvent.click(screen.getByRole('button', { name: 'Select orders (event #9)' }))
    expect(onSelect.mock.calls).toEqual([
      [{ kind: 'coroutine', id: 'c-2' }],
      [{ kind: 'resource', resource: 'mutex', id: 'm-1' }],
      [{ kind: 'resource', resource: 'channel', id: 'ch-1' }],
    ])
    // A deadlock is about no one thing: not a button.
    expect(screen.getAllByRole('button', { name: /^Select / })).toHaveLength(3)
  })

  it('keeps rows presentational without onSelect', () => {
    render(<EventsList events={[acquired]} />)
    expect(screen.queryByRole('button', { name: /^Select / })).toBeNull()
  })
})
```

`EventsPanel.test.tsx` — replace the three tests with:

```tsx
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { VizEvent } from '@/types/api'

vi.mock('../EventsList', () => ({
  EventsList: ({ events, onSelect }: { events: VizEvent[]; onSelect?: unknown }) => (
    <div data-testid="events-list" data-count={events.length} data-selectable={String(!!onSelect)} />
  ),
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
    render(
      <EventsPanel events={EVENTS} selection={{ kind: 'resource', resource: 'mutex', id: 'm-1' }} selectionLabel="inventory-lock" />,
    )
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events about inventory-lock · 1')
  })

  it('falls back to the id when the selection has no label', () => {
    render(<EventsPanel events={EVENTS} selection={{ kind: 'coroutine', id: 'c-1' }} selectionLabel={null} />)
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events for c-1')
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

`EventsList.tsx`:

```diff
diff --git a/frontend/src/components/EventsList.tsx b/frontend/src/components/EventsList.tsx
index 52b8dc37..1c1be241 100644
--- a/frontend/src/components/EventsList.tsx
+++ b/frontend/src/components/EventsList.tsx
@@ -19,11 +19,20 @@ import { formatNanoTime, formatRelativeTime } from '@/lib/utils'
 import { useAnimatedInView } from '@/hooks/use-animated-in-view'
 import { FiSearch, FiClock, FiServer, FiDatabase, FiMail, FiCloud, FiActivity } from 'react-icons/fi'
 import { DispatcherBadge } from './DispatcherBadge'
+import { eventTitle, type Selection } from '@/lib/selection'
 
 interface EventsListProps {
   events: VizEvent[]
+  /** What a row is about (S-2); null keeps that row presentational. */
+  subjectOf?: (event: VizEvent) => Selection | null
+  /** Selects what a row is about. Without it every row is presentational. */
+  onSelect?: (selection: Selection) => void
+  /** Resource id -> name, for rows about a channel whose creation is not in the list. */
+  resourceNames?: ReadonlyMap<string, string>
 }
 
+const NO_NAMES: ReadonlyMap<string, string> = new Map()
+
 /**
  * Parse a service label like "OrderService.processOrder" into parts
  */
@@ -96,7 +105,7 @@ function AnimatedEventItem({
   )
 }
 
-export function EventsList({ events }: EventsListProps) {
+export function EventsList({ events, subjectOf, onSelect, resourceNames = NO_NAMES }: EventsListProps) {
   const [filter, setFilter] = useState('')
 
   const filteredEvents = useMemo(() => {
@@ -139,7 +148,10 @@ export function EventsList({ events }: EventsListProps) {
 
       <div className="space-y-2">
         <AnimatePresence mode="popLayout">
-          {sortedEvents.map((event, idx) => (
+          {sortedEvents.map((event, idx) => {
+            const title = eventTitle(event, resourceNames)
+            const subject = onSelect && subjectOf ? subjectOf(event) : null
+            return (
             <AnimatedEventItem
               key={`${event.sessionId}-${event.seq}`}
               initial={{ opacity: 0, y: -10 }}
@@ -147,17 +159,22 @@ export function EventsList({ events }: EventsListProps) {
               exit={{ opacity: 0, y: -10 }}
               transition={{ delay: idx * 0.02 }}
             >
-              <Card 
+              <Card
                 shadow="sm"
                 className={
-                  event.kind?.includes('failed')
+                  'w-full ' +
+                  (event.kind?.includes('failed')
                     ? 'border-l-4 border-danger'
                     : event.kind?.includes('cancelled')
                     ? 'border-l-4 border-default-400'
                     : event.kind?.includes('body-completed')
                     ? 'border-l-4 border-secondary/50'
-                    : ''
+                    : '')
                 }
+                // A row selects what it is about (S-2): its coroutine, or a resource.
+                {...(subject && onSelect
+                  ? { isPressable: true, 'aria-label': `Select ${title} (event #${event.seq})`, onPress: () => onSelect(subject) }
+                  : {})}
               >
                 <CardBody className="py-3">
                   <div className="flex items-center justify-between">
@@ -203,16 +220,18 @@ export function EventsList({ events }: EventsListProps) {
                           }
                           return (
                             <div className="font-semibold">
-                              {event.label || event.coroutineId}
+                              {title}
                             </div>
                           )
                         })()}
-                        <div className="text-xs text-default-500 mt-1">
-                          ID: <code className="font-mono">{event.coroutineId}</code>
-                          {event.parentCoroutineId && (
-                            <> • Parent: <code className="font-mono">{event.parentCoroutineId}</code></>
-                          )}
-                        </div>
+                        {event.coroutineId && (
+                          <div className="text-xs text-default-500 mt-1">
+                            ID: <code className="font-mono">{event.coroutineId}</code>
+                            {event.parentCoroutineId && (
+                              <> • Parent: <code className="font-mono">{event.parentCoroutineId}</code></>
+                            )}
+                          </div>
+                        )}
                       </div>
                     </div>
                     <div className="text-right text-xs text-default-400 flex flex-col items-end gap-1">
@@ -401,7 +420,8 @@ export function EventsList({ events }: EventsListProps) {
                 </CardBody>
               </Card>
             </AnimatedEventItem>
-          ))}
+            )
+          })}
         </AnimatePresence>
       </div>
     </div>
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
  /** Resource id -> name, so a row about a channel can name it. */
  resourceNames?: ReadonlyMap<string, string>
}

/**
 * The Events tab (spec S-4): the raw event stream, scoped to the page's
 * selection — a coroutine's events, the lock and permit events about it
 * included, or a resource's. A row selects what it is about (S-2).
 */
export function EventsPanel({
  events,
  selection,
  selectionLabel,
  subjectOf,
  onSelect,
  onShowAll,
  resourceNames,
}: EventsPanelProps) {
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
      <EventsList events={scoped} subjectOf={subjectOf} onSelect={onSelect} resourceNames={resourceNames} />
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
// A thin controller: replay's cursor is moved by the real useReplay, through this button.
vi.mock('./replay/ReplayController', () => ({
  ReplayController: ({ replay }: { replay: { seekTo: (i: number) => void } }) => (
    <button type="button" onClick={() => replay.seekTo(0)}>
      Seek to the first event
    </button>
  ),
}))
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

  it('a selected lock that leaves the page\'s events is no longer selected', async () => {
    stream(contended)
    const { rerender } = render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await userEvent.click(screen.getByRole('tab', { name: 'Events' }))
    await userEvent.click(screen.getByRole('button', { name: /Select inventory-lock \(event #1\)/ }))
    await userEvent.click(screen.getByRole('tab', { name: 'Events' }))
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events about inventory-lock')
    // The view's events cleared (Clear events, or the history trimmed past it).
    stream([co('coroutine.started', 'c-1')])
    rerender(<SessionWorkspace sessionId="session-1" startLive />)
    expect(screen.getByTestId('events-scope')).toHaveTextContent('All 1 events in this session')
  })

  it('a channel picked in Evidence becomes the page\'s selection', async () => {
    const channels = [
      ev('ChannelCreated', { channelId: 'ch-1', name: 'orders', capacity: 0, channelType: 'RENDEZVOUS' }),
      ev('ChannelCreated', { channelId: 'ch-2', name: 'invoices', capacity: 0, channelType: 'RENDEZVOUS' }),
      co('coroutine.suspended', 'c-1', { waitKind: 'CHANNEL_SEND', resourceId: 'ch-2', reason: 'channel' }),
    ]
    stream(channels)
    render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await openList()
    expect(within(card('order-1')).getByTestId('activity-line')).toHaveTextContent('waits to send to invoices')
    await userEvent.click(screen.getByRole('tab', { name: /Evidence/ }))
    await userEvent.click(screen.getByRole('button', { name: /Channels/ }))
    await userEvent.click(screen.getByTestId('channel-selector-ch-2'))
    await userEvent.click(screen.getByRole('tab', { name: 'Events' }))
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events about invoices · 2')
  })

  it('in replay, Evidence says what was captured by the cursor, not later', async () => {
    stream([co('coroutine.started', 'c-1'), ...contended])
    render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))
    await userEvent.click(screen.getByRole('button', { name: 'Seek to the first event' }))
    await userEvent.click(screen.getByRole('tab', { name: /Evidence/ }))
    expect(screen.getByTestId('evidence-section-Locks and permits')).toHaveTextContent('none captured')
  })
})
```

(If the real `CoroutineTree` card's accessible name is not `Open source for …` once HeroUI renders it, match it the way `SessionWorkspace.reachability.test.tsx` finds its card.)

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm exec vitest run src/components/SessionWorkspace.selection.test.tsx`
Expected: FAIL — no second lines, no selectable rows, Evidence reads the (empty) stored history.

- [ ] **Step 3: Implement** — `src/lib/selection.ts` gains `resourceIds` (Task 5's listing includes it, with its test), and `SessionWorkspace.tsx`:

```diff
diff --git a/frontend/src/components/SessionWorkspace.tsx b/frontend/src/components/SessionWorkspace.tsx
index 6c9dbeb7..0e73760d 100644
--- a/frontend/src/components/SessionWorkspace.tsx
+++ b/frontend/src/components/SessionWorkspace.tsx
@@ -1,4 +1,4 @@
-import { useState, useEffect, useMemo, useRef } from 'react'
+import { useState, useEffect, useMemo, useRef, useCallback } from 'react'
 import {
   Card,
   CardBody,
@@ -10,7 +10,7 @@ import {
 } from '@heroui/react'
 import { useSession, useSessionEvents } from '@/hooks/use-sessions'
 import { useThreadActivity } from '@/hooks/use-thread-activity'
-import { useEventCategories } from '@/hooks/use-event-categories'
+import { categorizeEvents, useEventCategories } from '@/hooks/use-event-categories'
 import { useWorkspaceReplay } from '@/hooks/use-workspace-replay'
 import { useValidation } from '@/hooks/use-validation'
 import { SSE_FALLBACK_POLL_MS } from '@/lib/poll-interval'
@@ -38,7 +38,7 @@ import { RecordConfirmModal } from './replay/RecordConfirmModal'
 import { ManageShares } from './share/ManageShares'
 import { OrderProcessingView } from './scenarios/OrderProcessingView'
 import { RegistrationFlowView } from './scenarios/RegistrationFlowView'
-import type { ThreadActivity } from '@/types/api'
+import type { ThreadActivity, VizEvent } from '@/types/api'
 import { CoroutineState } from '@/types/api'
 import { isDemoSessionId, sessionDisplayName } from '@/lib/session-kind'
 import { useDockLayout } from '@/hooks/use-dock-layout'
@@ -46,6 +46,9 @@ import { deriveRunOutcome } from '@/lib/run-outcome'
 import { useRecordedSpan } from '@/hooks/use-recorded-span'
 import { countEvidence, evidenceTabLabel } from '@/lib/evidence-summary'
 import { openingDockTab, rememberDockTab, rememberedDockTab } from '@/lib/dock-tab-memory'
+import { projectActivity } from '@/lib/activity'
+import { eventSubject, resourceIds, resourceNames, type Selection } from '@/lib/selection'
+import type { CoroutinePicker } from './sync/CoroutineName'
 
 /** Terminal coroutine states — no further transitions expected. */
 const TERMINAL_STATES = new Set<CoroutineState>([
@@ -95,11 +98,11 @@ export function SessionWorkspace({
   } = useSessionEvents(sessionId)
   const [streamEnabled, setStreamEnabled] = useState(false)
   const [viewMode, setViewMode] = useState<'graph' | 'list'>('graph')
-  // Selected coroutine for the source drawer (RCO-06, D-02/D-06). Lifted here so
-  // both live-view modes (graph/list) share one selection + one mounted drawer.
-  // Null = drawer closed. Wired ONLY at the live "What's running now" mount, so
-  // replay/shared node renders stay non-interactive.
-  const [selectedCoroutineId, setSelectedCoroutineId] = useState<string | null>(null)
+  // The page's one selection (S-2): a coroutine or a resource, read by the
+  // tree, the Inspector, Evidence and Events. Set only in the live view, so
+  // replay and shared renders stay non-interactive (PD-01).
+  const [selection, setSelection] = useState<Selection | null>(null)
+  const selectedCoroutineId = selection?.kind === 'coroutine' ? selection.id : null
   // Active-only "What's running now" view (D-08): completed coroutines collapse
   // into an expandable aggregate, toggled off by default.
   const [showCompleted, setShowCompleted] = useState(false)
@@ -181,7 +184,10 @@ export function SessionWorkspace({
     sessionId,
     scenarioId: scenarioId ?? '',
     scenarioName,
-    onClearEvents: clearEvents,
+    onClearEvents: () => {
+      clearEvents()
+      setSelection(null)
+    },
     onRefetch: () => void refetch(),
   })
   const hasScenario = !!scenarioId
@@ -282,6 +288,48 @@ export function SessionWorkspace({
 
   // What the Evidence tab holds, named on its label (S-4).
   const evidenceCounts = useMemo(() => countEvidence(panelEvents), [panelEvents])
+  // Which Evidence sections have anything, from the same events: in replay,
+  // the cursor's, so a resource created later is not reported yet. The rung
+  // keeps the whole session's categories.
+  const evidenceCategories = useMemo(
+    () => (replayActive ? categorizeEvents(panelEvents) : eventCategories),
+    [replayActive, panelEvents, eventCategories],
+  )
+  // What each coroutine waits for or holds (S-6), and the resources' names,
+  // from the same events as the rest of the page: live, paused or at the
+  // replay cursor — so they refresh with every new event.
+  const activity = useMemo(() => projectActivity(panelEvents), [panelEvents])
+  const names = useMemo(() => resourceNames(panelEvents), [panelEvents])
+  const resources = useMemo(() => resourceIds(panelEvents), [panelEvents])
+  // A coroutine the snapshot does not hold is not offered as a selection:
+  // selecting it would be cleared at once.
+  const coroutineIds = useMemo(() => new Set(panelCoroutines.map(c => c.id)), [panelCoroutines])
+  const subjectOf = useCallback(
+    (event: VizEvent) => {
+      const subject = eventSubject(event)
+      return subject?.kind === 'coroutine' && !coroutineIds.has(subject.id) ? null : subject
+    },
+    [coroutineIds],
+  )
+  const picker = useMemo<CoroutinePicker>(
+    () => ({
+      select: (id: string) => setSelection({ kind: 'coroutine', id }),
+      canSelect: (id: string) => coroutineIds.has(id),
+    }),
+    [coroutineIds],
+  )
+  const selectionLabel =
+    selection === null
+      ? null
+      : selection.kind === 'coroutine'
+        ? (selectedCoroutine?.label ?? selection.id)
+        : (names.get(selection.id) ?? selection.id)
+  // A selection made inside a tab keeps you there; a resource opens its
+  // Evidence section (S-2), which EvidencePanels unfolds and scrolls to.
+  const selectFromEvent = (next: Selection) => {
+    setSelection(next)
+    if (next.kind === 'resource') selectDockTab('evidence')
+  }
   // "Time into the capture" for the thread table: the first retained event.
   const captureStartNanos = useMemo(() => {
     let first: number | null = null
@@ -294,11 +342,14 @@ export function SessionWorkspace({
   // capped-out coroutine still exists in panelCoroutines, so it keeps its drawer +
   // resolved label (WR-04); only a truly-absent id clears the selection so we stop
   // fetching a coroutine that no longer exists.
+  // The same for a resource no longer named by the page's events (Clear
+  // events, or a history trimmed past it): no Evidence target, no events.
   useEffect(() => {
-    if (selectedCoroutineId && !panelCoroutines.some(c => c.id === selectedCoroutineId)) {
-      setSelectedCoroutineId(null)
-    }
-  }, [selectedCoroutineId, panelCoroutines])
+    if (selection === null) return
+    const gone =
+      selection.kind === 'coroutine' ? !coroutineIds.has(selection.id) : !resources.has(selection.id)
+    if (gone) setSelection(null)
+  }, [selection, coroutineIds, resources])
 
   const panelThreadActivity: ThreadActivity | undefined = useMemo(() => {
     if (replayActive) return projectThreadActivity(replay.visibleEvents)
@@ -469,12 +520,13 @@ export function SessionWorkspace({
           }
           isDemo={isDemo}
           selectedCoroutineId={selectedCoroutineId}
+          activity={activity}
           // Selection is LIVE-ONLY (PD-01): replay and the read-only shared
           // view stay presentational, with no added interactivity.
           onSelect={
             isLiveView
               ? (id: string) => {
-                  setSelectedCoroutineId(id)
+                  setSelection({ kind: 'coroutine', id })
                   // A selection is read in the Inspector: show it (S-4).
                   selectDockTab('inspector')
                 }
@@ -540,7 +592,8 @@ export function SessionWorkspace({
               }
               threadActivity={panelThreadActivity}
               // Back to the session summary (T-3) from a selection.
-              onClearSelection={isLiveView ? () => setSelectedCoroutineId(null) : undefined}
+              onClearSelection={isLiveView ? () => setSelection(null) : undefined}
+              activity={selectedCoroutine ? (activity.get(selectedCoroutine.id) ?? null) : null}
             />
           </DockTabPanel>
           <DockTabPanel id="evidence" selected={dockTab === 'evidence'}>
@@ -549,13 +602,17 @@ export function SessionWorkspace({
             <EvidencePanels
               sessionId={sessionId}
               rung={rung}
-              categories={eventCategories}
+              categories={evidenceCategories}
               counts={evidenceCounts}
               replayActive={replayActive}
               readOnly={readOnly}
               threadActivity={panelThreadActivity}
               captureStartNanos={captureStartNanos}
               history={historyFailed ? 'unavailable' : historyPending ? 'loading' : 'ready'}
+              events={panelEvents}
+              selection={isLiveView ? selection : null}
+              picker={isLiveView ? picker : undefined}
+              onSelectResource={isLiveView ? setSelection : undefined}
             />
           </DockTabPanel>
           <DockTabPanel id="events" selected={dockTab === 'events'}>
@@ -564,7 +621,15 @@ export function SessionWorkspace({
                 live flush. It fetches nothing, so mounting later costs no
                 request. Every other panel stays mounted and is hidden. */}
             {dockTab === 'events' && (
-              <EventsPanel events={panelEvents} selectedCoroutine={selectedCoroutine} />
+              <EventsPanel
+                events={panelEvents}
+                selection={isLiveView ? selection : null}
+                selectionLabel={selectionLabel}
+                subjectOf={isLiveView ? subjectOf : undefined}
+                onSelect={isLiveView ? selectFromEvent : undefined}
+                onShowAll={() => setSelection(null)}
+                resourceNames={names}
+              />
             )}
           </DockTabPanel>
           <DockTabPanel id="learn" selected={dockTab === 'learn'}>
```

(Task 11 left an interim `EventsPanel` wiring so its commit compiles; this replaces it. The three workspace test files that mock `use-event-categories` also export the real `categorizeEvents`.)

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
