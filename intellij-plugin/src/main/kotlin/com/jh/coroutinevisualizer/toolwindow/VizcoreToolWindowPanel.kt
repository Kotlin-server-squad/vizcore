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
import com.jh.coroutinevisualizer.api.VizcoreApiClient
import com.jh.coroutinevisualizer.health.BackendHealthCheck
import com.jh.coroutinevisualizer.model.CoroutineRow
import com.jh.coroutinevisualizer.model.CoroutineTreeModel
import com.jh.coroutinevisualizer.model.SessionModel
import com.jh.coroutinevisualizer.navigation.SourceNavigator
import com.jh.coroutinevisualizer.poll.SessionPollingService
import com.jh.coroutinevisualizer.run.VizcoreRunConfigurationExtension
import com.jh.coroutinevisualizer.settings.VizcoreSettings
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
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
class VizcoreToolWindowPanel(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(BorderLayout()) {
    private val cards = CardLayout()
    private val content = JPanel(cards)

    private val coroutineTreeModel = CoroutineTreeModel()
    private val tiles = MetricTilesPanel()
    private val inspector = InspectorPanel(onJump = ::onJump)

    private val backendUrl = VizcoreSettings.getInstance().backendUrl
    private val apiClient = VizcoreApiClient(backendUrl, VizcoreRunConfigurationExtension.AGENT_TOKEN)

    /** Latest delivered model, kept for tree-selection node lookup. Read/written on the EDT. */
    @Volatile private var latestModel: SessionModel? = null

    private var frozen = false
    private val freezeButton = JButton("Freeze")

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
                    LOG.info("[vizcore-diag] launch event received: correlation=$correlation")
                    startFor(correlation)
                },
            )

        // Handle the run-then-open ordering: if a correlation is already armed, start immediately.
        val armed = VizcoreLaunchState.getInstance(project).correlation
        LOG.info("[vizcore-diag] panel constructed; armed correlation at open = $armed")
        if (armed != null) startFor(armed) else showState(ContentState.NOT_LAUNCHED)
    }

    /** Begin (or reuse) polling for [correlation]. Idempotent; safe to call from init AND the topic. */
    private fun startFor(correlation: String) {
        if (startedCorrelation == correlation) {
            LOG.info("[vizcore-diag] startFor($correlation) ignored — already polling this correlation")
            return
        }
        startedCorrelation = correlation

        val health = BackendHealthCheck.check(backendUrl)
        LOG.info("[vizcore-diag] startFor($correlation) backendUrl=$backendUrl health=$health")
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
                    LOG.info("[vizcore-diag] ignoring empty poll; keeping ${previous.hierarchy.size} coroutines")
                } else {
                    LOG.info("[vizcore-diag] model delivered: ${model.hierarchy.size} coroutines -> LIVE")
                    latestModel = model
                    coroutineTreeModel.apply(model.hierarchy, model.leakIds)
                    tiles.update(model.tiles)
                    showState(ContentState.LIVE)
                }
            },
            onError = { error ->
                // Keep the last good model on screen; transient poll failures self-heal next tick.
                LOG.warn("[vizcore-diag] poll error surfaced to panel", error)
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
        freezeButton.addActionListener { toggleFreeze() }
        toolbar.add(freezeButton)
        header.add(toolbar, BorderLayout.EAST)

        val panel = JPanel(BorderLayout())
        panel.add(header, BorderLayout.NORTH)
        panel.add(buildSplitter(), BorderLayout.CENTER)
        return panel
    }

    private fun toggleFreeze() {
        val service = SessionPollingService.getInstance(project)
        if (frozen) {
            service.unfreeze()
            frozen = false
            freezeButton.text = "Freeze"
        } else {
            service.freeze()
            frozen = true
            freezeButton.text = "Resume"
        }
    }

    private fun buildSplitter(): OnePixelSplitter {
        val splitter = OnePixelSplitter(false, SPLITTER_PROPORTION)
        splitter.firstComponent = JBScrollPane(buildTree())
        splitter.secondComponent = inspector
        return splitter
    }

    private fun buildTree(): Tree {
        val tree = Tree(coroutineTreeModel.treeModel)
        tree.cellRenderer = CoroutineTreeRenderer()
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
            inspector.show(null)
            return
        }
        val service = SessionPollingService.getInstance(project)
        val model = latestModel
        ApplicationManager.getApplication().executeOnPooledThread {
            val sid = service.currentSessionId()
            val timeline = if (sid != null) apiClient.timeline(sid, row.id) else null
            val hierarchyNode = model?.hierarchy?.firstOrNull { it.id == row.id }
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

        val LIVE_GREEN: JBColor = JBColor(Color(0x2E7D32), Color(0x66BB6A))
    }
}
