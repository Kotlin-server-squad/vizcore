# Workspace State Bar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the session workspace a state bar that says what the session is doing at a glance and doubles as the filter for the coroutine canvas — the first piece of D-4, landed without disturbing the eight tabs.

**Architecture:** A pure `deriveStateCounts` reducer turns the already-derived `panelCoroutines` into per-state counts. A presentational `StateBar` renders them as chips and owns no data fetching. `SessionDetails` holds the active filter and applies it where `renderedCoroutines` is computed, generalising the existing binary `showCompleted` toggle into a filter with named states.

**Tech Stack:** React 19, HeroUI v2.7, Tailwind v3.4, Vitest 4 + Testing Library.

**Spec:** `docs/superpowers/specs/2026-08-23-spa-workspace-redesign-design.md` — decision D-4.

**Branch:** continues `feat/spa-shell-ia`.

---

## Context for the implementer

Work in `frontend/`.

`SessionDetails.tsx` already derives everything needed:

- `panelCoroutines` (line ~261) — the snapshot, either the server list or the replay projection
- `TERMINAL_STATES` (line ~57) — `COMPLETED | CANCELLED | FAILED`
- `showCompleted` state (line ~114) — today a binary toggle between "active only" and "everything"
- `renderedCoroutines` (line ~279) — what the tree and graph actually render, capped at `NODE_CAP`

`CoroutineState` has seven members: `CREATED`, `ACTIVE`, `SUSPENDED`, `WAITING_FOR_CHILDREN`, `COMPLETED`, `CANCELLED`, `FAILED`.

**Two states need a deliberate call, not a default:**

- `WAITING_FOR_CHILDREN` counts as **running**. Structurally the parent is still live work; filing it elsewhere makes a healthy structured-concurrency tree look half-dead.
- `CANCELLED` gets its **own chip**, shown only when non-zero. The spec's illustrative bar lists five chips and does not mention cancellation, but folding cancelled into either completed or failed would misreport it — cancellation is neither success nor error.

**Out of scope — say so rather than half-building it:** the `⚠ leaks` chip. Leak data comes from `useSessionMetrics`, which `SessionDetails` does not hold — `LiveDockPanel` owns that mount. The chip lands in the next plan, when the dock migrates and leak data is in scope. A chip that displays a count but cannot filter is worse than no chip.

---

### Task 1: `deriveStateCounts`

**Files:**
- Create: `src/lib/state-counts.ts`
- Test: `src/lib/state-counts.test.ts`

- [ ] **Step 1: Write the failing test** covering: each state mapping to the right bucket; `WAITING_FOR_CHILDREN` counted as running; `CANCELLED` in its own bucket, not folded into completed or failed; an empty list producing all zeros; and a `total` that equals the input length so nothing is silently dropped.

- [ ] **Step 2: Run it** — `pnpm vitest run src/lib/state-counts.test.ts`. Expected: FAIL, module missing.

- [ ] **Step 3: Implement.** A `StateFilter` union (`'all' | 'running' | 'suspended' | 'completed' | 'cancelled' | 'failed'`), a `StateCounts` record, `deriveStateCounts(coroutines): StateCounts`, and `matchesFilter(state, filter): boolean` so the bar and the canvas cannot disagree about what a chip means.

- [ ] **Step 4: Run it** — expected PASS.

- [ ] **Step 5: Commit** — `feat(workspace): derive coroutine state counts`

---

### Task 2: `StateBar`

**Files:**
- Create: `src/components/StateBar.tsx`
- Test: `src/components/StateBar.test.tsx`

- [ ] **Step 1: Write the failing test:** renders a chip per non-zero state; hides zero-count chips except Running, which always shows so the bar never collapses to nothing; clicking a chip calls `onFilterChange` with that filter; clicking the active chip calls it with `'all'` (toggling off); the active chip is marked `aria-pressed`.

- [ ] **Step 2: Run it** — expected FAIL.

- [ ] **Step 3: Implement.** Presentational only — props are `counts`, `filter`, `onFilterChange`. Chips carry semantic colour from the token layer: running primary, suspended warning, completed success, cancelled warning, failed danger. Buttons, not divs, so they are keyboard-reachable.

- [ ] **Step 4: Run it** — expected PASS.

- [ ] **Step 5: Commit** — `feat(workspace): add the state bar`

---

### Task 3: Wire it into the workspace

**Files:**
- Modify: `src/components/SessionDetails.tsx`
- Test: `src/components/SessionDetails.test.tsx` (extend)

- [ ] **Step 1: Write the failing test** — the workspace renders the bar, and choosing `Suspended` narrows the rendered coroutine set to suspended ones.

- [ ] **Step 2: Run it** — expected FAIL.

- [ ] **Step 3: Implement.** Add `const [stateFilter, setStateFilter] = useState<StateFilter>('all')`. Mount `<StateBar>` directly above `<Tabs>`. Rework `renderedCoroutines` so the filter drives it, keeping `NODE_CAP` and preserving today's default: with filter `'all'`, behaviour is exactly as before — active-only unless `showCompleted`. A named filter selects that state explicitly and bypasses `showCompleted`.

- [ ] **Step 4: Run it** — expected PASS, and the existing `SessionDetails` suites still pass. Any pre-existing test that asserts the default view is the regression guard for "filter `'all'` changed nothing."

- [ ] **Step 5: Commit** — `feat(workspace): state bar filters the coroutine canvas`

---

### Task 4: Verify

- [ ] `pnpm test` — all pass
- [ ] `pnpm lint` — 0 errors
- [ ] `pnpm build` — clean
- [ ] **Look at it.** Run `pnpm dev` on a free port with a demo session running, and confirm: the bar shows counts; clicking `Suspended` narrows the tree; clicking it again restores; counts sum to the session total; chips carry the right semantic colours.

## Definition of done

- The bar renders above the tabs and filters the canvas.
- With no filter chosen, the workspace behaves exactly as it did before.
- No tab is moved or removed — that is the next plan.
- The leak chip is absent, deliberately, and noted as such.
