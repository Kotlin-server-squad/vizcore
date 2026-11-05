# Part 1 · Session page quick wins — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make today's session page show its answer on the first screen: the tree first, honest state words, a failed run that says Failed, a finished demo that shows its coroutines, a neutral held lock, and one live indicator.

**Architecture:** Frontend only, inside today's layout (no dock yet — that is Part 3). Each change is a small edit to an existing component, with the state wording centralised in `lib/coroutine-state-colors.ts` and `lib/state-counts.ts` so the tree, the state bar and the colours keep agreeing.

**Tech Stack:** React 19, TypeScript, HeroUI, Tailwind, Vitest + Testing Library. Run every command from `frontend/`.

**Spec:** [`docs/superpowers/specs/2026-11-05-session-dock-design.md`](../specs/2026-11-05-session-dock-design.md), Part 1 row of "Building it: ten parts", the Part 1 testing bullet, and S-3 / S-6 for wording. Boards: canvas page "2 · Quick wins first" (`S1-Finished`, `S1-Live`).

**Branch:** a fresh worktree from `origin/main`:

```bash
cd /Users/jirihermann/Documents/workspace-vizcore/vizcore
git fetch origin
git worktree add ../vizcore-quick-wins -b feat/session-quick-wins origin/main
cd ../vizcore-quick-wins/frontend && pnpm install
```

---

## File structure

| File | Change | Responsibility |
|---|---|---|
| `src/lib/coroutine-state-colors.ts` | modify | add `stateLabel()` — the one place a state becomes words |
| `src/lib/state-counts.ts` | modify | buckets `active` (was `running`) and a new `waiting` |
| `src/components/StateBar.tsx` | modify | chips "Active" and "Waiting for children" |
| `src/components/CoroutineTree.tsx`, `src/components/CoroutineTreeGraph.tsx` | modify | show `stateLabel(node.state)` instead of the raw enum |
| `src/components/workspace/ScenarioControls.tsx` | modify | run outcome Running / Finished / Failed |
| `src/components/sync/MutexStateIndicator.tsx` | modify | a held lock is neutral |
| `src/components/SessionWorkspace.tsx` | modify | finished runs show their coroutines; tree before explanation and metrics |
| `src/components/workspace/CoroutineCanvas.tsx` | modify | `allFinished` heading, no toggle, no "Nothing running now" |
| `src/components/StructuredConcurrencyInfo.tsx` | rewrite | one folded line "How to read the tree", remembered |
| `src/components/workspace/WorkspaceBody.tsx` | modify | grid first, metric strip below it, no LivePill |
| `src/components/LivePill.tsx`, `src/components/LivePill.test.tsx` | delete | the header's stream chip is the one live indicator |

Tests are updated next to each file.

---

### Task 1: State words in one place

**Files:**
- Modify: `src/lib/coroutine-state-colors.ts` (append after the colour map exports)
- Modify: `src/components/CoroutineTree.tsx:171`, `src/components/CoroutineTreeGraph.tsx` (the `{node.state}` line inside `NodeCard`, ~line 509)
- Test: `src/lib/coroutine-state-colors.test.ts`, `src/components/CoroutineTree.test.tsx:63,96`

- [ ] **Step 1: Write the failing test** — append to `src/lib/coroutine-state-colors.test.ts`:

```ts
describe('stateLabel', () => {
  it('names every state in plain words, never "Running"', () => {
    expect(stateLabel(CoroutineState.CREATED)).toBe('Created')
    expect(stateLabel(CoroutineState.ACTIVE)).toBe('Active')
    expect(stateLabel(CoroutineState.SUSPENDED)).toBe('Suspended')
    expect(stateLabel(CoroutineState.WAITING_FOR_CHILDREN)).toBe('Waiting for children')
    expect(stateLabel(CoroutineState.COMPLETED)).toBe('Completed')
    expect(stateLabel(CoroutineState.CANCELLED)).toBe('Cancelled')
    expect(stateLabel(CoroutineState.FAILED)).toBe('Failed')
  })

  it('shows an unknown state as recorded rather than hiding it', () => {
    expect(stateLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })
})
```

Add `stateLabel` to the existing import from `./coroutine-state-colors` at the top of the test file.

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/lib/coroutine-state-colors.test.ts`
Expected: FAIL — `stateLabel` is not exported.

- [ ] **Step 3: Implement** — append to `src/lib/coroutine-state-colors.ts`:

```ts
// ---- Words ----------------------------------------------------------------

/**
 * What each recorded state is called on screen (spec S-6). "Active", never
 * "Running": vizcore records that a coroutine is active, not that it holds a
 * thread at this instant.
 */
const STATE_LABEL: Record<string, string> = {
  CREATED: 'Created',
  ACTIVE: 'Active',
  SUSPENDED: 'Suspended',
  WAITING_FOR_CHILDREN: 'Waiting for children',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  FAILED: 'Failed',
}

/** The state's on-screen name; an unknown state is shown as recorded. */
export function stateLabel(state: string): string {
  return STATE_LABEL[state] ?? state
}
```

- [ ] **Step 4: Use it in both trees**

In `src/components/CoroutineTree.tsx`, add `stateLabel` to the import from `@/lib/coroutine-state-colors` (line 8) and replace line 171 `{node.state}` with `{stateLabel(node.state)}`.

In `src/components/CoroutineTreeGraph.tsx`, add `stateLabel` to its import from `@/lib/coroutine-state-colors` and in `NodeCard` replace

```tsx
              {node.state}
              {node.state === 'ACTIVE' && <span className="ml-2 inline-block">●</span>}
```

with

```tsx
              {stateLabel(node.state)}
              {node.state === 'ACTIVE' && <span className="ml-2 inline-block">●</span>}
```

The graph's floating badge (`CoroutineTreeGraph.tsx:75-78` and `:345`) counts `isActiveState`, which includes
WAITING_FOR_CHILDREN, and says "actively running". Make it count only the Active bucket and say so:

```tsx
  // The badge counts what the state bar calls Active (CREATED or ACTIVE); a parent
  // waiting for its children is not running its body (spec S-6).
  const activeCount = useMemo(
    () => coroutines.filter(c => c.state === 'ACTIVE' || c.state === 'CREATED').length,
    [coroutines]
  )
```

```tsx
                <span>{activeCount} active</span>
```

In `src/components/CoroutineTreeGraph.test.tsx:142` change `'2 coroutines actively running'` to `'2 active'`, and add
next to that test:

```tsx
  it('does not count a parent waiting for its children as active', () => {
    const coroutines: CoroutineNode[] = [
      makeCoroutine({ id: 'p', label: 'Parent', state: CoroutineState.WAITING_FOR_CHILDREN }),
      makeCoroutine({ id: 'c', label: 'Child', parentId: 'p', state: CoroutineState.ACTIVE }),
    ]
    render(<CoroutineTreeGraph coroutines={coroutines} />)
    expect(screen.getByText('1 active')).toBeInTheDocument()
  })
```

(If `makeCoroutine` takes no `parentId`, drop it — the count does not depend on the tree shape.)

In `src/components/CoroutineTree.test.tsx`, change `getByText('ACTIVE')` (line 63) to `getByText('Active')` and `getByText('WAITING_FOR_CHILDREN')` (line 96) to `getByText('Waiting for children')`.

- [ ] **Step 5: Run the tests**

Run: `pnpm vitest run src/lib/coroutine-state-colors.test.ts src/components/CoroutineTree.test.tsx src/components/CoroutineTreeGraph.test.tsx`
Expected: PASS. If another test reads a raw state from a node (`grep -rn "getByText('\(ACTIVE\|SUSPENDED\|COMPLETED\|FAILED\|CANCELLED\|CREATED\)')" src`), switch it to the label.

- [ ] **Step 6: Commit**

```bash
git add src/lib/coroutine-state-colors.ts src/lib/coroutine-state-colors.test.ts src/components/CoroutineTree.tsx src/components/CoroutineTree.test.tsx src/components/CoroutineTreeGraph.tsx src/components/CoroutineTreeGraph.test.tsx
git commit -m "feat(frontend): name coroutine states in plain words on the tree"
```

---

### Task 2: The state bar says Active, and Waiting for children is its own chip

**Files:**
- Modify: `src/lib/state-counts.ts`, `src/components/StateBar.tsx:50-56`
- Test: `src/lib/state-counts.test.ts`, `src/components/StateBar.test.tsx`, `src/lib/coroutine-state-colors.test.ts:73-89`, `src/components/SessionWorkspace.test.tsx:974`

- [ ] **Step 1: Write the failing tests** — in `src/lib/state-counts.test.ts`:

Replace the empty-session expectation with

```ts
    expect(c).toEqual({
      active: 0,
      waiting: 0,
      suspended: 0,
      completed: 0,
      cancelled: 0,
      failed: 0,
      total: 0,
      leaks: 0,
    })
```

Replace the two tests "counts WAITING_FOR_CHILDREN as running" and "counts CREATED as running" with

```ts
  it('gives WAITING_FOR_CHILDREN its own bucket — the body is done, the scope is not', () => {
    const c = deriveStateCounts([
      node(CoroutineState.ACTIVE),
      node(CoroutineState.WAITING_FOR_CHILDREN),
    ])
    expect(c.active).toBe(1)
    expect(c.waiting).toBe(1)
  })

  it('counts CREATED as active — it is in flight, not finished', () => {
    expect(deriveStateCounts([node(CoroutineState.CREATED)]).active).toBe(1)
  })
```

and in "never silently drops a coroutine" use
`expect(c.active + c.waiting + c.suspended + c.completed + c.cancelled + c.failed).toBe(all.length)`.

Replace the `matchesFilter` test "agrees with deriveStateCounts about what running means" with:

```ts
  it('agrees with deriveStateCounts about what active and waiting mean', () => {
    expect(matchesFilter(CoroutineState.CREATED, 'active')).toBe(true)
    expect(matchesFilter(CoroutineState.ACTIVE, 'active')).toBe(true)
    expect(matchesFilter(CoroutineState.WAITING_FOR_CHILDREN, 'active')).toBe(false)
    expect(matchesFilter(CoroutineState.WAITING_FOR_CHILDREN, 'waiting')).toBe(true)
    expect(matchesFilter(CoroutineState.SUSPENDED, 'active')).toBe(false)
  })
```

In "counts leaks separately from the state buckets" (~line 107) change `expect(counts.running).toBe(1)` to
`expect(counts.active).toBe(1)` and its comment to "so it is also counted as active". Then
`grep -n "running" src/lib/state-counts.test.ts` must print nothing; rename any remaining `'running'` filter by
what it tests (`'active'` for CREATED/ACTIVE, `'waiting'` for WAITING_FOR_CHILDREN), not mechanically.

In `src/components/StateBar.test.tsx`: in the `counts` fixture replace `running: 47,` with `active: 47,\n  waiting: 2,` and change `total` to `1282`; replace every `/running 47/i` with `/active 47/i`, the "always shows Running" test's `running: 0` with `active: 0,\n        waiting: 0,` and `/running 0/i` with `/active 0/i` (rename the test "always shows Active …"). Every other complete `StateCounts` fixture in the file gets the same pair: `running:` becomes `active:` and a `waiting: 0` is added (TypeScript reports TS2741 for any that lacks it). Add:

```ts
describe('StateBar waiting chip', () => {
  it('shows Waiting for children as its own chip when any parent waits', () => {
    renderBar()
    expect(screen.getByRole('button', { name: 'Waiting for children 2' })).toBeInTheDocument()
  })

  it('selects the waiting filter', async () => {
    const { onFilterChange } = renderBar()
    await userEvent.click(screen.getByRole('button', { name: 'Waiting for children 2' }))
    expect(onFilterChange).toHaveBeenCalledWith('waiting')
  })
})
```

In `src/lib/coroutine-state-colors.test.ts:77` use the bucket list `(['active', 'waiting', 'suspended', 'completed', 'cancelled', 'failed'] as const)` and in `BUCKET_HUE` replace `running: palette.primary,` with `active: palette.primary,\n    waiting: palette.primary,`.

In `src/components/SessionWorkspace.test.tsx:974` change `'Running 1'` to `'Active 1'`.

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm vitest run src/lib/state-counts.test.ts src/components/StateBar.test.tsx src/lib/coroutine-state-colors.test.ts`
Expected: FAIL — `active` / `waiting` do not exist; TypeScript errors on the fixtures.

- [ ] **Step 3: Implement** — in `src/lib/state-counts.ts`:

```ts
export type StateFilter =
  | 'all'
  | 'active'
  | 'waiting'
  | 'suspended'
  | 'completed'
  | 'cancelled'
  | 'failed'
  /** Cross-cutting: a still-active coroutine alive past the leak threshold. */
  | 'leaks'

export interface StateCounts {
  active: number
  /** WAITING_FOR_CHILDREN: the body finished, the scope has not. */
  waiting: number
  suspended: number
  completed: number
  cancelled: number
  failed: number
  /** Every coroutine in the snapshot. The six state buckets always sum to this. */
  total: number
  /**
   * Potential leaks. NOT one of the six buckets and not part of `total` — a
   * leak is a heuristic flag on a still-active coroutine, so a leaked coroutine
   * is also counted under its state.
   */
  leaks: number
}
```

Replace the `STATE_BUCKET` doc comment's first call-out with: "`WAITING_FOR_CHILDREN` gets its **own** bucket. It is still live work, but a parent whose body has finished is a different fact from one that is running its body, and the structured-concurrency demos are about exactly that difference (spec S-6)." Then:

```ts
const STATE_BUCKET = {
  [CoroutineState.CREATED]: 'active',
  [CoroutineState.ACTIVE]: 'active',
  [CoroutineState.WAITING_FOR_CHILDREN]: 'waiting',
  [CoroutineState.SUSPENDED]: 'suspended',
  [CoroutineState.COMPLETED]: 'completed',
  [CoroutineState.CANCELLED]: 'cancelled',
  [CoroutineState.FAILED]: 'failed',
} as const satisfies Record<CoroutineState, Exclude<StateFilter, 'all'>>
```

In `deriveStateCounts` initialise `active: 0, waiting: 0,` instead of `running: 0,`, and change both fallbacks (`counts[bucket ?? 'running']`, `STATE_BUCKET[state] ?? 'running'`) to `'active'`. Update the comment above `matchesFilter` to say "what "Active" means".

In `src/components/StateBar.tsx` replace the first chip with two:

```ts
    { filter: 'active', label: 'Active', count: counts.active, color: 'var(--primary)', always: true },
    { filter: 'waiting', label: 'Waiting for children', count: counts.waiting, color: 'var(--primary)' },
```

- [ ] **Step 4: Typecheck and run**

Run: `pnpm exec tsc --noEmit && pnpm vitest run src/lib src/components/StateBar.test.tsx src/components/SessionWorkspace.test.tsx`
Expected: PASS. `tsc` finds any other reader of `counts.running` or the `'running'` filter; rename it the same way.

- [ ] **Step 5: Commit**

```bash
git add -A src/lib/state-counts.ts src/lib/state-counts.test.ts src/components/StateBar.tsx src/components/StateBar.test.tsx src/lib/coroutine-state-colors.test.ts src/components/SessionWorkspace.test.tsx
git commit -m "feat(frontend): state bar says Active, and Waiting for children is its own chip"
```

---

### Task 3: A run with a failure reads Failed, in red

**Files:**
- Modify: `src/components/workspace/ScenarioControls.tsx:52-62,116-146`
- Test: `src/components/workspace/ScenarioControls.test.tsx`, `src/components/SessionWorkspace.test.tsx:399-437`

- [ ] **Step 1: Write the failing tests** — append to `src/components/workspace/ScenarioControls.test.tsx` (inside `describe('ScenarioControls', …)`):

```tsx
  const finishedWith = (...states: string[]) =>
    ({
      sessionId: 's-1',
      coroutineCount: states.length,
      coroutines: states.map((state, i) => ({
        id: `c${i}`, jobId: `j${i}`, parentId: null, scopeId: 's', label: `c${i}`, state,
      })),
    }) as unknown as SessionSnapshot

  const renderWith = (session: SessionSnapshot) =>
    render(
      <HeroUIProvider>
        <ScenarioControls
          sessionId="s-1"
          scenarioId="exception-propagation"
          session={session}
          onClearEvents={vi.fn()}
          onRefetch={vi.fn()}
        />
      </HeroUIProvider>,
    )

  it('reads Finished when every coroutine completed or was cancelled', () => {
    renderWith(finishedWith('COMPLETED', 'CANCELLED'))
    expect(screen.getByText('Scenario finished')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /scenario finished/i })).toBeDisabled()
  })

  it('reads Failed in red — never green Completed — when any coroutine failed', () => {
    renderWith(finishedWith('COMPLETED', 'FAILED', 'CANCELLED'))
    expect(screen.getByText('Scenario failed: 1 coroutine failed')).toBeInTheDocument()
    const button = screen.getByRole('button', { name: /scenario failed/i })
    expect(button).toBeDisabled()
    expect(button.className).toMatch(/danger/)
    expect(button.className).not.toMatch(/success/)
    expect(screen.queryByText(/completed/i)).toBeNull()
  })

  it('reads Failed as soon as a coroutine failed, even while others still run', () => {
    renderWith(finishedWith('FAILED', 'ACTIVE'))
    expect(screen.getByText('Scenario failed: 1 coroutine failed')).toBeInTheDocument()
    expect(screen.queryByText('Scenario is running')).toBeNull()

    renderWith(finishedWith('FAILED', 'WAITING_FOR_CHILDREN'))
    expect(screen.getAllByText('Scenario failed: 1 coroutine failed')).toHaveLength(2)
  })

  it('counts several failures in the plural', () => {
    renderWith(finishedWith('FAILED', 'FAILED'))
    expect(screen.getByText('Scenario failed: 2 coroutines failed')).toBeInTheDocument()
  })
```

In `src/components/SessionWorkspace.test.tsx:436` change `/scenario completed/i` to `/scenario finished/i` and its comment to "a finished indicator". Check the neighbouring test "shows disabled "Scenario Running" button when some coroutines are still active" (~line 439): its fixture must contain no FAILED coroutine, or it now reads Failed; if it does, change that coroutine to `ACTIVE`.

- [ ] **Step 2: Run and see them fail**

Run: `pnpm vitest run src/components/workspace/ScenarioControls.test.tsx`
Expected: FAIL — the component still says "Scenario completed".

- [ ] **Step 3: Implement** — in `ScenarioControls.tsx` replace the derivation with:

```tsx
  // Run outcome (spec S-3):
  //   notStarted — no coroutines seen yet
  //   failed     — at least one coroutine FAILED, whether or not others still
  //                run. Never green "completed": a run that failed something did
  //                not succeed, and a supervisor's surviving children do not undo it.
  //   running    — none failed, at least one is not terminal
  //   finished   — all terminal, none failed
  const { scenarioState, failedCount } = useMemo(() => {
    if (!session || session.coroutineCount === 0 || session.coroutines.length === 0) {
      return { scenarioState: 'notStarted' as const, failedCount: 0 }
    }
    const failed = session.coroutines.filter(c => c.state === CoroutineState.FAILED).length
    if (failed > 0) return { scenarioState: 'failed' as const, failedCount: failed }
    const allTerminal = session.coroutines.every(c => TERMINAL_STATES.has(c.state))
    return { scenarioState: allTerminal ? ('finished' as const) : ('running' as const), failedCount: 0 }
  }, [session])
```

Replace the status paragraph and the first button block with:

```tsx
            <p className={scenarioState === 'failed' ? 'text-sm text-danger' : 'text-sm text-default-500'}>
              {scenarioState === 'notStarted' && 'Ready to run the scenario'}
              {scenarioState === 'running' && 'Scenario is running'}
              {scenarioState === 'finished' && 'Scenario finished'}
              {scenarioState === 'failed' &&
                `Scenario failed: ${failedCount} ${failedCount === 1 ? 'coroutine' : 'coroutines'} failed`}
            </p>
          </div>
          <div className="flex gap-2">
            {scenarioState === 'finished' || scenarioState === 'failed' ? (
              <Button
                color={scenarioState === 'failed' ? 'danger' : 'success'}
                size="lg"
                variant="flat"
                startContent={<FiPlay />}
                isDisabled
              >
                {scenarioState === 'failed' ? 'Scenario Failed' : 'Scenario Finished'}
              </Button>
            ) : (
```

(The rest of the button block is unchanged.)

- [ ] **Step 4: Run the tests**

Run: `pnpm vitest run src/components/workspace/ScenarioControls.test.tsx src/components/SessionWorkspace.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/components/workspace/ScenarioControls.tsx src/components/workspace/ScenarioControls.test.tsx src/components/SessionWorkspace.test.tsx
git commit -m "fix(frontend): a run with a failed coroutine reads Failed, not Completed"
```

---

### Task 4: A held lock is neutral

**Files:**
- Modify: `src/components/sync/MutexStateIndicator.tsx:11-28,46-53`
- Test: `src/components/sync/SyncPanel.test.tsx` (the "renders locked state" test, ~line 125)

- [ ] **Step 1: Write the failing test** — at the end of the "renders locked state" test add:

```tsx
    // Holding a lock is normal work, not an error (spec Problem 8): neutral, never red.
    const chip = screen.getByTestId('lock-status')
    expect(chip.className).not.toMatch(/danger/)
    expect(chip.className).toMatch(/default/)
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest run src/components/sync/SyncPanel.test.tsx -t "renders locked state"`
Expected: FAIL — the chip carries `danger` classes.

- [ ] **Step 3: Implement** — in `MutexStateIndicator.tsx`, change the locked glow to neutral grey and the chip colour to `default`:

```tsx
        mutex.isLocked
          ? {
              boxShadow: [
                '0 0 0 0 rgba(113, 113, 122, 0)',
                '0 0 0 4px rgba(113, 113, 122, 0.15)',
                '0 0 0 0 rgba(113, 113, 122, 0)',
              ],
            }
```

```tsx
                color={mutex.isLocked ? 'default' : 'success'}
```

- [ ] **Step 4: Run it**

Run: `pnpm vitest run src/components/sync/SyncPanel.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/components/sync/MutexStateIndicator.tsx src/components/sync/SyncPanel.test.tsx
git commit -m "fix(frontend): show a held lock as neutral, not red"
```

---

### Task 5: A finished run shows its coroutines

**Files:**
- Modify: `src/components/SessionWorkspace.tsx:174-196,406-416`, `src/components/workspace/CoroutineCanvas.tsx`
- Test: `src/components/SessionWorkspace.test.tsx` (the "What's running now" describe, ~lines 876-1081)

- [ ] **Step 1: Write the failing tests** — in the describe `SessionWorkspace active-only "What's running now" view`:

Replace the test "surfaces a filtered state even when every coroutine is terminal" with:

```tsx
  it('shows every coroutine of a finished run, not "Nothing running now"', async () => {
    mountSession([
      coro('a-failed', 'FAILED'),
      coro('b-failed', 'FAILED'),
      coro('c-cancelled', 'CANCELLED'),
    ])

    expect(screen.queryByText('Nothing running now')).not.toBeInTheDocument()
    expect(screen.getByText('Everything has finished')).toBeInTheDocument()
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute(
      'data-ids',
      'a-failed,b-failed,c-cancelled',
    )
    // Nothing is live, so there is nothing to hide the completed ones behind.
    expect(screen.queryByText(/Show completed|Hide completed/)).toBeNull()

    // A chip still narrows the finished run.
    await userEvent.click(screen.getByRole('button', { name: 'Failed 2' }))
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-ids', 'a-failed,b-failed')
  })
```

Replace "says everything finished, not "start your app", when only completed coroutines remain" with:

```tsx
  it('shows the completed coroutines of a finished demo, not "start your app"', () => {
    mountSession([coro('c-completed', 'COMPLETED')])
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-ids', 'c-completed')
    expect(screen.queryByText(/Start your instrumented app/)).not.toBeInTheDocument()
  })

  it('keeps the active-only default while anything is still live', () => {
    mountSession([coro('a-active', 'ACTIVE'), coro('c-completed', 'COMPLETED')])
    expect(screen.getByText("What's running now")).toBeInTheDocument()
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-ids', 'a-active')
  })

  it('caps a finished run at the node cap and counts the rest', () => {
    mountSession(Array.from({ length: 205 }, (_, i) => coro(`c-${i}`, 'COMPLETED')))
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-count', '200')
    expect(screen.getByText('5 more coroutines')).toBeInTheDocument()
  })

  it('counts "more" from what the chip selects, not from the whole run', async () => {
    mountSession([
      ...Array.from({ length: 205 }, (_, i) => coro(`c-${i}`, 'COMPLETED')),
      coro('f-1', 'FAILED'),
    ])
    await userEvent.click(screen.getByRole('button', { name: 'Failed 1' }))
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-ids', 'f-1')
    expect(screen.queryByText(/more coroutines/)).toBeNull()
  })
```

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm vitest run src/components/SessionWorkspace.test.tsx -t "What's running now"`
Expected: FAIL — "Nothing running now" is still shown.

- [ ] **Step 3: Implement** — in `SessionWorkspace.tsx` replace the active-only memo and `renderedCoroutines` with:

```tsx
  // Active-only "What's running now" derivation (D-08). Default the live view to
  // the non-terminal set, cap rendered nodes at NODE_CAP, and surface the
  // remainder ("N more") + the collapsed completed aggregate ("Show completed").
  // When nothing is live any more the run is over, and hiding its coroutines
  // would leave an empty tree: a finished run shows all of them (spec Part 1).
  const { completedCount, activeCoroutines, allFinished } = useMemo(() => {
    const active = panelCoroutines.filter(c => !TERMINAL_STATES.has(c.state as CoroutineState))
    return {
      completedCount: panelCoroutines.length - active.length,
      activeCoroutines: active,
      allFinished: active.length === 0 && panelCoroutines.length > 0,
    }
  }, [panelCoroutines])
  // What the tree/graph render, and how many the cap leaves out — both from the
  // same pool, so "N more" always counts what this view would have shown.
  const { renderedCoroutines, moreCount } = useMemo(() => {
    // A named chip selects that state explicitly across the whole snapshot, and
    // bypasses the active-only default — you asked for completed, you get
    // completed. With no chip, the active set; everything when the user expands
    // the completed aggregate or when the run is over.
    const pool =
      stateFilter !== 'all'
        ? selectCoroutines(panelCoroutines, stateFilter, leakIds)
        : showCompleted || allFinished
          ? panelCoroutines
          : activeCoroutines
    const shown = pool.slice(0, NODE_CAP)
    return { renderedCoroutines: shown, moreCount: pool.length - shown.length }
  }, [stateFilter, showCompleted, allFinished, panelCoroutines, activeCoroutines, leakIds])
```

and pass `allFinished={allFinished}` to `<CoroutineCanvas …/>`. `shownCoroutines` is gone; `moreCount` now comes from the second memo (the `moreCount={moreCount}` prop line stays as it is).

The old behaviour this changes on purpose: with the completed aggregate expanded, "N more" used to count only active coroutines past the cap; it now counts what the expanded view leaves out.

In `CoroutineCanvas.tsx` add the prop:

```tsx
  /** Nothing is live and the canvas shows the whole finished run (spec Part 1). */
  allFinished?: boolean
```

destructure it with `allFinished = false`, and replace the header row and the `completedCount > 0` empty-state branch:

```tsx
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">
          {allFinished ? 'Everything has finished' : <>What&apos;s running now</>}
        </h3>
        {completedCount > 0 && !allFinished && (
```

(keep the Button body as it is), and in the empty-state ternary delete the `completedCount > 0 ? (<EmptyState title="Nothing running now" …/>) :` arm so it starts at `isDemo ? (`. A finished run is never empty now, so that arm cannot be reached.

- [ ] **Step 4: Run the tests**

Run: `pnpm vitest run src/components/SessionWorkspace.test.tsx src/components/SessionWorkspace.reachability.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/components/SessionWorkspace.tsx src/components/workspace/CoroutineCanvas.tsx src/components/SessionWorkspace.test.tsx
git commit -m "feat(frontend): a finished run shows its coroutines instead of an empty tree"
```

---

### Task 6: "How to read the tree" — one folded, remembered line

**Files:**
- Rewrite: `src/components/StructuredConcurrencyInfo.tsx`
- Test: `src/components/StructuredConcurrencyInfo.test.tsx`

- [ ] **Step 1: Write the failing tests** — replace the file's three tests with:

```tsx
import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { StructuredConcurrencyInfo, HOW_TO_READ_KEY } from './StructuredConcurrencyInfo'

const open = () => userEvent.click(screen.getByRole('button', { name: /how to read the tree/i }))

describe('StructuredConcurrencyInfo', () => {
  beforeEach(() => localStorage.clear())

  it('is one closed line by default, so the tree stays near the top', () => {
    render(<StructuredConcurrencyInfo />)
    const toggle = screen.getByRole('button', { name: /how to read the tree/i })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('Failure Propagation')).toBeNull()
  })

  it('opens to the explanation and the legend, and remembers that', async () => {
    const { unmount } = render(<StructuredConcurrencyInfo />)
    await open()
    expect(screen.getByText('Failure Propagation')).toBeInTheDocument()
    expect(screen.getByText('Suspended')).toBeInTheDocument()
    expect(localStorage.getItem(HOW_TO_READ_KEY)).toBe('open')

    unmount()
    render(<StructuredConcurrencyInfo />)
    expect(screen.getByRole('button', { name: /how to read the tree/i })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
  })

  it('remembers closing it again', async () => {
    localStorage.setItem(HOW_TO_READ_KEY, 'open')
    render(<StructuredConcurrencyInfo />)
    await open()
    expect(localStorage.getItem(HOW_TO_READ_KEY)).toBe('closed')
  })

  it('states the parent becomes FAILED, and the siblings are cancelled', async () => {
    render(<StructuredConcurrencyInfo />)
    await open()
    const text = screen.getByText(/when a child coroutine throws/i).closest('div')!.textContent ?? ''
    expect(text).toMatch(/parent.*which gets FAILED/i)
    expect(text).not.toMatch(/which gets CANCELLED/i)
    expect(text).toMatch(/sibling.*cancel/i)
  })
})
```

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm vitest run src/components/StructuredConcurrencyInfo.test.tsx`
Expected: FAIL — no toggle, no `HOW_TO_READ_KEY` export.

- [ ] **Step 3: Implement** — rewrite `StructuredConcurrencyInfo.tsx`. Keep the three explanation blocks exactly as they are today (moved inside the open body), and give the legend a Suspended entry:

```tsx
import { useState } from 'react'
import { motion } from 'framer-motion'
import { FiAlertTriangle, FiCheckCircle, FiChevronDown, FiChevronRight, FiClock, FiInfo } from 'react-icons/fi'

/** localStorage key for whether "How to read the tree" is open; remembered per browser. */
export const HOW_TO_READ_KEY = 'vizcore.howToReadTree'

function readOpen(): boolean {
  try {
    return localStorage.getItem(HOW_TO_READ_KEY) === 'open'
  } catch {
    return false
  }
}

/**
 * How to read the tree (spec Part 1): the structured-concurrency explanation and
 * the colour legend, folded into one line that is closed by default, so the
 * tree is the first thing on the page. Opening or closing it is remembered.
 */
export function StructuredConcurrencyInfo() {
  const [open, setOpen] = useState(readOpen)

  const toggle = () => {
    const next = !open
    setOpen(next)
    try {
      localStorage.setItem(HOW_TO_READ_KEY, next ? 'open' : 'closed')
    } catch {
      // Private mode or storage full: the fold still works for this visit.
    }
  }

  return (
    <div className="rounded-medium border border-default-200 bg-content1">
      <button
        type="button"
        aria-expanded={open}
        aria-controls="how-to-read-tree"
        onClick={toggle}
        className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm"
      >
        {open ? <FiChevronDown className="h-4 w-4" /> : <FiChevronRight className="h-4 w-4" />}
        <FiInfo className="h-4 w-4 text-primary" />
        <span className="font-semibold">How to read the tree</span>
        {!open && (
          <span className="text-default-500">— states, colours, and how failures spread</span>
        )}
      </button>
      {open && (
        <div id="how-to-read-tree" className="space-y-3 px-3 pb-3 text-sm">
          {/* The three explanation blocks, unchanged from the old card:
              WAITING_FOR_CHILDREN State, Normal Completion, Failure Propagation. */}
          …copy the three <motion.div> blocks verbatim from the current file…

          <div className="mt-3 rounded-lg border border-default-200 p-3">
            <div className="text-xs font-semibold text-default-700 mb-2">Visual Indicators:</div>
            <div className="grid grid-cols-2 gap-2 text-xs">
              <div className="flex items-center gap-2">
                <span className="inline-block h-2 w-2 rounded-full bg-primary animate-pulse"></span>
                <span>Active / Waiting for children</span>
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
          </div>
        </div>
      )}
    </div>
  )
}
```

The `…copy…` line is the only placeholder: paste the three existing `<motion.div>` blocks (old lines 13–62) there unchanged; `motion`, `FiClock`, `FiCheckCircle`, `FiAlertTriangle` stay imported for them. Drop the unused `Card*` import.

- [ ] **Step 4: Run the tests**

Run: `pnpm vitest run src/components/StructuredConcurrencyInfo.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/components/StructuredConcurrencyInfo.tsx src/components/StructuredConcurrencyInfo.test.tsx
git commit -m "feat(frontend): fold the structured-concurrency card into 'How to read the tree'"
```

---

### Task 7: The tree first, the metrics below it, one live indicator

**Files:**
- Modify: `src/components/workspace/WorkspaceBody.tsx`, `src/components/SessionWorkspace.tsx:369-392`
- Delete: `src/components/LivePill.tsx`, `src/components/LivePill.test.tsx`
- Test: `src/components/workspace/WorkspaceBody.test.tsx:49-67,227-244`, `src/components/SessionWorkspace.test.tsx:112-116,1041-1050`, `src/components/SessionWorkspace.reachability.test.tsx:83,279`

- [ ] **Step 1: Write the failing tests**

In `WorkspaceBody.test.tsx`, replace "renders the LIVE pill + the four metric-tile labels in the header strip" with:

```tsx
  it('puts the canvas first and the metric tiles below it, with no second LIVE badge', () => {
    render(
      <WorkspaceBody
        sessionId="s-1"
        streamEnabled
        readOnly={false}
        showMetrics
        liveList={<div data-testid="live-list" />}
      />,
    )

    // The header's stream chip is the one live indicator (spec Part 1).
    expect(screen.queryByText('LIVE')).toBeNull()

    expect(screen.getByText('Active')).toBeInTheDocument()
    expect(screen.getByText('Peak')).toBeInTheDocument()
    expect(screen.getByText('Throughput')).toBeInTheDocument()
    expect(screen.getByText('Dispatcher utilization')).toBeInTheDocument()

    // The tree is above the metrics in document order.
    const canvas = screen.getByTestId('live-list')
    const tiles = screen.getByText('Peak')
    expect(canvas.compareDocumentPosition(tiles) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
```

and in "renders no metric strip and no LIVE pill when showMetrics is false" delete the two `LIVE` / `NOT LIVE` lines (rename it "renders no metric strip when showMetrics is false").

In `SessionWorkspace.test.tsx` delete the `vi.mock('./LivePill', …)` block (lines 112–116) and, in "mounts the docked metrics panel…", delete the `live-pill` assertion and its comment (rename the test "mounts the metric strip below the live canvas"). Add to the same describe:

```tsx
  it('puts the tree above the explanation', () => {
    mountSession([coro('a-active', 'ACTIVE')])
    const tree = screen.getByTestId('coroutine-tree-graph')
    const info = screen.getByTestId('structured-concurrency-info')
    expect(tree.compareDocumentPosition(info) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
```

In `SessionWorkspace.reachability.test.tsx` delete the `vi.mock('./LivePill', …)` line (83); in "renders the workspace body (metric strip + canvas) in the live view" delete `expect(screen.getByTestId('live-pill')).toBeInTheDocument()` (~line 282) and change the comment above it to "The body mounts the metric tiles exactly once."; in "renders the read-only shared view…" delete `expect(screen.queryByTestId('live-pill')).toBeNull()` (~line 300) and drop "+ LIVE pill" from its comment. Then `grep -rn "live-pill\|LivePill" src` must print nothing.

- [ ] **Step 2: Run them and see them fail**

Run: `pnpm vitest run src/components/workspace/WorkspaceBody.test.tsx src/components/SessionWorkspace.test.tsx`
Expected: FAIL — LIVE is rendered, tiles come before the canvas, the explanation comes before the tree.

- [ ] **Step 3: Implement `WorkspaceBody.tsx`** — drop the `LivePill` import and the `streamEnabled`/`isConnected` use in the body (keep both props in the interface for now, documented as unused by the body, or remove them and their two props at the call site — prefer removing: `SessionWorkspace` passes them only for the pill). Move the strip under the grid:

```tsx
  return (
    <div className="mt-8 rounded-xl bg-content1 border-t-2 border-primary min-h-[200px]">
      {/* Two-column body: canvas + 320px inspector. … (comment unchanged) */}
      <div className="grid grid-cols-[minmax(0,1fr)_320px] gap-8 p-6">
        …unchanged…
      </div>

      {/* Metric tiles, below the tree (spec Part 1: the tree comes first). The
          live indicator is the header's stream chip; the body no longer has one. */}
      {showMetrics && (
        <div className="px-6 pb-6">
          <SessionMetrics sessionId={sessionId} enabled={!readOnly} showLeaks={false} />
        </div>
      )}
    </div>
  )
```

Update the component doc comment: "A two-column grid — the canvas and a single inline amber `LeakList` on the left, the ~320px inspector on the right — over a strip of metric tiles."

If you removed `streamEnabled` / `isConnected` from `WorkspaceBodyProps`, delete them from the `<WorkspaceBody …>` call in `SessionWorkspace.tsx` and from every `<WorkspaceBody` render in `WorkspaceBody.test.tsx` (`tsc` lists them).

- [ ] **Step 4: Implement the order in `SessionWorkspace.tsx`** — move these two blocks from above `<StateBar>` to just after `</WorkspaceBody>` (before `<EvidencePanels>`), unchanged:

```tsx
      {/* How to read the tree — folded, below the tree (spec Part 1). */}
      {session.coroutineCount > 0 && <StructuredConcurrencyInfo />}

      {/* Scenario Pipeline View - shown for realistic scenarios */}
      {scenarioId === 'order-processing' && allEvents.length > 0 && (
        <OrderProcessingView events={allEvents} />
      )}
      {scenarioId === 'user-registration' && allEvents.length > 0 && (
        <RegistrationFlowView events={allEvents} />
      )}
```

The page order is now: header, replay bar, scenario controls, state bar, workspace body (tree + inspector, then metrics), how to read the tree, pipeline views, evidence.

- [ ] **Step 5: Delete the pill**

```bash
git rm src/components/LivePill.tsx src/components/LivePill.test.tsx
grep -rn "LivePill" src && echo "still referenced" || echo "gone"
```

Expected: `gone`.

- [ ] **Step 6: Run the tests**

Run: `pnpm exec tsc --noEmit && pnpm vitest run src/components`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A src/components/workspace/WorkspaceBody.tsx src/components/workspace/WorkspaceBody.test.tsx src/components/SessionWorkspace.tsx src/components/SessionWorkspace.test.tsx src/components/SessionWorkspace.reachability.test.tsx
git commit -m "feat(frontend): tree before metrics and explanation; one live indicator"
```

---

### Task 8: Full check, browser check, PR

- [ ] **Step 1: Everything green**

Run: `pnpm exec tsc --noEmit && pnpm lint && pnpm test`
Expected: all pass, no lint errors.

- [ ] **Step 2: Browser check on free ports** (never 3000, 5173, 8080, 13001, 18081; 13291/18291 are the user's running preview). Pick e.g. backend 18301, frontend 13301:

```bash
cd ../backend && JAVA_HOME=/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home PORT=18301 CORS_ALLOWED_ORIGINS=http://localhost:13301 ./gradlew :run -q &
cd ../frontend && VITE_PROXY_TARGET=http://localhost:18301 pnpm dev --port 13301 --strictPort &
```

At 1512 × 788, dark and light, check with screenshots:
1. A Basics demo while live: the tree is on the first screen; the state bar says "Active n"; a parent after its body shows "Waiting for children"; one live indicator (the header chip).
2. The same demo finished: heading "Everything has finished", all coroutines visible, no "Nothing running now", status "Scenario finished".
3. The Exception Propagation demo finished: "Scenario failed: 1 coroutine failed" in red, no green "Completed".
4. A Mutex or Combined demo: a held lock chip is grey, not red.
5. "How to read the tree" is one closed line below the tree; open it, reload, it stays open.

Space demo starts at least 7 s apart (10 per minute limit). Stop both servers afterwards.

- [ ] **Step 3: Codex review** — up to 3 rounds:

```bash
codex exec -s read-only -C "$PWD/.." --skip-git-repo-check -o <scratchpad>/codex-part1-r1.md \
  "Review the diff of branch feat/session-quick-wins against origin/main (git diff origin/main...HEAD) for the Part 1 row of docs/superpowers/specs/2026-11-05-session-dock-design.md: correctness, regressions in SessionWorkspace/StateBar/ScenarioControls, accessibility of the fold toggle, missed spec items, and test gaps. Rank findings P1/P2/P3 with file:line." < /dev/null
```

Fix findings, re-run until no P1/P2.

- [ ] **Step 4: Push and open the PR** (only when the user asks to push)

```bash
git push -u origin feat/session-quick-wins
gh pr create --title "Session page quick wins: tree first, honest states, Failed runs" --body "<summary + test plan + screenshots>"
```
