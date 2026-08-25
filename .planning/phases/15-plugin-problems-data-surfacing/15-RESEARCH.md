# Phase 15: Plugin Problems + data surfacing - Research

**Researched:** 2026-07-02
**Domain:** IntelliJ Platform plugin UI (Kotlin + Swing) — debugger-grade tool window; problem promotion, row-density, inspector reorder, Live/All history toggle
**Confidence:** HIGH (codebase-grounded; all data already on the wire, no backend work; one experimental-API caveat on `SegmentedButton`)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Problems strip + split layout (004-D, SC#1)**
- **D-01:** Problems strip is its own full-width row under the tiles row, above the tree|detail splitter (literal 004-D geometry). Existing leak-tile amber highlight stays.
- **D-02:** Zero-problem state: strip stays at fixed height as a slim green line — muted "✓ No problems" + zeroed chips. Layout never jumps when the first problem arrives.
- **D-03:** Filter chips are single-select toggles (one active category at a time). Active chip filters the tree (ancestor closure, same pattern as live filter) AND the problems-panel list in sync. Re-click or "All" clears.
- **D-04:** Right pane is a contextual swap (no tabs, no vertical stack): selecting a problem → problems-detail; selecting a tree/graph node → inspector.
- **D-05:** Default right-pane content when nothing selected = the problems list (or its ✓-healthy state) — problems-first, not an empty inspector placeholder.
- **D-06:** Each problem row carries badge · name · ~age + inline one-line why (leak: "alive ~42s"; exception: "IllegalStateException: …"; long-suspended: "suspended ~35s at file:line"). No second click to see why.
- **D-07:** Problems list ordering: severity then recency — exceptions > leaks > long-suspended, newest first within each group.
- **D-08:** Cross-highlight: single click on a problem row scrolls the tree to the coroutine with a non-selecting soft highlight (right pane stays on the why); double-click or an "Inspect" link selects the node → inspector swaps in. The soft highlight MUST NOT trigger the tree's selection listener.

**Problem taxonomy (SC#1, SC#2)**
- **D-09:** Three categories: Leaks (from `MetricsDto.leaks` verbatim — never recomputed), Exceptions (`exceptionType != null` excluding kotlin/kotlinx `CancellationException`), Long-suspended (client-derived, D-10).
- **D-10:** Long-suspended detection = client-side transition tracking: the poll pipeline remembers when each coroutine was last seen entering SUSPENDED (poll-bounded, "~" framing) and flags it once continuously suspended past the threshold. No backend change.
- **D-11:** Long-suspended threshold = fixed named constant ~30s beside `LIVE_COMPLETED_WINDOW_MS` in `SessionModel`. No settings surface this phase. Accepted caveat: idle-by-design coroutines (actor loops on channel receive) will flag after 30s.
- **D-12:** Problems are full-session in both modes; problem nodes (+ancestors) are pinned into the Live view regardless of the 5s completed-window. Live filter semantics become: active + recently-completed + problems. (Deliberate amendment; perf-safe because problems are few.)

**All mode: round grouping (006-A, SC#4)**
- **D-13:** A round group = each root coroutine (`parentId == null`) + its subtree; group label = root name + ~start time. App-agnostic, data-true — no name parsing, no time-gap heuristics.
- **D-14:** Group-row counts ✓/✗/⚠ = completed-clean / problem-exceptions / amber problems (leaks + long-suspended) over the group's subtree — mirrors the strip taxonomy exactly.
- **D-15:** Collapse economy: current round expanded + marked IN PROGRESS; the 5 most recent finished rounds individually listed but collapsed; everything older folds into one "Rounds 1–N" summary node with aggregate counts. Collapsed history must stay cheap (lazy materialization — don't build Swing nodes for unexpanded content).
- **D-16:** Problem rounds never fold into the summary node — any round with ✗/⚠ stays individually listed (collapsed) regardless of age.
- **D-17:** History search (All mode only): case-insensitive substring over name + id, behaves as filter + auto-expand — matching rows show with ancestors, owning rounds auto-expand (even out of the summary node), non-matching rounds hide; clearing restores the normal collapse state.

**Live/All toggle mechanics (006-A, SC#4)**
- **D-18:** Control = segmented `[Live | All n]` in the header next to Tree/Graph (n = full-session coroutine count); LIVE ⇄ HISTORY pill swap and refresh indicator ~200ms ⇄ ~1.5s per sketch 006-A.
- **D-19:** Mode switch changes the real poll interval (`SessionPollingService`): ~200ms in Live, ~1.5s in All. Strip/tiles update at the slower cadence in All.
- **D-20:** Graph is disabled in All mode — Graph toggle grays out with tooltip "Graph is live-only"; re-enabled on switch back to Live. No 2,800-node graph layout.
- **D-21:** Always Live on launch; mode sticks within a session only; no persistence surface.

**Tiles (SC#5 — roadmap-locked)**
- **D-22:** Tile set changes to Active · Throughput · Leaks · Peak (from `MetricsDto.active` / `throughputPerSec` / `leaks.size` / `peak`), replacing Coroutines/Active/Suspended/Leak risk/Dispatchers. Tiles remain full-session in both view modes (computed pre-filter).

**Row density + inspector (SC#2, SC#3 — sketch-locked)**
- **D-23:** Tree rows: state · name · ~age · child count · leak/exception badge always-on; dispatcher/thread dimmed secondary. Exception badge = D-09's exception definition.
- **D-24:** Inspector reorders to stacked cards, most-diagnostic-first: timing → suspended-at (+jump-to-source) → runs-on → identity → events; exception card at top only when the coroutine threw; a placeholder card marks where future multi-frame stacks slot in (sketch 005-A).

### Claude's Discretion
- Pause/freeze button interplay with All mode (freeze halts deliveries in both modes — keep behavior obvious).
- Exact Swing controls for the segmented switch (SegmentedButton vs ToggleAction pair), strip/chip components, and soft-highlight rendering (flash vs outline).
- "All n" / tile value formatting (e.g. throughput "12.3/s"), placeholder-card copy, search-box placement (visible in All mode only).
- Lazy-materialization implementation details for collapsed rounds / the summary node.
- Long-suspended tracking data structure and where pinning integrates into `SessionModel.filterLive`.

### Deferred Ideas (OUT OF SCOPE)
- Multi-frame stack traces in the inspector — needs the deferred backend DebugProbes-stack change; this phase ships only the placeholder card (D-24).
- Long-suspended threshold configurability (VizcoreSettings entry) — revisit if the fixed 30s constant proves noisy.
- Additional toggle scopes ("Failed only", "Last minute" — 006-B dropdown idea).
- Remember-last-mode persistence — rejected (D-21).
- Backend `CoroutineInfoAdapter` hierarchy/naming reconstruction (`2026-06-24-debugprobes-…` todo) — backend work outside this phase's plugin-UI scope.
</user_constraints>

<phase_requirements>
## Phase Requirements

No formal REQ IDs exist (phase is sketch-driven). The five Success Criteria are the requirements the planner must map tasks to:

| ID | Description | Research Support |
|----|-------------|------------------|
| SC#1 | Persistent Problems strip + filter chips above a split tree\|problems-detail panel; selecting a problem cross-highlights the coroutine in the tree | §Architecture Pattern 1 (strip row + swap pane), Pattern 4 (soft highlight via existing flash idiom), Pattern 2 (problem derivation) |
| SC#2 | Tree rows carry state · name · ~age · child count · leak/exception badge; dispatcher/thread dimmed | §Code Examples (renderer reorder), CoroutineRow gains exception field |
| SC#3 | Inspector reordered to stacked cards most-diagnostic-first + placeholder card | §Pattern 6 (InspectorPanel.content reorder, pure) |
| SC#4 | Segmented `[Live \| All n]` toggle; Live=active+recently-completed; All=full history grouped by round, no lag at 2,800+ nodes | §Pattern 3 (segmented control), Pattern 5 (lazy round grouping), Pattern 7 (poll-cadence switch) |
| SC#5 | Tiles (Active · Throughput · Leaks · Peak) show full-session totals in both modes | §Code Examples (tileValues retarget), SessionTiles field change |
</phase_requirements>

## Summary

This is a **plugin-UI-only** phase on the native IntelliJ tool window (branch `feat/intellij-plugin-native-redesign`). Every feature derives from data the plugin already fetches every poll — `HierarchyNodeDto` (carries `exceptionType`/`exceptionMessage`/`completedAtNanos`), `MetricsDto` (carries `active`/`peak`/`throughputPerSec`/`leaks`), and `TimelineDto`. **No backend changes, no new endpoints, no new external dependencies.** The `SessionModel.from()` already receives the complete unfiltered hierarchy and filters client-side — "All mode" is simply the unfiltered model rendered differently.

The phase decomposes into six well-isolated changes, five of which land in already-unit-tested **pure helpers** (`SessionModel`, `MetricTilesPanel.tileValues`, `InspectorViewModel`, `CoroutineStateStyle`) plus new pure helpers for problem derivation, suspension tracking, and round grouping. The sixth is Swing wiring in `VizcoreToolWindowPanel` (strip row, contextual right-pane swap, segmented control, mode-aware poll cadence, graph-disable). The established codebase idioms already contain the mechanisms needed: the `CoroutineTreeRenderer` **already paints a non-selecting background highlight** (the flash-on-state-change feature) — the soft cross-highlight (D-08) is a direct extension of that idiom, not new machinery. `FlashTracker` is the template for the stateful `SuspensionTracker` (D-10). The `CardLayout`/`ContentState` pattern is the template for the right-pane contextual swap (D-04). `filterLive`'s ancestor-closure is reused verbatim for chip filtering (D-03) and problem pinning (D-12).

**Primary recommendation:** Push all decision logic into pure, unit-testable helpers (problem derivation, suspension tracking, round grouping, tile retarget, inspector ordering) so the Swing layer stays thin — mirror the existing `tileValues`/`CoroutineStateStyle`/`InspectorViewModel.from` discipline. For the `[Live | All n]` control, use the IntelliJ Kotlin UI DSL `segmentedButton` (confirmed present in build 241) embedded as a `panel{}` `DialogPanel` in the existing toolbar, OR a two-`JToggleButton` `ButtonGroup` consistent with the existing Graph `JToggleButton` — the latter is lower-friction and fully headless-testable; recommend it unless the exact segmented pixel-look is required.

## Architectural Responsibility Map

The plugin is a single-tier Swing desktop app; "tiers" here are its internal layers.

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Problem derivation (leaks/exceptions/long-suspended) | Model (`SessionModel.from`) | Poll pipeline (stateful tracker) | Pure, testable; the poll layer only owns the cross-tick suspension memory (D-10) |
| Long-suspended transition tracking | Poll pipeline (`SessionPollingService`/new `SuspensionTracker`) | Model | Stateful across polls — cannot live in stateless `SessionModel.from`; fed in like a computed set |
| Live filter + problem pinning (D-12) | Model (`SessionModel.filterLive`) | — | Extends the existing ancestor-closure keep-set |
| Round grouping (D-13..D-17) | Model (new pure `RoundGrouping` helper) | View (`CoroutineTreeModel` build path) | Grouping/count/collapse logic is pure; Swing nodes built lazily on expand |
| Tile values (D-22) | Model (`SessionTiles` + `tileValues`) | View (`MetricTilesPanel`) | Pure label→value mapping (existing gate) |
| Inspector card ordering (D-24) | View (`InspectorPanel.content`) | Model (`InspectorViewModel`, already complete) | Pure reorder of an already-mapped view model |
| Row density + exception badge (D-23) | View (`CoroutineTreeRenderer`) | Model (`CoroutineRow` gains field) | Rendering order + one new row field |
| Problems strip / chips / right-pane swap (D-01..D-05) | View (`VizcoreToolWindowPanel`) | — | Pure Swing layout using the existing `CardLayout` idiom |
| Soft cross-highlight (D-08) | View (`CoroutineTreeRenderer` + panel) | — | Extends the existing flash-background + `scrollPathToVisible` |
| Segmented Live/All control (D-18) | View (`VizcoreToolWindowPanel` header) | — | Small stateful control; recommend a pure `ViewMode` enum + testable state helper |
| Poll cadence switch (D-19) | Poll pipeline (`SessionPoller`/`SessionPollingService`) | View (trigger) | Reschedule the fixed-delay future |
| Graph-disable-in-All (D-20) | View (`VizcoreToolWindowPanel`) | — | Extends the "graph only when visible" perf discipline (commit cc88a00) |

## Standard Stack

No new libraries. Everything is already on the classpath.

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| IntelliJ Platform (IDEA Community) | 2024.1 / build **241** (sinceBuild=241, untilBuild=251.*) | Tool window, Swing widgets, `ColoredTreeCellRenderer`, `Tree`, `TreeUtil`, `OnePixelSplitter`, Kotlin UI DSL v2 | Bundled; the plugin's only UI runtime `[VERIFIED: intellij-plugin/build.gradle.kts]` |
| Kotlin | 2.3.21 (JVM) | Plugin language | `[VERIFIED: intellij-plugin/build.gradle.kts]` |
| kotlinx-serialization-json | 1.11.0 | Wire DTO (de)serialization (already used) | Pinned repo-wide `[VERIFIED: intellij-plugin/build.gradle.kts]` |

### Supporting (relevant Platform APIs — all in build 241)
| API | Package | Purpose | Notes |
|-----|---------|---------|-------|
| `SegmentedButton<T>` + `Row.segmentedButton(items){…}` | `com.intellij.ui.dsl.builder` | Native segmented `[Live \| All]` control (D-18) | **Experimental API** — see Pitfall 3. Confirmed present in 241 `[VERIFIED: raw.githubusercontent.com/JetBrains/intellij-community/241/.../SegmentedButton.kt]` |
| `JToggleButton` + `ButtonGroup` | `javax.swing` | Alternative segmented control (matches existing Graph toggle) | Already used for the Graph toggle `[VERIFIED: VizcoreToolWindowPanel.kt:196]` |
| `ToggleAction` / `DumbAwareToggleAction` + `ActionToolbar` | `com.intellij.openapi.actionSystem` | 3rd option for a segmented toolbar pair | Heavier; not currently used in this plugin `[CITED: plugins.jetbrains.com/docs/intellij/toggle-button.html]` |
| `TreeWillExpandListener` | `javax.swing.event` | Lazy child materialization on group expand (D-15) | Classic Swing lazy-tree idiom `[ASSUMED]` |
| `Tree.scrollPathToVisible(TreePath)` / `makeVisible` | `com.intellij.ui.treeStructure.Tree` / `JTree` | Scroll-to without selecting (D-08 soft highlight) | Does not mutate selection `[ASSUMED]` |
| `TreeUtil.expandAll` / `expand` | `com.intellij.util.ui.tree` | Selective expansion (must NOT be used blanket in All mode) | Already imported `[VERIFIED: VizcoreToolWindowPanel.kt:13]` |
| `ColoredTreeCellRenderer` + `SimpleTextAttributes` | `com.intellij.ui` | Row rendering (badges, dimmed dispatcher/thread) | Already the renderer base `[VERIFIED: CoroutineTreeRenderer.kt]` |

**Installation:** None. No `npm`/`gradle` dependency additions.

**Version verification:** `SegmentedButton` API surface confirmed against the `241` branch source: `SegmentedButton<T>` exposes `selectedItem: T?`, `bind(ObservableMutableProperty<T>)`, `whenItemSelected(parentDisposable, listener)`, `whenItemSelectedFromUi(...)`, `update(vararg items)`, `maxButtonsCount(Int)`, `enabled(Boolean)`; the `ItemPresentation` renderer sets `text`/`toolTipText`/`icon`/`enabled` `[VERIFIED: raw.githubusercontent.com/JetBrains/intellij-community/241/platform/platform-impl/src/com/intellij/ui/dsl/builder/SegmentedButton.kt]`.

## Package Legitimacy Audit

**No external packages are installed or added in this phase.** All work uses APIs already on the classpath (IntelliJ Platform 241 bundled, Kotlin stdlib, kotlinx-serialization-json 1.11.0 already declared). The Package Legitimacy Gate is therefore N/A — there is nothing to verify against a registry.

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

## Architecture Patterns

### System Architecture Diagram

```
                     backend  (unchanged — no work this phase)
                        │  GET /hierarchy  +  GET /metrics  +  GET /timeline
                        ▼
   ┌──────────────────────────────────────────────────────────────────┐
   │ SessionPoller  (daemon thread; fixed-delay tick)                   │
   │   • resolve correlation → sessionId                                │
   │   • fetch hierarchy + metrics each tick                            │
   │   • [NEW D-19] interval is mode-aware: Live ~200ms / All ~1500ms   │
   │   • [NEW D-10] feeds hierarchy through SuspensionTracker (stateful)│
   └───────────────────────────────┬──────────────────────────────────┘
                                    │  invokeLater → EDT
                                    ▼
   ┌──────────────────────────────────────────────────────────────────┐
   │ SessionModel.from(hierarchy, metrics, longSuspendedIds, mode)      │
   │   PURE. Computes:                                                   │
   │   • tiles  (Active·Throughput·Leaks·Peak — FULL, pre-filter D-22)  │
   │   • problems = leaks ∪ exceptions ∪ longSuspended  (D-09, full)    │
   │   • Live: filterLive(active + recently-completed + problems) D-12  │
   │   • All : unfiltered hierarchy                                     │
   └───────────────┬───────────────────────────────┬──────────────────┘
                   │                                │
        ┌──────────▼─────────┐          ┌───────────▼─────────────┐
        │ Live path          │          │ All path                │
        │ CoroutineTreeModel │          │ RoundGrouping (D-13..17)│
        │ .apply(flat diff)  │          │ → group nodes, lazy      │
        └──────────┬─────────┘          │   materialize on expand  │
                   │                    └───────────┬─────────────┘
                   ▼                                ▼
   ┌──────────────────────────────────────────────────────────────────┐
   │ VizcoreToolWindowPanel (EDT)                                       │
   │  [tiles row]                                                       │
   │  [Problems strip: chips + ✓/counts]  ← single-select filter (D-03)│
   │  OnePixelSplitter:                                                 │
   │    left  = TREE / GRAPH card  (Graph disabled in All, D-20)        │
   │    right = CardLayout swap:  problems-detail  ⇄  inspector  (D-04) │
   │  header toolbar: [Live | All n] segmented  ·  Graph  ·  Pause      │
   └──────────────────────────────────────────────────────────────────┘
   selection: problem click → scrollPathToVisible + soft highlight (D-08, no select)
              tree/graph node click → inspector (existing selectCoroutine path)
```

### Recommended Project Structure (files touched / added)
```
intellij-plugin/src/main/kotlin/com/jh/coroutinevisualizer/
├── model/
│   ├── SessionModel.kt        # + problems, + longSuspended input, + pinning, retarget tiles
│   ├── SessionTiles.kt(inline)# fields → active/throughput/leaks/peak
│   ├── CoroutineRow.kt        # + exception field (hasException / exceptionType)
│   ├── CoroutineTreeModel.kt  # unchanged for Live; All uses RoundGrouping build path
│   ├── Problem.kt             # NEW pure: Problem(category, id, name, ageLabel, why) + ProblemCategory
│   ├── ProblemDerivation.kt   # NEW pure: derive/sort problems (D-07/D-09) — or fold into SessionModel
│   ├── SuspensionTracker.kt   # NEW stateful (mirrors FlashTracker): id→firstSuspendedNanos
│   └── RoundGrouping.kt       # NEW pure: roots→groups, ✓/✗/⚠ counts, collapse plan (D-13..17)
├── poll/
│   ├── SessionPoller.kt       # + reschedule(intervalMs) for mode switch (D-19)
│   └── SessionPollingService.kt # + setMode(Live/All) → interval; owns SuspensionTracker
└── toolwindow/
    ├── VizcoreToolWindowPanel.kt   # strip row, right-pane swap, segmented control, graph-disable
    ├── ProblemsStripPanel.kt       # NEW: chips + counts + ✓-healthy fixed-height state (D-01/D-02)
    ├── ProblemsDetailPanel.kt      # NEW: problem list w/ inline why + Inspect link (D-06)
    ├── MetricTilesPanel.kt         # tileValues retarget (D-22)
    ├── InspectorPanel.kt           # content() reorder + placeholder card (D-24)
    ├── CoroutineTreeRenderer.kt    # row reorder + exception badge + soft-highlight read (D-23/D-08)
    ├── CoroutineTreeStyle.kt       # (badge/age helpers reused; maybe exception badge text)
    └── ViewMode.kt                 # NEW pure enum + state helper (Live/All) for unit test
```

### Pattern 1: Contextual right-pane swap via CardLayout (D-04/D-05)
**What:** The splitter's `secondComponent` becomes a `JPanel(CardLayout)` holding two cards: PROBLEMS_DETAIL and INSPECTOR. Selecting a problem shows the detail card; selecting a tree/graph node shows the inspector card. Default (nothing selected) = problems list (D-05).
**When to use:** Exactly the existing `ContentState`/`cards.show` idiom already used for NOT_LAUNCHED/CONNECTING/LIVE — reuse it one level down.
**Example:**
```kotlin
// Mirrors VizcoreToolWindowPanel.content + cards.show(...) already in the file.
private val rightCards = CardLayout()
private val rightPane = JPanel(rightCards).apply {
    add(problemsDetail, RIGHT_PROBLEMS)   // ProblemsDetailPanel
    add(inspector,     RIGHT_INSPECTOR)   // existing InspectorPanel
}
// splitter.secondComponent = rightPane   (was: = inspector)
private fun showProblems() = rightCards.show(rightPane, RIGHT_PROBLEMS)
private fun showInspector() = rightCards.show(rightPane, RIGHT_INSPECTOR)
```

### Pattern 2: Problem derivation as a pure function (D-07/D-09)
**What:** Given the full hierarchy + `metrics.leaks` + the long-suspended id set, produce an ordered `List<Problem>`.
**When to use:** In `SessionModel.from` (or a `ProblemDerivation` object it calls) so it is covered by `SessionModelTest`.
**Example:**
```kotlin
enum class ProblemCategory { EXCEPTION, LEAK, LONG_SUSPENDED }   // severity order = declaration order

data class Problem(
    val category: ProblemCategory,
    val coroutineId: String,
    val name: String,
    val why: String,          // inline one-line (D-06): "IllegalStateException: …" / "alive ~42s" / "suspended ~35s at File.kt:88"
    val sortKeyNanos: Long,    // createdAtNanos (or detection time) for recency within group
)

private fun isRealException(exceptionType: String?): Boolean =
    exceptionType != null && !exceptionType.endsWith("CancellationException")   // excludes kotlin/kotlinx + JobCancellationException — CONFIRM rule (see Assumptions)

fun deriveProblems(
    hierarchy: List<HierarchyNodeDto>,
    leakIds: Set<String>,
    longSuspendedIds: Set<String>,
): List<Problem> {
    val byId = hierarchy.associateBy { it.id }
    val problems = buildList {
        hierarchy.filter { isRealException(it.exceptionType) }.forEach { add(exceptionProblem(it)) }
        leakIds.mapNotNull { byId[it] }.forEach { add(leakProblem(it)) }
        longSuspendedIds.mapNotNull { byId[it] }.forEach { add(longSuspendedProblem(it)) }
    }
    // D-07: severity (category ordinal) then recency (newest first)
    return problems.sortedWith(compareBy<Problem> { it.category.ordinal }.thenByDescending { it.sortKeyNanos })
}
```

### Pattern 3: Segmented `[Live | All n]` control (D-18) — two viable idioms
**What:** A binary mode switch. Keep the *state machine* pure (`ViewMode` enum + a helper) and pick one rendering.
**Option A (recommended — consistency + headless-testable):** two `JToggleButton`s in a `ButtonGroup`, styled flat/segmented, mirroring the existing Graph `JToggleButton`. No DSL panel embedding; trivially driven in tests.
**Option B (native look):** Kotlin UI DSL `segmentedButton`, embedded as a `DialogPanel`:
```kotlin
// com.intellij.ui.dsl.builder.panel — produces a DialogPanel you .add() into the FlowLayout toolbar.
enum class ViewMode { LIVE, ALL }
val modePanel = panel {
    row {
        segmentedButton(listOf(ViewMode.LIVE, ViewMode.ALL)) { mode ->
            text = when (mode) { ViewMode.LIVE -> "Live"; ViewMode.ALL -> "All $allCount" }
        }.whenItemSelected(parentDisposable) { mode -> onModeChange(mode) }   // parentDisposable REQUIRED
         .selectedItem = ViewMode.LIVE                                        // D-21 always Live on launch
    }
}
toolbar.add(modePanel)   // FlowLayout.RIGHT, next to Graph (D-18)
```
**Caveat:** `segmentedButton` is `@ApiStatus.Experimental` and the "All n" label must be rebuilt when the count changes — the `ItemPresentation` renderer runs on build, so refreshing the count means calling `.update(...)` or rebuilding the presentation (simpler with Option A, where you just `setText` on the JToggleButton each poll).

### Pattern 4: Soft (non-selecting) cross-highlight (D-08) — extend the existing flash idiom
**What:** The renderer ALREADY paints a non-selection background for flashing ids (`if (!selected && flashing.containsKey(id)) background = FLASH_BACKGROUND`). Add a `softHighlightId` the renderer reads the same way. Scroll to it with `scrollPathToVisible` (does NOT change selection, so the `TreeSelectionListener` never fires → right pane stays on the "why", satisfying D-08).
**Example:**
```kotlin
// In CoroutineTreeRenderer: add a settable id; paint an outline or reuse FLASH_BACKGROUND.
var softHighlightId: String? = null
// inside customizeCellRenderer, after the flash check:
if (!selected && coroutine.id == softHighlightId) background = SOFT_HIGHLIGHT_BG   // or a customLine border

// In the panel, on single-click of a problem row (NOT double-click):
val path = pathForCoroutine(problem.coroutineId) ?: return
renderer.softHighlightId = problem.coroutineId
tree.scrollPathToVisible(path)   // scroll WITHOUT tree.selectionModel.selectionPath = ...
tree.repaint()
// double-click / "Inspect" link → tree.selectionPath = path  (fires listener → inspector swap)
```
**Note:** For a temporary pulse reuse the `Timer(FLASH_DURATION_MS)` self-clearing idiom already in the renderer; for a persistent outline until the next problem is chosen, keep `softHighlightId` set. Discretion (flash vs outline) is D-08-open.

### Pattern 5: Lazy round grouping for All mode (D-13..D-17)
**What:** In All mode the tree is a two-level structure: synthetic **round-group nodes** (root coroutine + subtree) whose coroutine children are materialized only when the group expands. Keeps 2,800+ nodes cheap.
**When to use:** Only in All mode. Live mode keeps the existing flat `CoroutineTreeModel.apply` diff untouched.
**Example:**
```kotlin
// TreeWillExpandListener builds real children on demand; collapsed groups carry a single dummy child
// so the expand handle shows without paying for the subtree.
class RoundGroup(val rootId: String, val label: String, val counts: Counts, val inProgress: Boolean)
data class Counts(val ok: Int, val exceptions: Int, val amber: Int)   // ✓/✗/⚠ (D-14)

tree.addTreeWillExpandListener(object : TreeWillExpandListener {
    override fun treeWillExpand(e: TreeExpansionEvent) {
        val node = e.path.lastPathComponent as DefaultMutableTreeNode
        val group = node.userObject as? RoundGroup ?: return
        if (isDummyOnly(node)) {                 // not yet materialized
            node.removeAllChildren()
            buildSubtreeInto(node, group.rootId) // build CoroutineRow children now
            treeModel.nodeStructureChanged(node)
        }
    }
    override fun treeWillCollapse(e: TreeExpansionEvent) { /* keep or prune to reclaim */ }
})
```
**Collapse economy (pure — `RoundGrouping` helper, D-15/D-16):** compute the display plan from the roots sorted by `createdAtNanos`:
- roots with any non-completed coroutine in subtree → `inProgress = true`, expanded;
- of the finished rounds, the 5 most recent → listed, collapsed;
- older finished-*clean* rounds → folded into one `"Rounds 1–N"` summary node with aggregate counts;
- **any round with ✗/⚠ never folds** (D-16) — stays individually listed and collapsed.
This plan is a pure function of `(roots, problems)` → testable without Swing.
**History search (D-17):** case-insensitive substring over `name`+`id` → the set of matching ids; expand owning rounds (even out of the summary), show matches + ancestors, hide non-matching rounds; empty query restores the plan above.

### Pattern 6: Inspector card reorder (D-24) — pure ordering change
**What:** `InspectorPanel.content()` already builds all the cards; only their `column.add(...)` order changes to: exception (top, when present) → **timing → suspended-at → runs-on → identity → events** → placeholder "Future: multi-frame stack". `InspectorViewModel` is already complete — no model change.
**When to use:** Straight edit of `content()`; add one static placeholder card. Covered by rendering, logic already in `InspectorViewModelTest`.

### Pattern 7: Mode-aware poll cadence (D-19)
**What:** `SessionPoller` schedules a `scheduleWithFixedDelay` future at a fixed `intervalMs`. To switch cadence, cancel the future and reschedule at the new interval **without** losing the resolved `sessionId`. Live uses `settings.pollIntervalMs` (default 200, clamped 50–5000); All uses ~1500 (within the clamp).
**Example:**
```kotlin
// SessionPoller: keep sessionId/onModel/onError; swap only the future.
@Volatile private var intervalMs: Long = ...
fun setInterval(ms: Long) {
    intervalMs = ms
    future?.cancel(false)
    future = scheduler.scheduleWithFixedDelay({ tick(correlation) }, 0, ms, TimeUnit.MILLISECONDS)
}
// SessionPollingService.setMode(mode) → poller?.setInterval(if (mode == ALL) 1500 else settings.pollIntervalMs)
```
**Freeze interplay (discretion):** `freeze()`/`unfreeze()` already gate `tick`; keep them mode-independent so Pause halts deliveries in both modes — the button label ("Pause view"/"Resume view") already communicates this.

### Anti-Patterns to Avoid
- **`TreeUtil.expandAll` in All mode.** The existing code calls `expandAll` on first data (`VizcoreToolWindowPanel.kt:159`). In All mode this would materialize all 2,800 nodes and reintroduce lag — expand only per the collapse plan (D-15).
- **Recomputing leaks client-side.** D-09 is explicit: leaks come from `MetricsDto.leaks` verbatim. Never re-derive from age.
- **Selecting the tree node on problem single-click.** Setting `tree.selectionPath` fires the `TreeSelectionListener` → the inspector swaps in, defeating D-08. Use `scrollPathToVisible` + the renderer highlight only.
- **Making long-suspended stateless.** `SessionModel.from` is stateless per call; a naive "is SUSPENDED now" check cannot know *how long*. Transition memory must persist across polls (Pattern via `SuspensionTracker`).
- **Rebuilding the whole All-mode tree every 1.5s poll.** Preserve expansion/selection by reusing id-keyed nodes (the `CoroutineTreeModel` reuse principle) even in the grouped model; don't drop and rebuild.
- **Computing the graph layout in All mode.** D-20 + commit cc88a00 — never compute `GraphLayout` for 2,800 nodes; disable the Graph toggle.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Non-selecting row highlight | A custom selection-suppressing tree | The existing renderer `background`/border painting (flash idiom) + `scrollPathToVisible` | Already proven in `CoroutineTreeRenderer`; selection stays untouched |
| Segmented control | Hand-painted toggle widget | `segmentedButton` (DSL) or `JToggleButton`+`ButtonGroup` | Platform-native focus/theme/hi-DPI handling |
| Lazy tree loading | A virtualized tree / custom TreeModel | `TreeWillExpandListener` + dummy-child placeholder on `DefaultMutableTreeNode` | Standard Swing idiom; keeps the existing `DefaultTreeModel` + node-reuse diffing |
| Approximate duration formatting | New ms/s formatter | `CoroutineStateStyle.ageLabel` / `InspectorViewModel.formatApproxNanos` | Already unit-tested; keeps "~" framing consistent |
| Ancestor-closure filtering | New graph walk | `SessionModel.filterLive`'s keep-set + parent-chain walk | Reuse for chip filter (D-03) and problem pinning (D-12) |
| Tile value mapping | Inline label/value strings in the panel | `MetricTilesPanel.tileValues` pure helper | Existing unit gate (`MetricTilesTest`) |
| Cross-tick state change detection | Ad-hoc diff | `FlashTracker` pattern (put-and-compare map) | Template for `SuspensionTracker` |

**Key insight:** Nearly every mechanism this phase needs already exists in the codebase in a slightly different guise. The work is *re-composition* (new pure helpers + Swing wiring), not new infrastructure — which is why it is plugin-UI-only and dependency-free.

## Common Pitfalls

### Pitfall 1: Long-suspended flags idle-by-design coroutines
**What goes wrong:** Actor loops / channel `receive` coroutines sit SUSPENDED indefinitely and trip the 30s threshold, cluttering the strip.
**Why it happens:** D-10 detection is purely time-based; it cannot distinguish "stuck" from "idle-by-design."
**How to avoid:** This is an **accepted caveat (D-11)** — ship the fixed 30s constant; configurability is deferred. Keep the threshold a single named constant beside `LIVE_COMPLETED_WINDOW_MS` so tuning is a one-line change.
**Warning signs:** Steady long-suspended count that never clears on a healthy app.

### Pitfall 2: CancellationException misclassified as an exception
**What goes wrong:** Structured-concurrency cancellation is normal flow; counting it as a problem produces false ✗ badges everywhere.
**Why it happens:** `exceptionType != null` is true for cancellations too.
**How to avoid:** Exclude any `exceptionType` ending in `CancellationException` (covers `kotlin.coroutines.cancellation.CancellationException`, `kotlinx.coroutines.CancellationException`, and `kotlinx.coroutines.JobCancellationException`). **Confirm the exact match rule** with the user (see Assumptions A1) — the sketch says "kotlin/kotlinx CancellationException."
**Warning signs:** Exception count == active-cancelled count.

### Pitfall 3: `SegmentedButton` is an experimental Platform API
**What goes wrong:** Compiling against `com.intellij.ui.dsl.builder.SegmentedButton` on 241 is fine, but the verifier can flag `ExperimentalApiUsages`, and the API may shift in future platforms (untilBuild=251.*).
**Why it happens:** It is annotated `@ApiStatus.Experimental`.
**How to avoid:** The build already mutes `ExperimentalApiUsages` in `pluginVerification.freeArgs` (`-mute InternalApiUsages,DeprecatedApiUsages,ExperimentalApiUsages`), so the gate will not fail. But the low-friction, non-experimental **`JToggleButton`+`ButtonGroup`** (Option A) sidesteps the concern entirely and matches the existing Graph toggle — recommended default.
**Warning signs:** Verifier `ExperimentalApiUsages` warnings (muted), or an "All n" count that won't refresh via the `ItemPresentation` renderer.

### Pitfall 4: Transient-empty-poll guard vs. All mode
**What goes wrong:** The panel keeps the last good tree when a poll returns empty (`VizcoreToolWindowPanel.kt:148`). In All mode a slower 1.5s cadence + a grouped rebuild could interact with this guard to freeze the view.
**Why it happens:** The guard assumes a flat live model.
**How to avoid:** Keep the guard, but ensure the All-mode build path respects it (don't blow away group nodes on a transient empty). Preserve expansion state across the slower refresh.
**Warning signs:** All-mode tree stops updating after a backend blip.

### Pitfall 5: Rescheduling the poller drops sessionId or double-schedules
**What goes wrong:** Naively calling `SessionPollingService.start()` again to change cadence rebuilds a fresh `SessionPoller`, losing the resolved `sessionId` (forces a re-resolve) and possibly leaking the old scheduler.
**Why it happens:** `start()` does `poller?.stop()` then `SessionPoller(...)` new.
**How to avoid:** Add `SessionPoller.setInterval(ms)` that cancels only the `future` and reschedules while retaining `sessionId`/callbacks (Pattern 7). Cancel with `cancel(false)` (not `true`) to avoid interrupting an in-flight tick.
**Warning signs:** Brief "Connecting…" flash on every mode switch; two `vizcore-poller` threads.

### Pitfall 6: `LEAK_RISK_INDEX` hard-coded tile highlight breaks after retarget
**What goes wrong:** `MetricTilesPanel` highlights tile index 3 (Leak risk) amber. After D-22 the tile set is Active·Throughput·Leaks·Peak — Leaks is index 2.
**Why it happens:** The highlight index is a hard-coded constant.
**How to avoid:** Update `LEAK_RISK_INDEX` (or key the highlight off the label "Leaks") when retargeting `tileValues`, and update `MetricTilesTest`.
**Warning signs:** Wrong tile turns amber on a leak.

## Runtime State Inventory

Not applicable — this is a feature-addition phase within the plugin's existing code, not a rename/refactor/migration. No stored data keys, service configs, OS registrations, secrets, or build artifacts carry a string that changes. The one stateful concern is **new in-memory-only state**: the `SuspensionTracker` map and the current `ViewMode` — both session-scoped, non-persisted (D-21), rebuilt on relaunch. No migration.

## Code Examples

### Tile retarget (D-22) — pure helper + SessionTiles
```kotlin
// SessionModel.kt — SessionTiles fields change:
data class SessionTiles(
    val active: Int,
    val throughputPerSec: Double,
    val leaks: Int,
    val peak: Int,
)
// SessionModel.from(...) fills them from metrics (FULL, pre-filter — existing rule):
val tiles = SessionTiles(
    active = metrics?.active ?: hierarchy.count { it.state.equals("RUNNING", true) },
    throughputPerSec = metrics?.throughputPerSec ?: 0.0,
    leaks = leakIds.size,
    peak = metrics?.peak ?: 0,
)

// MetricTilesPanel.tileValues (pure gate — update MetricTilesTest):
fun tileValues(t: SessionTiles): List<Pair<String, String>> = listOf(
    "Active" to t.active.toString(),
    "Throughput" to String.format(Locale.ROOT, "%.1f/s", t.throughputPerSec),   // "12.3/s" (discretion)
    "Leaks" to t.leaks.toString(),
    "Peak" to t.peak.toString(),
)
private const val LEAK_HIGHLIGHT_INDEX = 2   // was 3
```

### Row density reorder + exception badge (D-23)
```kotlin
// CoroutineRow gains:  val hasException: Boolean   (or exceptionType: String?)
// CoroutineTreeModel.toRow: hasException = isRealException(dto.exceptionType)

// CoroutineTreeRenderer.customizeCellRenderer — order per D-23:
append("● ", SimpleTextAttributes(STYLE_PLAIN, accent))
append(coroutine.name, REGULAR_ATTRIBUTES)
append("  ${badgeText(coroutine.state)}", SimpleTextAttributes(STYLE_SMALLER, accent))
append("  $ageLabel", GRAYED_SMALL_ATTRIBUTES)                 // ~age
if (coroutine.childCount > 0) append("  (${coroutine.childCount})", GRAYED_SMALL_ATTRIBUTES)  // child count
if (coroutine.isLeak) append("  ⚠", SimpleTextAttributes(STYLE_SMALLER, leakColor()))          // leak badge (amber)
if (coroutine.hasException) append("  ✗", SimpleTextAttributes(STYLE_SMALLER, dangerColor()))   // exception badge
// dispatcher/thread LAST, dimmed (already GRAYED_SMALL_ATTRIBUTES):
coroutine.dispatcherName?.let { append("  @$it", GRAYED_SMALL_ATTRIBUTES) }
coroutine.threadName?.takeIf { it.isNotBlank() }?.let { append("  @$it", GRAYED_SMALL_ATTRIBUTES) }
```

### SuspensionTracker (D-10/D-11) — stateful, FlashTracker-style
```kotlin
// model/SuspensionTracker.kt — not thread-safe; confined to the poll thread (like FlashTracker to EDT).
class SuspensionTracker(private val thresholdNanos: Long = LONG_SUSPENDED_THRESHOLD_NANOS) {
    private val firstSuspendedAt = mutableMapOf<String, Long>()

    /** Returns ids continuously SUSPENDED for ≥ threshold as of [nowNanos]. Updates memory. */
    fun update(hierarchy: List<HierarchyNodeDto>, nowNanos: Long = System.nanoTime()): Set<String> {
        val presentSuspended = hierarchy.filter { it.state.equals("SUSPENDED", true) }.map { it.id }.toSet()
        firstSuspendedAt.keys.retainAll(presentSuspended)          // cleared when it leaves SUSPENDED
        presentSuspended.forEach { firstSuspendedAt.putIfAbsent(it, nowNanos) }
        return firstSuspendedAt.filterValues { nowNanos - it >= thresholdNanos }.keys.toSet()
    }
    companion object { const val LONG_SUSPENDED_THRESHOLD_NANOS = 30_000L * 1_000_000L }  // ~30s (D-11)
}
```

### Problem pinning in filterLive (D-12)
```kotlin
// SessionModel.filterLive — seed the keep-set with problem ids before ancestor closure:
val keep = HashSet<String>()
for (node in full) {
    val completed = node.completedAtNanos
    if (completed == null || (nowNanos - completed) <= windowNanos) keep += node.id
}
keep += problemIds        // <-- NEW (D-12): leaks ∪ exceptions ∪ longSuspended, always retained
// ...existing ancestor closure unchanged...
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Live view showed all completed coroutines (render lag) | Live = active + recently-completed (~5s window) | commit 5287197 / 452520d | The reason All mode is needed (past rounds vanish); D-12 amends the filter to also pin problems |
| Graph layout computed every poll | Graph layout computed only when the graph tab is visible | commit cc88a00 | D-20 extends this: never compute graph in All mode |
| JCEF/loopback embedded React frontend in the tool window | Fully native Swing tool window (tree/graph/inspector/tiles) | 2026-06-30 native-redesign supersede | This phase builds entirely on the native surface; the old JCEF path is deleted |
| Kotlin UI DSL v1 | Kotlin UI DSL v2 (`com.intellij.ui.dsl.builder`), `segmentedButton` available | Platform ≥ 2021.3 (present in 241) | `segmentedButton` is the native option for D-18 (experimental) |

**Deprecated/outdated:**
- Kotlin UI DSL v1 (`com.intellij.ui.layout.*`) — do not use; v2 is standard on 241.
- The former tile set (Coroutines/Active/Suspended/Leak risk/Dispatchers) — replaced by D-22.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | CancellationException exclusion = `exceptionType.endsWith("CancellationException")` (covers kotlin + kotlinx + JobCancellationException) | Pattern 2 / Pitfall 2 | Too-broad match hides a real user exception that happens to end in that suffix; too-narrow shows cancellations as problems. Low risk; confirm the exact FQN set with the user |
| A2 | "Newest first within group" (D-07 recency) = sort by `createdAtNanos` descending | Pattern 2 | Could instead mean detection-time; visual ordering only, easily changed |
| A3 | "Current round" (D-15 IN PROGRESS) = any root whose subtree has a non-completed coroutine; there may be >1 concurrently | Pattern 5 | If the user expects exactly one "current" round, multiple IN-PROGRESS labels would surprise; clarify during planning |
| A4 | `TreeWillExpandListener` + dummy-child placeholder is the chosen lazy-materialization mechanism (vs a custom `TreeModel`) | Pattern 5 | Custom TreeModel would break `CoroutineTreeModel`'s node-reuse diffing; low risk, standard idiom |
| A5 | All-mode 1500ms interval is within the settings clamp (50–5000) and can be hard-coded | Pattern 7 | Verified against `VizcoreSettings` clamp; safe |
| A6 | Throughput tile format = "12.3/s" (`%.1f/s`) | Code Examples | Cosmetic; discretion per CONTEXT |
| A7 | `scrollPathToVisible` does not fire the `TreeSelectionListener` (satisfies D-08) | Pattern 4 | If wrong, the soft highlight would swap the pane; standard Swing guarantees selection is separate from scroll — low risk, verify in live UAT |

## Open Questions (RESOLVED)

1. **CancellationException match rule (A1).** — **RESOLVED:** plan 15-01 adopts `endsWith("CancellationException")` (per D-09) with a unit test over the observed strings (`ProblemDerivationTest`).
   - What we know: sketch says exclude "kotlin/kotlinx CancellationException"; backend sends `exceptionType` as a type name string.
   - What's unclear: exact FQNs the backend emits (does it send simple name or FQN? `JobCancellationException`?).
   - Recommendation: use `endsWith("CancellationException")`; add a unit test with the real strings observed in a live session; confirm during discuss/plan.

2. **"Current round" cardinality (A3).** — **RESOLVED:** plan 15-03 allows multiple concurrent IN-PROGRESS groups (data-true; test comment "multiple concurrent in-progress groups allowed (A3)").
   - What we know: D-15 says "current round expanded and marked IN PROGRESS" (singular phrasing).
   - What's unclear: whether concurrent roots can produce multiple in-progress rounds.
   - Recommendation: treat every root with active descendants as IN PROGRESS (data-true); revisit if the user wants a single "current."

3. **Soft-highlight persistence: flash vs. outline (D-08 discretion).** — **RESOLVED:** plan 15-02 implements a persistent outline (`softHighlightId` held, not a timed flash).
   - What we know: existing flash Timer self-clears after 1200ms; an outline would persist until the next problem is selected.
   - Recommendation: persistent outline (`softHighlightId` held) reads better for "which coroutine is this problem" than a brief flash; low-cost to switch.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 21 | Gradle build/test of the plugin | Assumed (per project memory: JDK 21 for Gradle gates) | 21 | none — build fails without it |
| IntelliJ Platform 2024.1 (241) | Compile + `runIde`/`test` fixtures | Downloaded by `org.jetbrains.intellij.platform` 2.16.0 | 241 | none |
| Backend on :8080 + demo app + agent | Live UAT of SC#1–5 (not unit tests) | Runtime only (see live-UAT harness in project memory) | — | Unit tests + `runIde` sandbox cover most logic without a live app |

**Missing dependencies with no fallback:** none blocking — all build/test tooling is already configured in `intellij-plugin/build.gradle.kts`.
**Missing dependencies with fallback:** live UAT needs the backend + demo running on alternate ports (8080/3000 often taken) — see the project's live-UAT harness note.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (`junit-jupiter` 6.1.0) + `kotlin-test-junit` 2.4.0; IntelliJ Platform `TestFrameworkType.Platform` for fixture-based tests |
| Config file | `intellij-plugin/build.gradle.kts` (`tasks.named<Test>("test"){ useJUnitPlatform() }`) |
| Quick run command | `cd backend && ./gradlew :intellij-plugin:test --tests "*SessionModelTest"` (plugin is included as `:intellij-plugin` in the backend composite; use JDK 21) |
| Full suite command | `cd backend && ./gradlew :intellij-plugin:test` (add `ktlintCheck detekt` for the full gate; JDK 21) |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SC#1 | Problem derivation + ordering (severity then recency) | unit | `:intellij-plugin:test --tests "*SessionModelTest"` | ✅ (extend `SessionModelTest`) |
| SC#1 | Long-suspended transition tracking (enter/clear/threshold) | unit | `:intellij-plugin:test --tests "*SuspensionTrackerTest"` | ❌ Wave 0 |
| SC#1 | Problem pinning into filterLive (problems retained past 5s window) | unit | `:intellij-plugin:test --tests "*SessionModelTest"` | ✅ (extend) |
| SC#2 | Row fields incl. exception flag; renderer order | unit | `:intellij-plugin:test --tests "*CoroutineTreeModelTest"` `*CoroutineTreeStyleTest` | ✅ (extend) |
| SC#3 | Inspector card ordering + placeholder (logic already in VM) | unit | `:intellij-plugin:test --tests "*InspectorViewModelTest"` | ✅ (ordering is view; VM covered) |
| SC#4 | Round grouping plan: ✓/✗/⚠ counts, collapse economy, problem-round never folds, search filter | unit | `:intellij-plugin:test --tests "*RoundGroupingTest"` | ❌ Wave 0 |
| SC#4 | ViewMode state + mode-aware interval selection | unit | `:intellij-plugin:test --tests "*ViewModeTest"` / `*SessionPollerTest` | ⚠ extend `SessionPollerTest` (add reschedule); new `ViewModeTest` |
| SC#4 | Graph-disabled-in-All decision | unit | `:intellij-plugin:test --tests "*ToolWindowStateTest"` | ✅ (extend `ToolWindowStateTest`) |
| SC#5 | Tile values retarget (Active·Throughput·Leaks·Peak) + highlight index | unit | `:intellij-plugin:test --tests "*MetricTilesTest"` | ✅ (extend) |
| SC#1/4 | Live-app cross-highlight, segmented switch, no-lag-at-2800 | manual (live UAT) | `runIde` + backend/demo harness | manual — justified: Swing rendering/perf not unit-assertable |

### Sampling Rate
- **Per task commit:** the specific `--tests "*<Helper>Test"` for the touched helper (< 30s).
- **Per wave merge:** `cd backend && ./gradlew :intellij-plugin:test ktlintCheck detekt` (JDK 21).
- **Phase gate:** full plugin test suite green + live UAT of SC#1–5 before `/gsd-verify-work`.

### Wave 0 Gaps
- [ ] `model/SuspensionTrackerTest.kt` — covers SC#1 long-suspended enter/clear/threshold (poll-bounded, injected `nowNanos`)
- [ ] `model/RoundGroupingTest.kt` — covers SC#4 grouping plan (counts, 5-recent rule, summary fold, problem-round-never-folds, search filter)
- [ ] `toolwindow/ViewModeTest.kt` — covers SC#4 mode enum + interval selection (or fold into `SessionPollerTest`)
- [ ] Extend `SessionPollerTest` with `setInterval`/reschedule (retains sessionId, no double-schedule)
- [ ] Extend `SessionModelTest`, `MetricTilesTest`, `CoroutineTreeModelTest`, `CoroutineTreeStyleTest`, `ToolWindowStateTest` for the new fields/values
- Framework install: none needed (JUnit 5 + Platform test framework already configured)

## Security Domain

`security_enforcement` is not disabled in config (absent = enabled), but this phase's attack surface is minimal: it is **plugin-UI-only over already-fetched, already-trusted DTOs** from the plugin's own backend (auth handled in prior phases via `AGENT_TOKEN`). No new network input, no new persistence, no crypto, no user-supplied data parsed.

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | Backend auth unchanged; plugin uses existing `AGENT_TOKEN` |
| V3 Session Management | no | No new sessions |
| V4 Access Control | no | No new endpoints/resources |
| V5 Input Validation | minimal | History search box (D-17) is a local case-insensitive substring over in-memory strings — no injection sink; still, treat as literal (no regex compilation of user input) |
| V6 Cryptography | no | None |

### Known Threat Patterns for {IntelliJ plugin / Swing}
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Source-path info disclosure (creation-stack file:line shown in UI) | Information Disclosure | Already mitigated + documented as dev-only in Phase 13 (`plugin.xml` description, T-13-19); this phase surfaces the same already-fetched fields — no new exposure |
| Search-box treated as a pattern | (Tampering/DoS) | Use plain `contains(..., ignoreCase = true)` — never compile user input as a regex |
| EDT violations from off-thread UI mutation | (Availability/UX) | Keep the established discipline: model deliveries `invokeLater`'d; timeline fetch off-EDT; never `GlobalScope` |

## Sources

### Primary (HIGH confidence)
- Codebase (verified by direct read): `SessionModel.kt`, `CoroutineRow.kt`, `CoroutineTreeModel.kt`, `WireModels.kt`, `VizcoreToolWindowPanel.kt`, `SessionPoller.kt`, `SessionPollingService.kt`, `InspectorPanel.kt`, `InspectorViewModel.kt`, `MetricTilesPanel.kt`, `CoroutineTreeRenderer.kt`, `CoroutineTreeStyle.kt`, `CoroutineGraphPanel.kt`, `VizcoreSettings.kt`, `intellij-plugin/build.gradle.kts`, `backend/settings.gradle.kts`
- CONTEXT.md (24 locked decisions D-01..D-24), MANIFEST.md, sketch READMEs 004/005/006
- `SegmentedButton.kt` @ intellij-community branch 241 — API surface confirmed (`raw.githubusercontent.com/JetBrains/intellij-community/241/platform/platform-impl/src/com/intellij/ui/dsl/builder/SegmentedButton.kt`)

### Secondary (MEDIUM confidence)
- JetBrains IntelliJ Platform SDK docs — Kotlin UI DSL v2 (`segmentedButton`), Toggle Button / `ToggleAction` / `ActionToolbar` (plugins.jetbrains.com/docs/intellij)

### Tertiary (LOW confidence)
- Training knowledge for Swing lazy-tree (`TreeWillExpandListener` + dummy child) and `scrollPathToVisible` selection independence — standard idioms, verify in live UAT

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new deps; all APIs verified in build 241 or already in the codebase
- Architecture: HIGH — every pattern maps to an existing codebase idiom (CardLayout, FlashTracker, filterLive, tileValues)
- Pitfalls: HIGH — grounded in the actual code (hard-coded `LEAK_RISK_INDEX`, transient-empty guard, `expandAll`, poller rebuild) and project memory

**Research date:** 2026-07-02
**Valid until:** 2026-08-01 (stable — IntelliJ Platform 241 target fixed; revisit `segmentedButton` if the platform target is bumped)
