package com.jh.coroutinevisualizer.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
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
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingConstants
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

        val health = BackendHealthCheck.check(backendUrl)
        if (health is BackendHealthCheck.HealthStatus.Down) {
            showState(ContentState.BACKEND_DOWN)
            return
        }

        showState(ContentState.CONNECTING)

        val service = SessionPollingService.getInstance(project)
        service.setListener(
            onModel = { model ->
                // Already on the EDT (the service invokeLater's deliveries).
                val previous = latestModel
                if (model.hierarchy.isEmpty() && previous != null && previous.hierarchy.isNotEmpty()) {
                    // Transient empty poll (backend blip / stale poller) — keep the last good tree.
                } else {
                    latestModel = model
                    strip.update(model.problems)
                    problemsDetail.show(model.problems, activeProblemFilter)
                    applyModelToTree(model)
                    tiles.update(model.tiles)
                    if (!expandedOnce && model.hierarchy.isNotEmpty()) {
                        expandedOnce = true
                        TreeUtil.expandAll(tree)
                    }
                    resolveSuspensionSites(model)
                    showState(ContentState.LIVE)
                }
            },
            onError = { error ->
                // Keep the last good model on screen; transient poll failures self-heal next tick.
                LOG.warn("Coroutine visualizer poll error", error)
            },
        )
        service.start(correlation)
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
        val liveLabel = JBLabel("● LIVE")
        liveLabel.foreground = LIVE_GREEN
        toolbar.add(liveLabel)
        val graphToggle =
            JToggleButton("Graph").apply {
                toolTipText = "Toggle between the tree view and the parent-child graph view."
                addActionListener {
                    graphVisible = isSelected
                    leftCardLayout.show(leftCards, if (isSelected) GRAPH_CARD else TREE_CARD)
                    // The graph may be stale if the model updated while it was hidden — recompute now.
                    if (isSelected) {
                        latestModel?.let { graphPanel.setModel(GraphLayout.compute(it.hierarchy, it.leakIds)) }
                    }
                }
            }
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
        tree.addTreeSelectionListener { onTreeSelection(tree) }
        return tree
    }

    private fun onTreeSelection(tree: Tree) {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val row = node?.userObject as? CoroutineRow
        if (row == null) {
            // Selection cleared → back to the problems-first default (D-04/D-05).
            inspector.show(null)
            showProblemsCard()
            return
        }
        // A real selection lands → clear the soft highlight and swap in the inspector (D-08).
        treeRenderer.softHighlightId = null
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

        const val RIGHT_PROBLEMS = "PROBLEMS"
        const val RIGHT_INSPECTOR = "INSPECTOR"

        val LIVE_GREEN: JBColor = JBColor(Color(0x2E7D32), Color(0x66BB6A))
    }
}
