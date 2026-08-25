# SPA Redesign — Handoff

**Read this first on a cold start.** Updated after every completed plan.

**Last updated:** 2026-08-25, after fixing PR #102's CI. **Start at "Next action" below.**

---

## Next action — merge #102 and #74, then integrate

**#102's CI is fixed and green.** Commit `c99b4f9` on `feat/spa-design-tokens`
adds `@types/node@^24` to `frontend/package.json`. The GitHub run passed all
five steps — install (`--frozen-lockfile`, so the lockfile is valid under CI's
pnpm 9), lint, type check, test, build. The lockfile churn beyond the new entry
is peer-suffix rewiring only (vite, vitest and msw all take `@types/node` as an
optional peer); no version moved.

**The "passes locally" mystery is solved, and it generalises.** There is a stray
`/Users/<user>/node_modules/@types/node` in the home directory, and TypeScript
walks *parent* directories looking for `node_modules`. A CI checkout has no such
ancestor. So **any** missing `@types` dependency will pass locally on this
machine and fail in CI — this was not specific to the token layer. See the
harness gotchas below.

### Both PRs are reviewed and ready; neither is merged

1. **#102 — token layer.** Reviewed. ~250 lines of real change (the rest is plan
   and spec docs): `palette.ts` → `tokens.css` → `tailwind.config.ts`, with tests
   that read the files off disk rather than the loaded objects, plus the
   `#6366f1` guards. One nit — `palette.ts`'s header names `stateColor()` as a
   consumer, but that function only exists here on `feat/spa-shell-ia` (plan 5).
   The comment is true once this branch lands, aspirational on `main` alone.

2. **#74 — validation.** Re-verified 2026-08-25 as still current: nothing on
   `main` has touched the validator since June, and both test mirrors still exist
   on `main`, so the PR patches the right two files. Green, CLEAN, unreviewed by
   anyone else. One finding, recorded under carried debt as **C-3** — it is a
   narrowing, not a regression, so it does not block the merge.

3. Then the integration decision below, which #102 landing on `main` is step one of.
4. Then sub-project 4.

**`origin/main` is 113 commits behind local `main`** (unpushed Phase-15 work).
Merging these PRs moves `origin/main` forward and diverges it further. The user
reconciles that themselves — do not touch local `main`.

### Dependabot — 25 PRs

13 green (ktor group, tanstack, postgres, eslint tooling, prettier), 5 with no
checks (GitHub Actions bumps), 7 failing. Two of the failing ones are majors
that need a deliberate decision, not a merge:

- **#91 `@heroui/react` 2.7.11 → 3.2.1** — a major of the entire UI library.
  HeroUI v3 moves to Tailwind v4 + React Aria. This is a migration, not a bump,
  and the whole frontend is on v2.7. A `heroui-react` skill for v3 exists.
- **#98 `framer-motion` 11 → 12**.

## What this work is

A five-sub-project redesign of the vizcore frontend, driven by one finding: the
product has two identities (a coroutine teaching lab and a real-app debugger)
and the SPA's navigation served the wrong one. The spine is a **fidelity
ladder** — demo / attached / instrumented — and the rung is set by how much of
their own code the developer changed.

- **Spec:** `docs/superpowers/specs/2026-08-23-spa-workspace-redesign-design.md` — read this before anything else. Eight decisions (D-1..D-8), each with the rejected alternative.
- **Plans:** `docs/superpowers/plans/2026-08-2*.md`, one per executed step.

## Branches — NOT a stack

Corrected 2026-08-24, **measured 2026-08-25**. These are **divergent lines off
different points**, and the earlier "bottom to top" framing was wrong:

| Branch | vs local `main` | Contains | Pushed? |
|---|---|---|---|
| `feat/spa-design-tokens` | 9 ahead, 113 behind | sub-project 2: token layer. Genuinely an ancestor of `spa-shell-ia` | **PR #102**, open, CI green, reviewed |
| `feat/spa-shell-ia` | 48 ahead, **113 behind** | plans 1–5: IA, state bar, rung + locked panels, workspace decomposition, debt cleanup | local only |
| `feat/intellij-plugin-native-redesign` | **117 ahead, 0 behind** | Phase-15 work + one redesign commit `fd479d0`. **Contains all of local `main`.** | local only |

Merge bases: `spa-shell-ia` ∩ `main` = `a87b5f9`; `plugin` ∩ `main` = `737e361`
(= local `main`'s tip). So the plugin branch is a strict descendant of `main`,
and only `spa-shell-ia` sits on an older fork point.

## The integration decision — measured, not estimated (2026-08-25)

A real trial merge was run with `git merge-tree --write-tree` (no working tree
touched). **The conflict surface is two files, and both resolve trivially.**

- 85 files touched by `spa-shell-ia`, 175 by `main`+plugin, **overlap = 2**.
- Trial merge produces a coherent 885-file tree. Everything auto-merges except
  `frontend/src/routes/index.tsx` (content) and `index.test.tsx` (add/add).

**`index.tsx` is a non-conflict.** The plugin side's *net* diff from the merge
base is a rename `HomePage` → `Home`, an `export`, and a doc comment — the
`?correlation=` deep-link was added in `7bb6482` and removed again in `fd479d0`,
so it nets to nothing. `spa-shell-ia` rewrote that file 124 → 40 lines. Take the
`spa-shell-ia` version; add a named export if a plugin-side test wants one.

**`index.test.tsx` is a shell.** The plugin's 96-line file is ~80 lines of router
scaffolding supporting a single surviving assertion — that the marketing hero
renders (`heading 'Coroutine Visualizer'`, `button 'View Sessions'`). That is the
page sub-project 1 deliberately deleted, and `spa-shell-ia`'s own test asserts
the hero is *gone*. Delete the plugin's copy; **no coverage is lost**, because
its real content (the deep-link tests) was already removed with the deep-link.

**No modify/delete landmines.** `SessionDetails.tsx`, `LiveDockPanel.tsx` and
*both their test files* are removed cleanly in the merged tree — git tracked them
as renames into `components/workspace/`, so the 113 `main` commits do not
resurrect them.

**Do it as a merge, not a rebase.** The conflict is concentrated in 2 files and 2
commits; replaying 48 commits would re-litigate the same conflict repeatedly, and
a merge preserves the frozen-suite signal that plan 4's refactor depended on.

⚠️ **The integrated branch will fail CI exactly like #102 did.** The merged tree
carries the three `node:*` token tests but **not** `@types/node` — that fix lives
only on `feat/spa-design-tokens` (`c99b4f9`). Land #102 first, or carry that
commit into the integration.

## Next: sub-project 4 — shared domain projections — unblocked BY the integration

Move `ProblemDerivation.kt` into `coroutine-viz-core` and expose it on the session
API, so the plugin and SPA stop computing the same domain twice (spec D-8). Then
sub-project 5 aligns the plugin to the new IA.

It cannot start on `feat/spa-shell-ia` — the file lives only on the plugin branch,
and this branch has no `model/`, `api/` or `toolwindow/` package at all. **The
trial merge confirms it lands intact:**
`intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/model/ProblemDerivation.kt`
plus `SuspensionTracker.kt` and both their tests are present in the merged tree.

**The domain/presentation split is small and located.** `ProblemDerivation` is 109
lines; the entire presentation coupling is three `CoroutineStateStyle.ageLabel`
call sites (lines 65, 79, 80 — line 80 also builds the `why` string) plus the
`longSuspended: Map<String, Long>` parameter fed by the plugin-side tracker.
Everything else is domain. *Correction to an earlier note:* there is no
`CoroutineStateStyle.kt` — the object is declared inside
`toolwindow/CoroutineTreeStyle.kt`. `HierarchyNodeDto` and `LeakDto` are in
`api/` (`WireModels.kt`).

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
- **C-3 — #74's body-completion probe is stream-wide, not per-source.**
  `HierarchyValidator` decides whether to assert parent/child terminal ordering
  from `coroutineEvents.any { it is CoroutineBodyCompleted }` — the whole stream.
  But `InstrumentationSource` documents that "multiple sources may run
  concurrently against the same session", and `VizcoreClient` drives a
  `DebugProbesSource` against a session where the developer may *also* be using
  `VizScope` wrappers. That combination **is** the instrumented rung. In such a
  session one `CoroutineBodyCompleted` from a single wrapped scope re-arms the
  strict rule for every DebugProbes-sourced parent/child pair, reintroducing the
  exact false positive #74 removes — for the users highest on the ladder.
  Not a regression (today that case always false-positives), so #74 still
  improves on the status quo. The obvious per-parent fix has its own cost:
  `VizScope` emits `coroutineBodyCompleted()` on the normal-completion path
  (`VizScope.kt:202`), so a *cancelled* wrapped parent would lose the check.
  `SourceAttribution.kt` already exists — deciding by source id is probably the
  real answer.

- **The backend reports no per-coroutine active/suspended durations.** The inspector's Timing card correctly says "not reported" for two of its three rows, because the timeline projection is a deferred stub (D-02). Filling those is a backend change.
- Four hardcoded `#6366f1` remain in `FlowParticlePath.tsx` and `animation-variants.ts` — a five-colour flow-operator scheme the palette does not define.
- `/scenarios/builder` (410-line `ScenarioBuilder`) still resolves; its fate is an open question in the spec.

## Harness gotchas — these cost real time

- **CORS.** The backend allowlists `localhost:3000` only. Run it as `CORS_ALLOWED_ORIGINS=http://localhost:<devport> PORT=8085 ./gradlew run`, or POSTs return **403** while curl gets 201 — which reads like a broken UI.
- **Killing the backend.** `pkill -f "gradlew run"` does **not** reach the Gradle-spawned JVM. Kill by PID from `lsof -nP -iTCP:8085 -sTCP:LISTEN -t`, or a stale backend keeps serving and you debug a phantom.
- **A missing `@types/*` dep passes locally and fails only in CI.** There is a
  stray `/Users/<user>/node_modules/@types/node` in the home directory, and
  TypeScript resolves `node:*` builtins by walking parent directories for
  `node_modules`. A CI checkout has no ancestor above the repo. Cost: one full
  session's caveat in this handoff insisting the failure "could not be
  reproduced". **Trust the CI annotations** (`gh api repos/<o>/<r>/check-runs/<id>/annotations`)
  — they carried the exact file, line and message when `gh run view --log` had
  already expired to empty.
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
