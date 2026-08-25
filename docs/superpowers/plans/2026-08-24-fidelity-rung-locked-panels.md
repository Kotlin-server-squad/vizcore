# Fidelity Rung & Locked Panels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the fidelity ladder visible — show which rung a session is on, and where a panel is empty *because of that rung*, say what would appear there and the one change that unlocks it. Plus the leak chip the state bar has been missing.

**Architecture:** A pure `deriveRung` reducer classifies a session as demo / attached / instrumented from data the app already has: the `scenario-` id prefix and `useEventCategories`. A presentational `LockedPanel` renders the unlock copy. The state bar gains a leak chip, and canvas selection moves into a pure `selectCoroutines` so state filters and the leak filter share one definition.

**Tech Stack:** React 19, HeroUI v2.7, Tailwind v3.4, Vitest 4 + Testing Library.

**Spec:** `docs/superpowers/specs/2026-08-23-spa-workspace-redesign-design.md` — decisions D-4, D-5, D-6.

**Branch:** continues `feat/spa-shell-ia`.

---

## Why this is not the whole of spec sub-project 3

The spec's workspace rebuild bundles four things: locked rung-aware panels, the tab migration, the leak chip, and decomposing `SessionDetails.tsx` (920 lines). That is too much for one plan and the decomposition is explicitly the highest-risk change in the spec.

This plan takes the part that carries the product insight and none of the risk: **the rung becomes visible and locked panels explain it**. The tab bar stays. The decomposition and the tab migration follow in the next plan, on top of a workspace that already knows its rung.

---

## Context for the implementer

Work in `frontend/`.

**The rung is derivable today — no backend change:**

- `src/lib/session-kind.ts` → `deriveSessionKind` returns `'demo'` for a `scenario-`-prefixed id.
- `src/hooks/use-event-categories.ts` → `hasChannels`, `hasFlowOps`, `hasSyncPrimitives`, `hasJobs`.

Channel, Flow and Sync event families are emitted **only** by the wrappers (`VizMutex`, `InstrumentedFlow`, `InstrumentedChannel`, `VizSelect`, `VizActor`). The DebugProbes attach path cannot produce them. So their presence on a non-demo session is direct evidence of instrumentation.

**Do not use `hasJobs` for this.** Job events are not confirmed wrapper-only, and a wrong rung is worse than a coarse one. Base `instrumented` on channels, flows and sync primitives.

**Leaks:** `useSessionMetrics(sessionId, isLive, enabled)` returns `MetricsResponse` with `leaks: LeakDto[]`, and `LeakDto.coroutineId` is what makes a leak chip filterable rather than decorative. A potential leak is **amber, never red**.

---

### Task 1: `deriveRung`

**Files:** Create `src/lib/fidelity-rung.ts`; test `src/lib/fidelity-rung.test.ts`

- [ ] **Step 1: Write the failing test** — a `scenario-`-prefixed session is `demo` regardless of its event categories; a non-demo session with sync/flow/channel events is `instrumented`; a non-demo session with none is `attached`; `hasJobs` alone does **not** promote a session to instrumented.
- [ ] **Step 2: Run it** — expected FAIL, module missing.
- [ ] **Step 3: Implement** `Rung = 'demo' | 'attached' | 'instrumented'`, `deriveRung(sessionId, categories): Rung`, and a `RUNG_LABEL` map for display.
- [ ] **Step 4: Run it** — expected PASS.
- [ ] **Step 5: Commit** — `feat(workspace): derive a session's fidelity rung`

---

### Task 2: `selectCoroutines` — one definition of what the canvas shows

**Files:** Modify `src/lib/state-counts.ts`; extend `src/lib/state-counts.test.ts`

- [ ] **Step 1: Write the failing test** — `'all'` returns everything; a state filter returns only that state; `'leaks'` returns only coroutines whose id is in the leak set; `'leaks'` with an empty set returns nothing.
- [ ] **Step 2: Run it** — expected FAIL.
- [ ] **Step 3: Implement.** Add `'leaks'` to `StateFilter` and `leaks` to `StateCounts`. Add `selectCoroutines(coroutines, filter, leakIds)`. A leak is a cross-cutting flag rather than a state, so it cannot go through `matchesFilter` — this function is where the two kinds of filter meet, so the bar and the canvas cannot disagree.
- [ ] **Step 4: Run it** — expected PASS.
- [ ] **Step 5: Commit** — `feat(workspace): unify state and leak selection`

---

### Task 3: The leak chip

**Files:** Modify `src/components/StateBar.tsx`; extend its test

- [ ] **Step 1: Write the failing test** — a leak chip appears only when the count is non-zero; it is amber, not danger; it selects the `'leaks'` filter; its accessible name reads "Potential leaks N".
- [ ] **Step 2: Run it** — expected FAIL.
- [ ] **Step 3: Implement.** Append the chip after the state chips, visually separated, since it is a different kind of claim: a heuristic finding, not a lifecycle fact.
- [ ] **Step 4: Run it** — expected PASS.
- [ ] **Step 5: Commit** — `feat(workspace): add the potential-leak chip`

---

### Task 4: `LockedPanel`

**Files:** Create `src/components/LockedPanel.tsx`; test alongside

- [ ] **Step 1: Write the failing test** — renders what the panel *would* show and the unlock instruction; names the specific change (e.g. swap `Mutex` for `VizMutex`); does not render as an error or a failure state.
- [ ] **Step 2: Run it** — expected FAIL.
- [ ] **Step 3: Implement.** Props: `title`, `whatYouWouldSee`, `unlockWith`. Presentational. The tone matters — this is an invitation, not an error. Never `danger`.
- [ ] **Step 4: Run it** — expected PASS.
- [ ] **Step 5: Commit** — `feat(workspace): add the rung-aware locked panel`

---

### Task 5: Wire rung + leaks into the workspace

**Files:** Modify `src/components/SessionDetails.tsx`; extend its test

- [ ] **Step 1: Write the failing test** — the workspace shows a rung badge; on an attached session the sync panel area shows the locked copy rather than nothing; selecting the leak chip narrows the canvas to leaked coroutines.
- [ ] **Step 2: Run it** — expected FAIL.
- [ ] **Step 3: Implement.** Derive the rung, show it beside the session id. Feed `leaks` into the state bar and `selectCoroutines`. Where a wrapper-only tab is absent on a non-demo session, mount `LockedPanel` in its place — the tabs stay for now, so this is additive.
- [ ] **Step 4: Run it** — expected PASS, and every existing suite still passes.
- [ ] **Step 5: Commit** — `feat(workspace): surface the fidelity rung and locked panels`

---

### Task 6: Verify

- [ ] `pnpm test`, `pnpm lint`, `pnpm build`
- [ ] **Look at it against a real backend.** Start Ktor with `CORS_ALLOWED_ORIGINS` matching the dev port — the allowlist defaults to `:3000` and a mismatch returns 403 on POST, which reads like a broken UI. Kill any prior backend **by PID**; `pkill -f "gradlew run"` does not reach the Gradle-spawned JVM.
- [ ] Confirm: a scenario session badges as demo; the leak chip appears only with leaks and filters the canvas; a locked panel reads as an invitation, not an error.

## Definition of done

- Every session displays its rung.
- A panel that is empty because of the rung says so, and says what unlocks it.
- The leak chip filters rather than merely counting.
- No tab is removed and `SessionDetails` is not yet decomposed.
