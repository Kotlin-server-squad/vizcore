# Workspace Decomposition & Tab Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retire the eight-tab, 1004-line `SessionDetails.tsx` and land the spec's workspace: header → tiles → state bar → canvas + inspector, with the tab content re-hosted where a developer would look for it.

**Architecture:** Two movements, strictly ordered. First a **pure decomposition** behind the tabs — the file is cut into components and hooks with the test suite untouched, so a green suite before and after is the proof nothing moved. Only then the **tab migration**, which is the part that changes behaviour and therefore the part that gets new tests.

**Tech Stack:** React 19, HeroUI v2.7, Tailwind v3.4, Vitest 4 + Testing Library.

**Spec:** `docs/superpowers/specs/2026-08-23-spa-workspace-redesign-design.md` — decisions D-4 through D-7, "Where the eight tabs go", "Component architecture".

**Branch:** continues `feat/spa-shell-ia`.

**Baseline to beat:** 74 files / 575 tests green at `039d541`.

---

## Why the order matters

The spec calls this decomposition "the highest-risk change in the sub-project — it is where replay, share, export and scenario-specific views are currently entangled." That entanglement is real and it is not obvious from the outside:

- three render modes (live / replay / read-only shared) that already take different layout branches,
- a record-replay pipeline whose auto-seek must stay suppressed while arming (CR-01),
- a debounced session refetch with a max-wait cap that a naive extraction will starve (CR-02),
- a read-only mode whose whole guarantee is that **no protected fetch is ever issued**.

Every one of those is currently held by a test. So tasks 1–5 change no test file at all. If a test goes red during them, the extraction is wrong — that is the entire point of doing them first.

---

## Context for the implementer

Work in `frontend/`. Run `pnpm test --run` after every task.

**The three modes, and what each may fetch:**

| Mode | `replayActive` | `readOnly` | Data source | Protected fetches |
|---|---|---|---|---|
| live | false | false | session snapshot + SSE | all |
| replay | true | false | frozen snapshot via replay cursor | metrics/threads suppressed |
| shared | false | true | React Query cache seeded by the public payload | **none** |

`isLiveView = !replayActive && !readOnly` gates node selection today. Keep that gate. The read-only view must gain no new network call in this plan.

**What already exists and is consumed unchanged:** `CoroutineTree`, `CoroutineTreeGraph`, `ThreadTimeline`, `DispatcherOverview`, `ChannelPanel`, `FlowPanel`, `SyncPanel`, `JobPanel`, `ValidationPanel`, `EventsList`, `CoroutineSourceStack`, `SessionMetrics`, `LeakList`, `LivePill`, `StateBar`, `LockedPanel`, `ReplayController`, `ExportMenu`, `ManageShares`.

**What the inspector can actually show.** `CoroutineNode` is thin — `id, jobId, parentId, scopeId, label, state`. Everything diagnostic comes from `useCoroutineTimeline(sessionId, coroutineId)`: `totalDuration/activeDuration/suspendedDuration` (nullable nanos), and per-event `threadName`, `dispatcherName`, `suspensionPoint`. That hook is a protected fetch — it must stay unmounted in read-only mode.

---

## Decisions this plan makes (gaps the spec left open)

**M-1 — Evidence panels sit full-width beneath the canvas/inspector grid, not inside the 320px inspector column.** The spec says "beneath the inspector"; `ChannelPanel` and friends are 110–170-line session-scoped tables that are illegible at 320px, and none of them is selection-scoped today. Full-width beneath the grid is the same reading order without pretending they are per-coroutine.

**M-2 — Threads becomes an evidence panel, not a deletion.** The spec routes Threads to "tiles (aggregate) + inspector runs-on". Both of those land here, but `ThreadTimeline` and `DispatcherOverview` are the only lane-level view in the app and neither tiles nor a one-line "runs on" replaces them. They re-host as an evidence panel. Nothing is deleted in this plan.

**M-3 — Validation becomes a header action plus a state-bar chip that opens it.** The spec folds validation into the state bar and moves the rules list to "session settings". There is no settings surface, and `useValidation` is an on-demand mutation — a chip fed by it reads zero until you click Run, which is worse than no chip. So: the header gets a **Checks** action opening a modal that hosts the existing `ValidationPanel`, the hook is owned by the workspace so results survive the modal closing, and the state bar shows a **Checks N** chip *only after a run has produced failures*. That chip opens the modal; it does not filter, and the bar marks it as a different kind of chip.

**M-4 — `LiveDockPanel` becomes `WorkspaceBody` and is mounted in all three modes.** It is already the spec's layout — metric strip on top, canvas left, ~320px inspector right. Keeping a second tabbed layout for replay/shared is the exact debt this sub-project exists to remove. The metric strip is gated off in replay and read-only, where it would either lie about a frozen view or issue a forbidden fetch.

**M-5 — `SessionDetails.tsx` is renamed, not wrapped.** No compatibility re-export. Two call sites (`routes/sessions/$sessionId.tsx`, `routes/shared.$token.tsx`) and one mock in `shared.$token.test.tsx` update with it.

---

# Movement 1 — decompose behind the tabs

**Rule for tasks 1–5: do not edit any `*.test.tsx` file except to rename it.** The suite is the guard. `pnpm test --run` must report 575 passing after each task.

---

### Task 1: Rename `SessionDetails` → `SessionWorkspace`

**Files:** `git mv` `src/components/SessionDetails.tsx` → `SessionWorkspace.tsx`, `SessionDetails.test.tsx` → `SessionWorkspace.test.tsx`, `SessionDetails.reachability.test.tsx` → `SessionWorkspace.reachability.test.tsx`; modify `src/routes/sessions/$sessionId.tsx`, `src/routes/shared.$token.tsx`, `src/routes/shared.$token.test.tsx`

- [ ] **Step 1: `git mv` the three files.** Rename the export and every import; update the `vi.mock('@/components/SessionDetails')` path and probe in the route test. Update `describe` titles.
- [ ] **Step 2: Run `pnpm test --run`** — expected 575 pass. A pure rename cannot change a count.
- [ ] **Step 3: Commit** — `refactor(workspace): rename SessionDetails to SessionWorkspace`

---

### Task 2: Extract `SessionHeader`

**Files:** Create `src/components/workspace/SessionHeader.tsx`; modify `SessionWorkspace.tsx`

- [ ] **Step 1: Extract** the whole leading `Card` — title, scenario chip, session id, rung badge, coroutine/event count chips, REPLAY chip, new-events badge, refresh, live-stream toggle + connection chip, replay toggle, Share button + tooltip, `ExportMenu`, graph/list toggle.
- [ ] **Step 2: Props, not context.** `SessionHeader` receives values and callbacks only — it fetches nothing and derives nothing. `useCapabilities` moves into it (it feeds only the Share button's disabled state and nothing else reads it).
- [ ] **Step 3: Run `pnpm test --run`** — expected 575 pass.
- [ ] **Step 4: Commit** — `refactor(workspace): extract SessionHeader`

---

### Task 3: Extract `ScenarioControls`

**Files:** Create `src/components/workspace/ScenarioControls.tsx`; modify `SessionWorkspace.tsx`

- [ ] **Step 1: Extract** the scenario control `Card` plus the `scenarioState` derivation (`notStarted` / `running` / `completed`) that only it consumes. `handleRunScenario`, `handleReset` and `handleClear` move with it; `useRunScenario`, `useDeleteSession` and `useNavigate` move with them.
- [ ] **Step 2:** The component still renders nothing when `!hasScenario || readOnly` — keep that gate at the call site so the mutation hooks are not mounted for a session that has no scenario.
- [ ] **Step 3: Run `pnpm test --run`** — expected 575 pass. Four existing tests cover Run/Reset/Clear and the WR-05 "Clear must not delete" guarantee.
- [ ] **Step 4: Commit** — `refactor(workspace): extract ScenarioControls`

---

### Task 4: Extract `CoroutineCanvas`

**Files:** Create `src/components/workspace/CoroutineCanvas.tsx`; modify `SessionWorkspace.tsx`

- [ ] **Step 1: Extract** the `liveList` IIFE body: the "What's running now" heading, the Show-completed toggle, both empty states (the "no app connected" one and the filter-miss one), the `Card` + `panelRef` wrapper, the graph/tree switch and the "N more coroutines" line.
- [ ] **Step 2:** Props: `coroutines` (already filtered and capped by the caller), `viewMode`, `completedCount`, `moreCount`, `showCompleted`, `onToggleCompleted`, `stateFilter`, `selectedCoroutineId`, `onSelect` (undefined = non-interactive), `panelRef`.
- [ ] **Step 3: Keep the filter-aware empty-state split.** Gating on the rendered set rather than the active set is what stopped filtered terminal coroutines hiding behind "no app connected" — a live defect found in the app, and it is asserted by two tests.
- [ ] **Step 4: Run `pnpm test --run`** — expected 575 pass.
- [ ] **Step 5: Commit** — `refactor(workspace): extract CoroutineCanvas`

---

### Task 5: Extract the replay and refetch machinery into hooks

**Files:** Create `src/hooks/use-workspace-replay.ts`, `src/hooks/use-session-refetch.ts`; modify `SessionWorkspace.tsx`

- [ ] **Step 1: `use-workspace-replay`** takes `{ sessionId, streamEnabled, liveEvents, storedEvents, panelRef }` and returns `{ replayActive, replaySnapshot, enterReplay, exitReplay, replay, recordReplay, newEventsCount }`. It owns the frozen snapshot, the explicit-snapshot defence in `enterReplay` (WR-08), the `recordEventsRef`, and the D-03 auto-seek-to-end **including its CR-01 suppression while `isArming || isRecording`**.
- [ ] **Step 2: `use-session-refetch`** takes `{ enabled, refetch, eventCount }` and owns the debounce + max-wait cap. Carry the two comments that explain why the cleanup must not reset `firstSessionRefetchAtRef` and why the window resets only on flush and teardown — that comment is the record of the CR-02 starvation bug.
- [ ] **Step 3: Run `pnpm test --run`** — expected 575 pass. `SessionWorkspace - session refetch max-wait under sustained stream (CR-02)` and the six replay-mode tests are the guard on this task specifically.
- [ ] **Step 4: Commit** — `refactor(workspace): extract replay and refetch hooks`

---

### Task 6: Checkpoint — the decomposition is invisible

- [ ] `pnpm test --run` → 575 pass, `pnpm lint`, `pnpm build`.
- [ ] `SessionWorkspace.tsx` is under ~450 lines and reads as layout + state.
- [ ] **Look at the app.** Live view, replay entry/exit, and a scenario run all behave as before. This is the last moment the app is known-identical; anything that looks wrong now is an extraction bug, not a redesign decision.
- [ ] Commit any fix as `fix(workspace): ...` before moving on.

---

# Movement 2 — migrate the tabs

Behaviour changes from here, so every task is test-first.

---

### Task 7: `WorkspaceBody` — one layout for all three modes

**Files:** `git mv` `src/components/LiveDockPanel.tsx` → `workspace/WorkspaceBody.tsx` and its test; modify `SessionWorkspace.tsx`

- [ ] **Step 1: Write the failing test** — the body renders in replay and in read-only mode, not just live; the metric strip is **absent** in both (`SessionMetrics` must not mount in read-only: the shared shell carries no Bearer); the inline `LeakList` still mounts exactly once in live mode.
- [ ] **Step 2: Run it** — expected FAIL.
- [ ] **Step 3: Implement.** Rename `sourcePanel` → `inspector`. Add `showMetrics: boolean`; the caller passes `!readOnly && !replayActive`. Drop `LivePill` when the strip is hidden. The grid, the 320px column and the leak placement (PD-02, amber only) are unchanged.
- [ ] **Step 4: Run it** — expected PASS.
- [ ] **Step 5: Commit** — `feat(workspace): one workspace body for live, replay and shared`

---

### Task 8: `Inspector/` — detail, most-diagnostic-first

**Files:** Create `src/components/workspace/Inspector/` — `Inspector.tsx`, `TimingCard.tsx`, `SuspendedAtCard.tsx`, `RunsOnCard.tsx`, `IdentityCard.tsx`, `EventsCard.tsx`; tests alongside

- [ ] **Step 1: Write the failing tests** — with nothing selected the inspector shows the existing "Select a coroutine" placeholder and mounts **no** timeline fetch; with a selection the cards render in the D-7 order timing → suspended-at → runs-on → identity → events; `RunsOnCard` names the thread and dispatcher from the newest timeline event that carries them; `TimingCard` renders a `~`-prefixed duration and says so is poll-bounded, and renders "not reported" rather than `0ms` when the duration is null; in read-only mode the inspector renders identity only and issues no protected fetch.
- [ ] **Step 2: Run them** — expected FAIL.
- [ ] **Step 3: Implement.** `Inspector` takes `{ sessionId, coroutine, readOnly }` and calls `useCoroutineTimeline` **only** when a coroutine is selected and `!readOnly`. `SuspendedAtCard` renders the existing `CoroutineSourceStack` unchanged — do not re-author the frame rendering, it is LOCKED v1. `IdentityCard` shows id / label / job / scope / parent from `CoroutineNode` and needs no fetch, which is why it is the one card read-only mode keeps.
- [ ] **Step 4: Run them** — expected PASS.
- [ ] **Step 5: Commit** — `feat(workspace): add the inspector cards`

---

### Task 9: Events becomes a selection-scoped drawer

**Files:** Create `src/components/workspace/EventsDrawer.tsx` + test; modify `CoroutineCanvas.tsx`

- [ ] **Step 1: Write the failing test** — the drawer is collapsed by default; its toggle names the count it would show; with a coroutine selected it lists only that coroutine's events and says whose they are; with nothing selected it lists the session's events; toggling it does not change the canvas selection.
- [ ] **Step 2: Run it** — expected FAIL.
- [ ] **Step 3: Implement.** A disclosure beneath the canvas, not a HeroUI overlay — the developer needs the tree and the events at once, which is the reason the tab was wrong. Filter on `coroutineId`; events without one are session-scoped and belong to the unselected view. `EventsList` is consumed unchanged.
- [ ] **Step 4: Run it** — expected PASS.
- [ ] **Step 5: Commit** — `feat(workspace): re-host events as a selection-scoped drawer`

---

### Task 10: `EvidencePanels/` — Channels, Flow, Sync, Jobs, Threads

**Files:** Create `src/components/workspace/EvidencePanels.tsx` + test; modify `SessionWorkspace.tsx`

- [ ] **Step 1: Write the failing test** — a session with channel events renders `ChannelPanel` under an "Evidence" heading and no channel `LockedPanel`; a non-demo session without them renders the `LockedPanel` naming `InstrumentedChannel` and does not mount `ChannelPanel`; a **demo** session renders neither a locked panel nor a panel for a category it lacks (a demo has full fidelity — nothing is locked there); the Threads panel renders at every rung; `LiveDataNotice` still shows on a live-data panel while replaying.
- [ ] **Step 2: Run it** — expected FAIL.
- [ ] **Step 3: Implement.** One component owning all five, replacing both the four capability-gated tabs and the ad-hoc locked-panel grid now in `SessionWorkspace`. Present panels stack full width (M-1); locked panels group into the existing 3-up grid beneath them, because an invitation should not outrank evidence. The unlock copy moves across verbatim — it was written once, deliberately.
- [ ] **Step 4: Run it** — expected PASS.
- [ ] **Step 5: Commit** — `feat(workspace): re-host the evidence panels`

---

### Task 11: Validation becomes Checks

**Files:** Create `src/components/workspace/ChecksModal.tsx` + test; modify `StateBar.tsx` + its test, `SessionHeader.tsx`, `SessionWorkspace.tsx`

- [ ] **Step 1: Write the failing tests** — the header has a Checks action that opens a modal hosting `ValidationPanel`; results survive closing and reopening the modal; the state bar shows **no** Checks chip before a run and none after a clean run; after a failing run it shows a `Checks N` chip that opens the modal instead of filtering the canvas, and the chip does not clear the active state filter.
- [ ] **Step 2: Run them** — expected FAIL.
- [ ] **Step 3: Implement per M-3.** `useValidation` is owned by `SessionWorkspace`. `StateBar` gains one optional `action?: { label, count, onPress }` rendered after the leak chip, visually marked as an action rather than a filter — it must not participate in `aria-pressed` state, because it selects nothing.
- [ ] **Step 4: Run them** — expected PASS.
- [ ] **Step 5: Commit** — `feat(workspace): fold validation into checks`

---

### Task 12: Retire the tabs

**Files:** Modify `SessionWorkspace.tsx`; update `SessionWorkspace.test.tsx`, `SessionWorkspace.reachability.test.tsx`

- [ ] **Step 1: Write the failing test** — the workspace renders **no** `Tabs`; the canvas, the inspector, the events drawer and the evidence section are all reachable without a tab click in all three modes; the read-only view still shows no source affordances and issues no protected fetch (port the existing guarantee off its "renders the existing tabs" phrasing); the thread lanes are reachable in replay.
- [ ] **Step 2: Run it** — expected FAIL.
- [ ] **Step 3: Implement.** Delete the `Tabs`/`Tab` block and the `isLiveView ? dock : liveList` branch. Final composition: `SessionHeader` → sticky `ReplayController` → `ScenarioControls` → `StructuredConcurrencyInfo` → scenario pipeline views → `StateBar` → `WorkspaceBody(canvas + drawer | inspector)` → `EvidencePanels` → modals. `SessionWorkspace` keeps only layout, selection and filter state.
- [ ] **Step 4: Fix the tests the migration invalidates.** Any test that asserted tab navigation asserts direct reachability instead. Do not delete a test to make it pass — if an assertion no longer has a home, say which task took its subject.
- [ ] **Step 5: Run `pnpm test --run`** — expected PASS across the suite.
- [ ] **Step 6: Commit** — `feat(workspace): retire the eight tabs`

---

### Task 13: Verify

- [ ] `pnpm test --run`, `pnpm lint`, `pnpm build`.
- [ ] **Mutation-test the three highest-value new tests** — the read-only no-protected-fetch guarantee, the demo-locks-nothing rule, and the inspector card order. Break the logic, confirm red, restore. A test written after its implementation is not yet known to be a test.
- [ ] **Run the app against a real backend.** `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`, then `CORS_ALLOWED_ORIGINS=http://localhost:3103 PORT=8085 ./gradlew run`; frontend on 3103. Kill any prior backend **by PID** from `lsof -nP -iTCP:8085 -sTCP:LISTEN -t` — `pkill -f "gradlew run"` does not reach the Gradle-spawned JVM.
- [ ] Confirm in the browser: a demo session opens on the canvas with no tab bar; selecting a node fills the inspector top-down; the events drawer narrows to that node; the evidence section shows real panels for what the session has; replay still enters, scrubs and exits; a shared link still renders read-only with no inspector fetch.
- [ ] Update `docs/superpowers/REDESIGN-HANDOFF.md`: move plan 4 into Done, name what plan 5 is, and record any debt this plan created.

## Definition of done

- `SessionDetails.tsx` no longer exists; `SessionWorkspace.tsx` is layout, selection and filter state.
- No `Tabs` anywhere in the session workspace, in any of the three modes.
- Every tab's content is reachable and hosted where the spec put it, and nothing was deleted to get there.
- The read-only shared view issues no protected fetch — same guarantee, new layout.
- Test count is at or above 575 and green.
