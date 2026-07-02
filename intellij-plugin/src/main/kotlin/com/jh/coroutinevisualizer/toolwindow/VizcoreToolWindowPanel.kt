package com.jh.coroutinevisualizer.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import com.jh.coroutinevisualizer.api.VizcoreApiClient
import com.jh.coroutinevisualizer.health.BackendHealthCheck
import com.jh.coroutinevisualizer.model.CoroutineRow
import com.jh.coroutinevisualizer.model.CoroutineTreeModel
import com.jh.coroutinevisualizer.model.ProblemCategory
import com.jh.coroutinevisualizer.model.RoundGrouping
import com.jh.coroutinevisualizer.model.RoundTreeModel
import com.jh.coroutinevisualizer.model.SessionModel
import com.jh.coroutinevisualizer.navigation.SourceNavigator
import com.jh.coroutinevisualizer.poll.SessionPollingService
import com.jh.coroutinevisualizer.run.VizcoreRunConfigurationExtension
import com.jh.coroutinevisualizer.settings.VizcoreSettings
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.FlowLayout
import java.util.concurrent.ConcurrentHashMap
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/** Content-state selector for the native tool window. Pure; the unit gate lives in ToolWindowStateTest. */
enum class ContentState {
    NOT_LAUNCHED,
    CONNECTING,
    BACKEND_DOWN,
    LIVE,
    ;

    companion object {
        fun of(
            correlation: String?,
            backendDown: Boolean,
            hasModel: Boolean,
        ): ContentState =
            when {
                correlation == null -> NOT_LAUNCHED
                backendDown -> BACKEND_DOWN
                hasModel -> LIVE
                else -> CONNECTING
            }
    }
}

/**
 * Native tool window content: assembles the tree + inspector + metric tiles from the pieces built in
 * Tasks 2–8 and drives them from [SessionPollingService]. On construction it selects one of four
 * content states ([ContentState]) — NOT_LAUNCHED / BACKEND_DOWN / CONNECTING / LIVE — via a
 * [CardLayout], then wires the live view once a model arrives.
 *
 * Threading: model deliveries arrive on the EDT (the service invokeLater's them). Timeline fetches on
 * tree selection run off the EDT and hop back via invokeLater. Never uses GlobalScope.
 */
@Suppress("TooManyFunctions") // presentational + wiring host: many small build/handler helpers
class VizcoreToolWindowPanel(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(BorderLayout()) {
    private val cards = CardLayout()
    private val content = JPanel(cards)

    private val coroutineTreeModel = CoroutineTreeModel()
    private val tiles = MetricTilesPanel()
    private val inspector = InspectorPanel(onJump = ::onJump)

    /** Held as a field so cross-highlight (D-08) can set [CoroutineTreeRenderer.softHighlightId]. */
    private val treeRenderer = CoroutineTreeRenderer()
    private val tree: Tree = buildTree()
    private val graphPanel = CoroutineGraphPanel(onSelect = ::selectCoroutine)
    private val graphScroll = JBScrollPane(graphPanel)

    /** All-mode round-grouped history tree (SC#4); its OWN renderer holds per-tree flash state. */
    private val roundTreeModel = RoundTreeModel()
    private val allTreeRenderer = CoroutineTreeRenderer()
    private val allTree: Tree = buildAllTree()

    /** Live/All segmented control (D-18): two flat toggles manually paired (exactly one active). */
    private val liveToggle = JToggleButton("Live")
    private val allToggle = JToggleButton(ViewMode.allLabel(0))

    /** Mode pill (D-18): swaps LIVE↔HISTORY text/color via [setViewMode]. Field so it stays mutable. */
    private val modePill = JBLabel(LIVE_PILL_TEXT).apply { foreground = LIVE_GREEN }

    /** Tree/graph toggle held as a field so All mode can disable it (D-20). */
    private val graphToggle = JToggleButton("Graph")

    /** All-mode-only history search (D-17); hidden in Live, shown in All. */
    private val searchField = SearchTextField()

    /** Active history search query; null = no search (blank field). EDT-confined. */
    private var activeSearchQuery: String? = null

    /** Current live-view mode (D-21: every session starts Live). EDT-confined. */
    private var viewMode: ViewMode = ViewMode.LIVE

    /** Persistent Problems strip (D-01): healthy line + single-select category chips. */
    private val strip = ProblemsStripPanel(onFilterChange = ::onFilterChange)

    /** Problems-detail list (D-05): the problems-first default card in the right pane. */
    private val problemsDetail = ProblemsDetailPanel(onHighlight = ::onProblemHighlight, onInspect = ::onProblemInspect)

    /** Contextual right pane (D-04): problems list (default) OR the selected-coroutine inspector. */
    private val rightCards = CardLayout()
    private val rightPane = JPanel(rightCards)

    /** Active problem-category chip filter; null = no filter (D-03). EDT-confined. */
    private var activeProblemFilter: ProblemCategory? = null

    /** Resolved "file:line" suspension sites by coroutine id (D-06), populated off-EDT. */
    private val suspensionSites: MutableMap<String, String> = ConcurrentHashMap()

    /** Guards against re-issuing an in-flight suspension-site fetch for the same id (T-15-06). */
    private val suspensionFetchInFlight = ConcurrentHashMap.newKeySet<String>()

    /** Swappable left pane: "TREE" (default) and "GRAPH". */
    private val leftCardLayout = CardLayout()
    private val leftCards = JPanel(leftCardLayout)

    /** Expand the (invisible) root once the first data arrives, so top-level coroutines are visible. */
    private var expandedOnce = false

    private val backendUrl = VizcoreSettings.getInstance().backendUrl
    private val apiClient = VizcoreApiClient(backendUrl, VizcoreRunConfigurationExtension.AGENT_TOKEN)

    /** Latest delivered model, kept for tree-selection node lookup. Read/written on the EDT. */
    @Volatile private var latestModel: SessionModel? = null

    /** Whether the graph card is currently showing. EDT-confined, so a plain field suffices. */
    private var graphVisible = false

    private var frozen = false
    private val freezeButton =
        JButton("Pause view").apply {
            toolTipText = "Pauses the live view only — your application keeps running."
        }

    /** The correlation we've already started polling for; guards [startFor] against double-start. */
    @Volatile private var startedCorrelation: String? = null

    init {
        content.add(placeholder(NOT_LAUNCHED_TEXT), ContentState.NOT_LAUNCHED.name)
        content.add(placeholder(CONNECTING_TEXT), ContentState.CONNECTING.name)
        content.add(placeholder(BACKEND_DOWN_TEXT), ContentState.BACKEND_DOWN.name)
        content.add(buildLiveView(), ContentState.LIVE.name)
        add(content, BorderLayout.CENTER)

        // React to a launch armed AFTER this (cached) content was built — IntelliJ never re-runs the
        // factory on hide/show, so without this a pre-existing panel would stay NOT_LAUNCHED forever.
        project.messageBus
            .connect(parentDisposable)
            .subscribe(
                VIZCORE_LAUNCH_TOPIC,
                VizcoreLaunchListener { correlation ->
                    startFor(correlation)
                },
            )

        // Handle the run-then-open ordering: if a correlation is already armed, start immediately.
        val armed = VizcoreLaunchState.getInstance(project).correlation
        if (armed != null) startFor(armed) else showState(ContentState.NOT_LAUNCHED)
    }

    /** Begin (or reuse) polling for [correlation]. Idempotent; safe to call from init AND the topic. */
    private fun startFor(correlation: String) {
        if (startedCorrelation == correlation) {
            return
        }
        startedCorrelation = correlation
        // D-21: every new session starts in Live; mode never persists across correlations.
        setViewMode(ViewMode.LIVE)

        showState(ContentState.CONNECTING)

        // The health probe is blocking HTTP (2s connect + 2s read timeouts) and startFor always runs
        // on the EDT (init + the synchronous launch-topic publish) — probe on a pooled thread and hop
        // back for the state switch, or an unreachable backend freezes the whole IDE for ~4s.
        ApplicationManager.getApplication().executeOnPooledThread {
            val health = BackendHealthCheck.check(backendUrl)
            ApplicationManager.getApplication().invokeLater {
                if (health is BackendHealthCheck.HealthStatus.Down) {
                    showState(ContentState.BACKEND_DOWN)
                } else {
                    startPolling(correlation)
                }
            }
        }
    }

    /** Registers the model/error listeners and starts the poll loop. Call on the EDT. */
    private fun startPolling(correlation: String) {
        val service = SessionPollingService.getInstance(project)
        service.setListener(
            onModel = { model ->
                // Already on the EDT (the service invokeLater's deliveries).
                onModelDelivered(model)
            },
            onError = { error ->
                // Keep the last good model on screen; transient poll failures self-heal next tick.
                LOG.warn("Coroutine visualizer poll error", error)
            },
        )
        service.start(correlation)
    }

    /**
     * Per-poll model delivery (EDT). The transient-empty guard short-circuits BEFORE the mode branch so
     * a momentary empty poll keeps the last good tree in BOTH modes (Pitfall 4). Strip, tiles, problems
     * detail, and the "All n" count are full-session in BOTH modes (SC#5/D-12); only the left tree
     * differs — Live renders the filtered [applyModelToTree], All renders [applyAllModel].
     */
    private fun onModelDelivered(model: SessionModel) {
        val previous = latestModel
        // Key the guard on the UNFILTERED hierarchy: a backend blip returns nothing at all, while a
        // quiescent app (all coroutines completed + live window elapsed) still has a fullHierarchy.
        // Keying on the live-filtered view froze every panel forever once the app went quiet.
        if (model.fullHierarchy.isEmpty() && previous != null && previous.fullHierarchy.isNotEmpty()) {
            return // Transient empty poll (backend blip / stale poller) — keep the last good tree.
        }
        latestModel = model
        strip.update(model.problems)
        problemsDetail.show(model.problems, activeProblemFilter)
        tiles.update(model.tiles)
        allToggle.text = ViewMode.allLabel(model.fullHierarchy.size)
        if (viewMode == ViewMode.ALL) {
            applyAllModel(model)
        } else {
            applyModelToTree(model)
            if (!expandedOnce && model.hierarchy.isNotEmpty()) {
                expandedOnce = true
                TreeUtil.expandAll(tree)
            }
        }
        resolveSuspensionSites(model)
        showState(ContentState.LIVE)
    }

    private fun showState(state: ContentState) {
        cards.show(content, state.name)
    }

    private fun placeholder(text: String): JPanel {
        val panel = JPanel(BorderLayout())
        val label = JBLabel(text)
        label.horizontalAlignment = SwingConstants.CENTER
        label.foreground = JBColor.GRAY
        panel.add(label, BorderLayout.CENTER)
        return panel
    }

    private fun buildLiveView(): JPanel {
        val header = JPanel(BorderLayout())
        header.border = JBUI.Borders.empty(HEADER_PADDING)
        header.add(tiles, BorderLayout.CENTER)

        val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT, TOOLBAR_GAP, 0))
        toolbar.isOpaque = false
        toolbar.add(modePill)
        // Live/All segmented control sits LEFT of the Graph toggle (D-18).
        buildModeToggles().forEach { toolbar.add(it) }
        configureGraphToggle()
        toolbar.add(graphToggle)
        freezeButton.addActionListener { toggleFreeze() }
        toolbar.add(freezeButton)
        header.add(toolbar, BorderLayout.EAST)

        // D-01: the Problems strip is a full-width row directly under the tiles header, above the
        // splitter. A vertical Box stacks [header] then [strip] so the strip spans the whole width.
        val topStack = JPanel()
        topStack.layout = BoxLayout(topStack, BoxLayout.Y_AXIS)
        topStack.add(header)
        topStack.add(strip)

        val panel = JPanel(BorderLayout())
        panel.add(topStack, BorderLayout.NORTH)
        panel.add(buildSplitter(), BorderLayout.CENTER)
        return panel
    }

    /**
     * Builds the Live/All segmented control (D-18). Two flat [JToggleButton]s manually paired — each
     * click funnels through [setViewMode], which re-asserts exactly one selected (clicking the active
     * one re-selects it). Returned so [buildLiveView] can insert them left of the Graph toggle.
     */
    private fun buildModeToggles(): List<JComponent> {
        liveToggle.isSelected = true
        liveToggle.addActionListener { setViewMode(ViewMode.LIVE) }
        allToggle.addActionListener { setViewMode(ViewMode.ALL) }
        // History search (D-17): All-mode-only affordance beside the mode toggles.
        searchField.isVisible = false
        searchField.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    activeSearchQuery = searchField.text.trim().ifEmpty { null }
                    // 2,800 substring checks are trivial — re-render immediately, no debounce.
                    if (viewMode == ViewMode.ALL) latestModel?.let { applyAllModel(it) }
                }
            },
        )
        return listOf(liveToggle, allToggle, searchField)
    }

    /** Wires the tree/graph toggle listener (D-20 disable is applied in [setViewMode]). */
    private fun configureGraphToggle() {
        graphToggle.toolTipText = GRAPH_TOOLTIP
        graphToggle.addActionListener {
            graphVisible = graphToggle.isSelected
            leftCardLayout.show(leftCards, if (graphToggle.isSelected) GRAPH_CARD else TREE_CARD)
            // The graph may be stale if the model updated while it was hidden — recompute now.
            if (graphToggle.isSelected) {
                latestModel?.let { graphPanel.setModel(GraphLayout.compute(it.hierarchy, it.leakIds)) }
            }
        }
    }

    /**
     * Single funnel for every mode effect (D-18/D-19/D-20): toggle selection, REAL poll cadence
     * ([SessionPollingService.setMode]), pill swap, left card, graph enable/disable, then an immediate
     * re-render from the latest model so the view never waits for the next poll.
     */
    private fun setViewMode(mode: ViewMode) {
        viewMode = mode
        liveToggle.isSelected = mode == ViewMode.LIVE
        allToggle.isSelected = mode == ViewMode.ALL
        searchField.isVisible = mode == ViewMode.ALL // history search is an All-mode affordance (D-17)
        SessionPollingService.getInstance(project).setMode(mode)
        if (mode == ViewMode.ALL) {
            modePill.text = HISTORY_PILL_TEXT
            modePill.foreground = HISTORY_AMBER
            graphVisible = false
            graphToggle.isSelected = false
            graphToggle.isEnabled = false
            graphToggle.toolTipText = GRAPH_DISABLED_TOOLTIP
            leftCardLayout.show(leftCards, ALL_CARD)
        } else {
            modePill.text = LIVE_PILL_TEXT
            modePill.foreground = LIVE_GREEN
            graphToggle.isEnabled = ViewMode.graphEnabled(mode)
            graphToggle.toolTipText = GRAPH_TOOLTIP
            leftCardLayout.show(leftCards, if (graphToggle.isSelected) GRAPH_CARD else TREE_CARD)
        }
        latestModel?.let { if (mode == ViewMode.ALL) applyAllModel(it) else applyModelToTree(it) }
    }

    /** Renders the All-mode round tree from the UNFILTERED full-session hierarchy (D-18/D-19). */
    private fun applyAllModel(model: SessionModel) {
        roundTreeModel.apply(
            model.fullHierarchy,
            model.leakIds,
            model.problems,
            System.nanoTime(),
            currentMatchIds(model),
        )
    }

    /**
     * The single D-17/D-03 match-set funnel for the All tree: history search AND the active problem
     * chip narrow the same way. Both null → null (normal collapse plan); one non-null → it; both →
     * their intersection. The search query is used ONLY via [RoundGrouping.searchMatchIds] (plain
     * substring, never a compiled Regex — T-15-03, ASVS V5).
     */
    private fun currentMatchIds(model: SessionModel): Set<String>? {
        val searchIds = activeSearchQuery?.let { RoundGrouping.searchMatchIds(model.fullHierarchy, it) }
        val chipIds =
            activeProblemFilter?.let { f ->
                model.problems
                    .filter { it.category == f }
                    .map { it.coroutineId }
                    .toSet()
            }
        return when {
            searchIds != null && chipIds != null -> searchIds intersect chipIds
            else -> searchIds ?: chipIds
        }
    }

    private fun toggleFreeze() {
        val service = SessionPollingService.getInstance(project)
        if (frozen) {
            service.unfreeze()
            frozen = false
            freezeButton.text = "Pause view"
        } else {
            service.freeze()
            frozen = true
            freezeButton.text = "Resume view"
        }
    }

    private fun buildSplitter(): OnePixelSplitter {
        leftCards.add(JBScrollPane(tree), TREE_CARD)
        leftCards.add(graphScroll, GRAPH_CARD)
        leftCards.add(JBScrollPane(allTree), ALL_CARD)
        leftCardLayout.show(leftCards, TREE_CARD)

        // D-04/D-05: right pane swaps contextually between the problems list (default) and the
        // selected-coroutine inspector — no tabs, no vertical stack.
        rightPane.add(problemsDetail, RIGHT_PROBLEMS)
        rightPane.add(inspector, RIGHT_INSPECTOR)
        rightCards.show(rightPane, RIGHT_PROBLEMS)

        val splitter = OnePixelSplitter(false, SPLITTER_PROPORTION)
        splitter.firstComponent = leftCards
        splitter.secondComponent = rightPane
        return splitter
    }

    private fun showProblemsCard() = rightCards.show(rightPane, RIGHT_PROBLEMS)

    private fun showInspectorCard() = rightCards.show(rightPane, RIGHT_INSPECTOR)

    private fun buildTree(): Tree {
        val tree = Tree(coroutineTreeModel.treeModel)
        tree.cellRenderer = treeRenderer
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.addTreeSelectionListener { onTreeSelection(tree, treeRenderer) }
        return tree
    }

    /**
     * The All-mode history tree — a SECOND [Tree] over [roundTreeModel] with its OWN renderer (flash
     * state is per-tree). Coroutine-row selections open the inspector exactly like Live; group / summary
     * / placeholder rows fall through to the problems card. NEVER expandAll'd — the SC#4 perf bar relies
     * on lazy materialization (Task 2's TreeWillExpandListener), so only the Live tree eager-expands.
     */
    private fun buildAllTree(): Tree {
        val tree = Tree(roundTreeModel.treeModel)
        tree.cellRenderer = allTreeRenderer
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.addTreeSelectionListener { onTreeSelection(tree, allTreeRenderer) }
        // Lazy materialization (D-15): the ONLY place history subtrees get built — the SC#4 cost model.
        tree.addTreeWillExpandListener(
            object : TreeWillExpandListener {
                override fun treeWillExpand(event: TreeExpansionEvent) {
                    val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    // materialize() no-ops unless the group still carries its placeholder child.
                    roundTreeModel.materialize(node)
                }

                override fun treeWillCollapse(event: TreeExpansionEvent) {
                    // No-op: keep materialized nodes so instance reuse keeps refreshes cheap (D-15).
                }
            },
        )
        return tree
    }

    private fun onTreeSelection(
        tree: Tree,
        renderer: CoroutineTreeRenderer,
    ) {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val row = node?.userObject as? CoroutineRow
        if (row == null) {
            // Selection cleared, or a group/summary/placeholder row → problems-first default (D-04/D-05).
            inspector.show(null)
            showProblemsCard()
            return
        }
        // A real selection lands → clear the soft highlight and swap in the inspector (D-08).
        renderer.softHighlightId = null
        tree.repaint()
        showInspectorCard()
        selectCoroutine(row.id)
    }

    /**
     * Chip filter (D-03): records the active category and immediately re-applies the latest model to
     * BOTH the tree (ancestor-closed) and the detail list — no wait for the next poll.
     */
    private fun onFilterChange(category: ProblemCategory?) {
        activeProblemFilter = category
        val model = latestModel ?: return
        applyModelToTree(model)
        problemsDetail.show(model.problems, activeProblemFilter)
        // D-03 in both modes: a chip filters the All tree via the same match-set auto-expand path.
        if (viewMode == ViewMode.ALL) applyAllModel(model)
    }

    /**
     * Applies the model to the tree, narrowing to the active problem category (ancestor-closed) when a
     * chip is selected (D-03). The graph input is intentionally left unfiltered this phase.
     */
    private fun applyModelToTree(model: SessionModel) {
        val treeHierarchy =
            activeProblemFilter?.let {
                SessionModel.filterToProblemCategory(model.hierarchy, model.problems, it)
            } ?: model.hierarchy
        coroutineTreeModel.apply(treeHierarchy, model.leakIds)
        if (graphVisible) {
            graphPanel.setModel(GraphLayout.compute(model.hierarchy, model.leakIds))
        }
    }

    /**
     * Stage 1 of the cross-highlight (D-08): a single problem click scrolls the tree to the coroutine
     * with a NON-selecting soft highlight. It MUST NOT touch the selection model — a selection would
     * fire [onTreeSelection] and swap in the inspector, defeating the "right pane stays on why" rule.
     */
    private fun onProblemHighlight(coroutineId: String) {
        val node = coroutineTreeModel.nodeFor(coroutineId) ?: return
        treeRenderer.softHighlightId = coroutineId
        tree.scrollPathToVisible(TreePath(node.path))
        tree.repaint()
    }

    /**
     * Stage 2 of the cross-highlight (D-08): a double-click / Inspect selects the coroutine, which
     * fires [onTreeSelection] → inspector swap. Setting the selection path is the ONLY select call.
     */
    private fun onProblemInspect(coroutineId: String) {
        val node = coroutineTreeModel.nodeFor(coroutineId) ?: return
        tree.selectionPath = TreePath(node.path)
    }

    /**
     * D-06 enrichment: for each long-suspended problem, re-apply a cached file:line site (so it
     * survives the per-poll rebuild) or fetch it off-EDT when unknown. Bounded — problems are few.
     */
    private fun resolveSuspensionSites(model: SessionModel) {
        val service = SessionPollingService.getInstance(project)
        val sid = service.currentSessionId() ?: return
        model.problems
            .filter { it.category == ProblemCategory.LONG_SUSPENDED }
            .forEach { problem ->
                val cached = suspensionSites[problem.coroutineId]
                if (cached != null) {
                    problemsDetail.setSuspensionSite(problem.coroutineId, cached)
                } else {
                    fetchSuspensionSite(sid, problem.coroutineId)
                }
            }
    }

    private fun fetchSuspensionSite(
        sessionId: String,
        coroutineId: String,
    ) {
        if (!suspensionFetchInFlight.add(coroutineId)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val site = resolveSuspensionSite(sessionId, coroutineId) ?: return@executeOnPooledThread
                suspensionSites[coroutineId] = site
                ApplicationManager.getApplication().invokeLater {
                    problemsDetail.setSuspensionSite(coroutineId, site)
                }
            } finally {
                suspensionFetchInFlight.remove(coroutineId)
            }
        }
    }

    /** Last timeline event whose suspension point carries both a file name and line → "file:line". */
    private fun resolveSuspensionSite(
        sessionId: String,
        coroutineId: String,
    ): String? {
        val point =
            apiClient
                .timeline(sessionId, coroutineId)
                ?.events
                ?.lastOrNull { it.suspensionPoint?.fileName != null && it.suspensionPoint?.lineNumber != null }
                ?.suspensionPoint
        return point?.let { "${it.fileName}:${it.lineNumber}" }
    }

    /**
     * Shared selection handler for both the tree and the graph: fetches the coroutine's timeline
     * off the EDT and shows it in the inspector on the EDT. Node is looked up by id from [latestModel].
     */
    private fun selectCoroutine(coroutineId: String) {
        val service = SessionPollingService.getInstance(project)
        val model = latestModel
        ApplicationManager.getApplication().executeOnPooledThread {
            val sid = service.currentSessionId()
            val timeline = if (sid != null) apiClient.timeline(sid, coroutineId) else null
            val hierarchyNode = model?.hierarchy?.firstOrNull { it.id == coroutineId }
            ApplicationManager.getApplication().invokeLater {
                inspector.show(InspectorViewModel.from(timeline, hierarchyNode))
            }
        }
    }

    private fun onJump(
        fileName: String,
        line: Int,
    ) {
        SourceNavigator(project).jumpTo(fileName, null, line)
    }

    private companion object {
        private val LOG = Logger.getInstance(VizcoreToolWindowPanel::class.java)

        const val NOT_LAUNCHED_TEXT =
            "Run a configuration with \"Run with Coroutine Visualizer\" to open the live view."
        const val CONNECTING_TEXT = "Connecting to your app…"
        const val BACKEND_DOWN_TEXT =
            "Backend not reachable — check Settings › Tools › Coroutine Visualizer"

        const val HEADER_PADDING = 6
        const val TOOLBAR_GAP = 8
        const val SPLITTER_PROPORTION = 0.6f

        const val TREE_CARD = "TREE"
        const val GRAPH_CARD = "GRAPH"
        const val ALL_CARD = "ALL"

        const val RIGHT_PROBLEMS = "PROBLEMS"
        const val RIGHT_INSPECTOR = "INSPECTOR"

        const val LIVE_PILL_TEXT = "● LIVE · ~200ms"
        const val HISTORY_PILL_TEXT = "● HISTORY · ~1.5s"

        const val GRAPH_TOOLTIP = "Toggle between the tree view and the parent-child graph view."
        const val GRAPH_DISABLED_TOOLTIP = "Graph is live-only"

        val LIVE_GREEN: JBColor = JBColor(Color(0x2E7D32), Color(0x66BB6A))

        /** Amber "history" accent for the mode pill in All mode (never the danger red). */
        val HISTORY_AMBER: JBColor = JBColor(Color(0xF5A524), Color(0xF5A524))
    }
}
