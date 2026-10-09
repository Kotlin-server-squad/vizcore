# SPA Redesign — Handoff

**Read this first on a cold start.** Updated after every completed plan.

**Last updated:** 2026-08-25, after merging #102 and #74 and integrating the
plugin line. **Start at "Next action" below.**

---

## Next action — sub-project 4, on `feat/spa-integration`

**The integration is done and green.** `feat/spa-integration` contains all three
lines and is **0 behind** every one of them: `origin/main`, `feat/spa-shell-ia`,
and `feat/intellij-plugin-native-redesign`. It is **local only** — not pushed, no
PR. `feat/spa-shell-ia` is untouched, so this is reversible by deleting one branch.

Verified on the integrated tree, not on either side alone:

| Check | Result |
|---|---|
| `npx tsc --noEmit` | pass |
| `pnpm lint` | 0 errors (5 pre-existing warnings) |
| `pnpm test` | **648 passed, 80 files** |
| `pnpm build` | pass |
| `./gradlew test` (all 6 modules) | **831 passed, 0 failures, 0 errors** |

The two predicted conflicts were the only two, and both resolved to the
redesign's version — see `## The integration decision` below for why neither
cost anything. `:intellij-plugin:test` ran as part of the backend build.

**So sub-project 4 is now unblocked and is the next real work:** split
`ProblemDerivation`'s presentation from its domain, move the domain into
`coroutine-viz-core`, expose it on the session API (spec D-8). The coupling is
three `CoroutineStateStyle.ageLabel` call sites and the `longSuspended`
parameter — details below. Then sub-project 5 aligns the plugin to the new IA.

### Merged 2026-08-25

- **#102** — the token layer — merged as `e7867ea`. Its CI was red because
  `@types/node` was undeclared; fixed in `c99b4f9`. See the harness gotcha below,
  which generalises well beyond this PR.
- **#74** — `HierarchyValidator` for coarse sources — merged as `47959ba`.
  Its one open question is recorded as **C-3** under carried debt.

Both were merged with merge commits, matching this repo's convention (18 of the
last 100 commits on `main` are `Merge pull request #NN`; zero squashes).

### The redesign is up as a 5-PR stack (opened 2026-08-25)

`main` ← **#103** 1-ia ← **#104** 2-state-bar ← **#105** 3-fidelity-rung ←
**#106** 4-workspace-decomposition ← **#107** 5-debt-cleanup. All MERGEABLE;
each diff is scoped to its own plan. As of this writing **none has been reviewed.**

⚠️ **Only #103 gets CI.** `ci-frontend.yml` triggers on
`pull_request: branches: [main]`, and #104–#107 target the branch below them, so
no workflow fires on four of the five. That is a property of stacking against
this workflow config, not something the split broke. The commits themselves are
verified green on `feat/spa-integration` (648 frontend, 831 backend).

### Dependabot — reviewed, and the reviews are substantive

Checked 2026-08-25. **Read the reviews before touching any of these** — they are
real analysis, not bot noise, and one of them reframed C-1.

- **15 APPROVED + green, mergeable now:** #76, #80, #82, #84, #88, #89, #90, #94,
  #95, #96, #97, #99, #101, plus GitHub-Actions bumps #75, #77, #79, #81, #85
  (which trigger no checks at all).
- **3 CHANGES_REQUESTED, correctly held:**
  - **#91 `@heroui/react` 2.7.11 → 3.2.1.** 50+ `TS2322`/`TS2305` errors.
    `CardBody`/`CardHeader` are gone in favour of a compound `Card` API (~35
    components); `variant="flat"` and Chip `color="secondary"` are removed (60+
    usages); `Tooltip`/`Drawer`/`Input` prop signatures changed; v3 requires
    **Tailwind v4** and the PR touches neither `tailwind.config.ts` nor postcss.
    Reviewer's call: `@dependabot ignore @heroui/react major version`, close it,
    and drive a tracked migration. **This is the source of C-1's reframing.**
  - **#98 `framer-motion` 11 → 12.** 2 `TS2322` errors, 58 importing files, and
    the reviewer wants it landed **in lockstep with #91** — v3 drops
    framer-motion for CSS animations and both touch the same components.
  - **#93** build group — failing.
- **4 needing a second look:** #83 (flyway, failing, no review) and #78, #86, #87
  — all APPROVED but carrying a FAILURE check, i.e. an approval that predates a
  red run.

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
| `feat/spa-integration` | **contains everything** | the merge of all three lines; green on both suites | local only — **work here now** |
| `feat/spa-design-tokens` | merged | sub-project 2: token layer | **PR #102 MERGED** (`e7867ea`) |
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

## Decisions that are settled — do not relitigate

- **Option A (problems-first triage) with B's locked-question idea grafted in.** The question rail was rejected on cost; the plugin's own 004-D problems strip was widened into a *state* bar because a problems-only strip opens on zeroes for a demo session.
- **One frame for all three rungs.** The demo user is the same person earlier, not a different audience.
- **`WAITING_FOR_CHILDREN` counts as running**; **`CANCELLED` gets its own bucket** — folding it into completed reports a cancellation as success, into failed as an error. It is neither.
- **`hasJobs` does not promote a session to instrumented.** Job events are not confirmed wrapper-only; over-reporting the rung promises panels the session cannot fill.
- **A potential leak is amber, never red.**

## Carried debt

- **C-1 — `secondary` is a HeroUI-v3 MIGRATION PREREQUISITE, not a cleanup preference.**
  *Reframed 2026-08-25 by the review on dependabot #91.* Plan 5 closed the one
  call site that carried real meaning (`WAITING_FOR_CHILDREN` in
  `coroutine-state-colors`), and no coroutine state maps onto the token any more.
  The remaining ~78 references were filed here as a per-call-site judgement call.
  **That framing was wrong.** HeroUI v3 *removes* `color="secondary"` from Chip
  and Button outright (the palette becomes
  `default`/`success`/`danger`/`accent`/`warning`), and removes `variant="flat"`
  too. So every remaining reference is work the v3 migration must do regardless
  of taste. Retiring them early is migration progress, not tidying.

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

- **C-4 — token-layer gaps the #102 review flagged, merged unaddressed.** All
  verified still live on the current tree:
  - `tailwind.config.ts` `borderRadius` hardcodes `12px`/`8px` instead of
    `var(--radius)`/`var(--radius-sm)`, so changing the CSS token does not reach
    `.rounded-viz`. A real hole in the single-source-of-truth claim — and the
    v3/Tailwind-v4 migration is CSS-first, so this compounds with C-1.
  - `--created` is never surfaced to Tailwind/HeroUI; it is CSS-only.
  - **Nine `tokens.css` properties have no palette entry and so are unguarded:**
    the four `*-soft` fills, `--font-sans`, `--font-mono`, `--radius`,
    `--radius-sm`, `--shadow`. A typo in any of them slips past `tokens.test.ts`,
    which only iterates the palette. The reviewer's fix is better than the
    obvious one: assert every `--*` in `tokens.css` is either in `palette` or on
    an explicit extras allowlist.
  - *Already answered, do not re-open:* `.font-mono-viz` was removed deliberately
    in `05eaa8a` as unused — `font-mono` already maps to JetBrains Mono at 118
    sites.

- **C-5 — the duplicate `HierarchyValidatorTest.kt`.** Byte-identical at
  `backend/coroutine-viz-core/src/test/...` and `backend/src/test/...`, and both
  #74 reviews flagged it independently. Phase 08.4's `verifyNoDuplicateSourceFqns`
  Gradle guard covers **main** sources only, so test-source duplication slips
  through it. Two 175-line files kept in sync by hand is the drift hazard that
  guard exists to prevent.

- **The backend reports no per-coroutine active/suspended durations.** The inspector's Timing card correctly says "not reported" for two of its three rows, because the timeline projection is a deferred stub (D-02). Filling those is a backend change.
- Four hardcoded `#6366f1` remain in `FlowParticlePath.tsx` and `animation-variants.ts` — a five-colour flow-operator scheme the palette does not define.
- `/scenarios/builder` (410-line `ScenarioBuilder`) still resolves; its fate is an open question in the spec.

## Harness gotchas — these cost real time

- **CORS.** The backend allowlists `localhost:3000` only. Run it as `CORS_ALLOWED_ORIGINS=http://localhost:<devport> PORT=8085 ./gradlew run`, or POSTs return **403** while curl gets 201 — which reads like a broken UI.
- **Killing the backend.** `pkill -f "gradlew run"` does **not** reach the Gradle-spawned JVM. Kill by PID from `lsof -nP -iTCP:8085 -sTCP:LISTEN -t`, or a stale backend keeps serving and you debug a phantom.
- **`reviewDecision` and the check rollup do NOT tell you whether a PR was
  reviewed.** A review posted as an *issue comment* leaves `reviews=0` and
  `reviewDecision=""`, so a PR carrying a detailed APPROVE reads as untouched.
  This cost real trust: #102 and #74 were both called "unreviewed" here and
  merged on that basis, when each already carried a thorough review with
  actionable nits (now C-4 and C-5). **Query all three endpoints** —
  `issues/N/comments`, `pulls/N/comments`, `pulls/N/reviews` — before claiming a
  PR is unreviewed. `gh pr list --json number,reviews,comments` sweeps the whole
  repo in one call.
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
