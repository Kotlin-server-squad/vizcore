# SPA Redesign — Handoff

**Read this first on a cold start.** Updated after every completed plan.

**Last updated:** 2026-08-25, after surveying open PRs. **Start at "Next action" below.**

---

## Next action — fix PR #102's CI, then integrate

**Do this first.** Surveyed 2026-08-25: 27 open PRs, 2 authored by the user, 25 dependabot.

### 1. PR #102 — the token layer — is RED and it blocks everything

`feat/spa-design-tokens` → `main`, +1144/−58 in 15 files. Unreviewed. The
`Type check` step of `ci-frontend.yml` (`npx tsc --noEmit`) fails with:

```
Cannot find module 'node:url' or its corresponding type declarations.
```

**Cause:** three token-layer tests — `src/styles/tokens.test.ts`,
`tailwind-theme.test.ts`, `theme-applied.test.ts` — import `fileURLToPath` from
`node:url`, and **`@types/node` is not a declared devDependency** of
`frontend/package.json`. The failing run is on the branch's current tip
(`05eaa8a`), not stale.

**⚠️ Caveat — do not skip this.** `npx tsc --noEmit` **passes locally** even
after a clean `pnpm install --frozen-lockfile`, so the failure could not be
reproduced on this machine. `--traceResolution` shows `node:url` does *not*
resolve locally either ("Module name 'node:url' was not resolved"), yet tsc
still exits 0 — something in the local toolchain suppresses what the CI runner
reports. The fix is the same either way, but **verify against CI, not against a
local green run.** A local pass proves nothing here.

Suggested fix: add `@types/node` to `frontend/package.json` devDependencies,
push, and confirm the check goes green on GitHub.

### 2. PR #74 — green and waiting since 2026-06-27

`fix/validation-parent-before-child` — "fix(validation): treat
CoroutineCompleted as body completion for coarse sources". +152/−11 in 3 files.
**MERGEABLE / CLEAN, all checks pass, unreviewed.** No reason to hold it.

### 3. Dependabot — 25 PRs

13 green (ktor group, tanstack, postgres, eslint tooling, prettier), 5 with no
checks (GitHub Actions bumps), 7 failing. Two of the failing ones are majors
that need a deliberate decision, not a merge:

- **#91 `@heroui/react` 2.7.11 → 3.2.1** — a major of the entire UI library.
  HeroUI v3 moves to Tailwind v4 + React Aria. This is a migration, not a bump,
  and the whole frontend is on v2.7. A `heroui-react` skill for v3 exists.
- **#98 `framer-motion` 11 → 12**.

### Order

1. Fix #102's type check → green → review → merge to `main`.
2. Merge #74.
3. Then the integration decision below, which #102 landing on `main` is step one of.
4. Then sub-project 4.

---

## What this work is

A five-sub-project redesign of the vizcore frontend, driven by one finding: the
product has two identities (a coroutine teaching lab and a real-app debugger)
and the SPA's navigation served the wrong one. The spine is a **fidelity
ladder** — demo / attached / instrumented — and the rung is set by how much of
their own code the developer changed.

- **Spec:** `docs/superpowers/specs/2026-08-23-spa-workspace-redesign-design.md` — read this before anything else. Eight decisions (D-1..D-8), each with the rejected alternative.
- **Plans:** `docs/superpowers/plans/2026-08-2*.md`, one per executed step.

## Branches — NOT a stack

Corrected 2026-08-24. These are **divergent lines off different points**, and
the earlier "bottom to top" framing here was wrong:

| Branch | vs `main` | Contains | Pushed? |
|---|---|---|---|
| `feat/spa-design-tokens` | 8 ahead | sub-project 2: token layer. Genuinely an ancestor of `spa-shell-ia` | **PR #102**, open, unreviewed |
| `feat/spa-shell-ia` | 41 ahead, **113 behind** | plans 1–5: IA, state bar, rung + locked panels, workspace decomposition, debt cleanup | local only |
| `feat/intellij-plugin-native-redesign` | 117 ahead, 0 behind | the user's Phase-15 work, plus one redesign commit `fd479d0`. **Descended from main, not from us.** | local only |

Only `feat/spa-design-tokens` is actually beneath us, so only a token-layer
review forces a rebase here. The other two need a real integration, and the
conflict surface between our branch and the plugin branch is small:
`frontend/src/routes/index.tsx` and its test.

## Done

1. **Token layer** — `src/styles/palette.ts` is the single source of colour, feeding `tokens.css`, the HeroUI theme in `tailwind.config.ts`. Dark-first actually on (the `dark` class was configured since day one and never set). Inter + JetBrains Mono self-hosted via `@fontsource`.
2. **Shell & IA** — sessions list is the root route, navbar is brand-only, `/scenarios` and `/gallery` redirect, demo-creation and Compare re-hosted into the sessions home as modals.
3. **State bar** — `src/lib/state-counts.ts` + `StateBar.tsx`. Chips show per-state counts and filter the canvas. Includes the potential-leak chip.
4. **Fidelity rung + locked panels** — `src/lib/fidelity-rung.ts` + `LockedPanel.tsx`. Every session badges its rung; a wrapper-only capability absent on a real session renders an invitation naming the unlock.
5. **Workspace decomposition + tab migration** — `SessionDetails.tsx` (1004 lines, eight tabs) is gone. `SessionWorkspace.tsx` is 414 lines of layout, selection and filter state. **No `Tabs` anywhere in the session workspace, in any of the three modes.** Suite went 575 → 617, all green.

### What plan 4 landed

`src/components/workspace/`: `SessionHeader`, `ScenarioControls`, `CoroutineCanvas`,
`WorkspaceBody` (was `LiveDockPanel`), `EventsDrawer`, `EvidencePanels`, `ChecksModal`,
`Inspector/` (`Inspector`, `TimingCard`, `SuspendedAtCard`, `RunsOnCard`, `IdentityCard`,
`EventsCard`, `InspectorCard`). Hooks: `use-workspace-replay`, `use-session-refetch`.

Where each tab went:

| Tab | Now |
|---|---|
| Coroutines | `CoroutineCanvas`, the default body |
| Events | `EventsDrawer` — a disclosure under the canvas, scoped to the selection |
| Threads | metric tiles + inspector "runs on" + a Threads **evidence panel** (M-2) |
| Channels / Flow / Sync / Jobs | `EvidencePanels`, full width under the grid, locked stand-in when the rung is the reason (M-1) |
| Validation | header **Checks** action + `ChecksModal`; a state-bar chip appears only after a run has failed something (M-3) |

`WorkspaceBody` is now mounted in **all three modes** with `showMetrics` gated off in
replay and read-only (M-4) — there is no second layout any more.

### Plan-4 decisions worth not relitigating

- **Evidence panels are full width beneath the grid, not inside the 320px inspector.** They are session-scoped tables, not per-coroutine detail.
- **Threads was re-hosted, not deleted.** Tiles and a one-line "runs on" do not replace the lane view.
- **The checks chip only appears after a failing run.** Validation is on-demand; a chip reading zero for a session nobody validated is a claim the app cannot make.
- **`Inspector` renders identity only in read-only mode.** Every other card is timeline-backed, and the shared shell carries no Bearer.

6. **Plan-4 debt** — `stateColor()` (the palette-backed hex accessor `palette.ts` had named since the token layer but which was never built), the canvas reconciled with the state bar, `Runs on` filled from thread activity, and the checks modal down to one heading. Suite 617 → 637.

### What plan 5 landed

- **One definition of what a state looks like.** `coroutine-state-colors.ts` disagreed with `state-counts.ts` on two states, so clicking the amber **Cancelled** chip filtered the canvas to coroutines the canvas then drew *grey*. `CANCELLED` is now amber and `WAITING_FOR_CHILDREN` is the running hue (kept tellable from `ACTIVE` by its clock icon and slower pulse). The guard derives each expected hue from `state-counts` rather than restating it, so they cannot drift again.
- **`stateColor(state)`** returns resolved palette hex for canvas/SVG. Its test asserts every returned value is a member of `palette` — the guard against a second colour source appearing beside it.
- **`resolveRunsOn`** (`src/lib/runs-on.ts`) reads `/threads`, tracking `RELEASED` as well as `ASSIGNED` so the card cannot name a thread the coroutine has left.
- **`ValidationPanel` gained `showHeading`**, default `true`, so its standalone use is unchanged.

## Next: sub-project 4 — shared domain projections — **BLOCKED**

Move `ProblemDerivation.kt` into `coroutine-viz-core` and expose it on the session API,
so the plugin and SPA stop computing the same domain twice (spec D-8). Then sub-project 5
aligns the plugin to the new IA.

**This cannot start on `feat/spa-shell-ia`.** `ProblemDerivation.kt` is not reachable
from here — it lives only on `feat/intellij-plugin-native-redesign`, along with the
`HierarchyNodeDto`, `CoroutineStateStyle` and `SuspensionTracker` it depends on. On this
branch the plugin has no `model/`, `api/` or `toolwindow/` package at all. Writing a
second implementation here would create exactly the duplication the sub-project exists
to remove, so it waits on the integration decision above.

**When it does start, it is not a clean lift.** `ProblemDerivation` calls
`CoroutineStateStyle.ageLabel` from the **toolwindow** (UI) package and takes its
`longSuspended` map from a plugin-side stateful tracker. Moving it to core means first
splitting the domain — which coroutines are problems, in what order — from the
presentation: age labels and "why" strings.

## Decisions that are settled — do not relitigate

- **Option A (problems-first triage) with B's locked-question idea grafted in.** The question rail was rejected on cost; the plugin's own 004-D problems strip was widened into a *state* bar because a problems-only strip opens on zeroes for a demo session.
- **One frame for all three rungs.** The demo user is the same person earlier, not a different audience.
- **`WAITING_FOR_CHILDREN` counts as running**; **`CANCELLED` gets its own bucket** — folding it into completed reports a cancellation as success, into failed as an error. It is neither.
- **`hasJobs` does not promote a session to instrumented.** Job events are not confirmed wrapper-only; over-reporting the rung promises panels the session cannot fill.
- **A potential leak is amber, never red.**

## Carried debt

- **C-1 — `secondary` still has ~78 live references.** ✅ *Partly closed by plan 5*: the one call site the spec flagged as carrying real meaning (`WAITING_FOR_CHILDREN` in `coroutine-state-colors`) is retired, and no coroutine state maps onto the token any more. The rest — the comparison "B only" delta ring, chips across ~25 components — remain a per-call-site decision.
- **C-2 — palette-backed state colour.** ✅ CLOSED by plan 5: `stateColor()` returns resolved palette hex, in the existing module rather than a second one beside it.
- **The backend reports no per-coroutine active/suspended durations.** The inspector's Timing card correctly says "not reported" for two of its three rows, because the timeline projection is a deferred stub (D-02). Filling those is a backend change.
- Four hardcoded `#6366f1` remain in `FlowParticlePath.tsx` and `animation-variants.ts` — a five-colour flow-operator scheme the palette does not define.
- `/scenarios/builder` (410-line `ScenarioBuilder`) still resolves; its fate is an open question in the spec.

## Harness gotchas — these cost real time

- **CORS.** The backend allowlists `localhost:3000` only. Run it as `CORS_ALLOWED_ORIGINS=http://localhost:<devport> PORT=8085 ./gradlew run`, or POSTs return **403** while curl gets 201 — which reads like a broken UI.
- **Killing the backend.** `pkill -f "gradlew run"` does **not** reach the Gradle-spawned JVM. Kill by PID from `lsof -nP -iTCP:8085 -sTCP:LISTEN -t`, or a stale backend keeps serving and you debug a phantom.
- **JDK 21** for Gradle: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
- **Ports 3000 and 8080** are usually taken on this machine; use 3103+/8085.
- **`frontend/node_modules` may be absent** — run `pnpm install` first.
- The Chrome tool **blocks reading query strings**; screenshot instead of reading `location.search`.
- **Click by element `ref`, not by coordinate.** Raw-coordinate clicks silently missed several times in plan 4's UAT; `find` → click-by-ref always landed.
- **This backend build has no `/api/capabilities`** (404) and is memory-mode, so a real shared link cannot be created. The read-only path is covered by tests only.

## Working agreements that have paid off

- **Always run the app.** Five real defects have now been found live that the suite could not catch: filtered states hidden behind the empty state, a purple `secondary` leak across ~15 components, `LivePill` labelling a paused stream as "DEMO" against an ATTACHED badge, the coroutine graph overrunning a `1fr` grid track and covering the inspector entirely, and `EventsCard` rendering an absolute epoch timestamp as an elapsed duration.
- **Mutation-test any test written after its implementation.** Break the logic, confirm the test fails, restore. Done nine times now; every time it proved the tests real.
- **A drift guard should derive the other side, not restate it.** Plan 5's colour test reads the expected hue out of `state-counts` instead of hardcoding a list, so the two modules cannot disagree again without the test noticing. A guard that restates both sides only catches the half you remember to update.
- **Check a plan's task split survives contact.** Plan 5 split "add hex" from "reassign states"; they turned out to be one change, because the palette has no `secondary` entry to give `WAITING_FOR_CHILDREN` a hex from. Say so and merge the commit rather than faking the split.
- **A behaviour-preserving refactor gets NO test edits.** Plan 4's first five tasks cut a 1004-line file into components and hooks with the suite frozen at 575 passing. Any red during those tasks is an extraction bug, and that is the whole signal.
- **jsdom lacks `IntersectionObserver` and `matchMedia`.** Both are stubbed in `src/test/setup.ts`. They only bit once `EventsList` mounted outside a lazily-rendered tab panel — a tab bar hides this class of gap.
- **Check for a pre-existing module before writing a new one.** A duplicate `state-color.ts` was created alongside `coroutine-state-colors.ts` and had to be reverted.
