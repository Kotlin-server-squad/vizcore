package com.jh.coroutinevisualizer.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.jh.coroutinevisualizer.ui.renderers.CoroutineNodeRenderer
import com.jh.proj.coroutineviz.models.CoroutineNode
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Tree panel showing the coroutine hierarchy.
 * Updates in real-time as events arrive from the VizSession.
 */
class TreePanel(
    private val project: Project,
    private val session: VizSession,
) : JPanel() {
    private val rootNode = DefaultMutableTreeNode("Coroutines")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = JTree(treeModel)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        layout = BorderLayout()
        tree.cellRenderer = CoroutineNodeRenderer()
        tree.isRootVisible = true
        tree.showsRootHandles = true

        add(JBScrollPane(tree), BorderLayout.CENTER)

        // Start listening for events
        startEventListener()
    }

    private fun startEventListener() {
        scope.launch {
            session.coroutineLifecycleFlow().collect { event ->
                SwingUtilities.invokeLater {
                    refreshTree()
                }
            }
        }
    }

    private fun refreshTree() {
        rootNode.removeAllChildren()

        val coroutines =
            session.snapshot.coroutines.values
                .toList()
        val roots = coroutines.filter { it.parentId == null }

        for (root in roots) {
            val node = buildTreeNode(root, coroutines)
            rootNode.add(node)
        }

        treeModel.reload()
        expandAll()
    }

    private fun buildTreeNode(
        coroutine: CoroutineNode,
        allCoroutines: List<CoroutineNode>,
    ): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(coroutine)
        val children = allCoroutines.filter { it.parentId == coroutine.id }
        for (child in children) {
            node.add(buildTreeNode(child, allCoroutines))
        }
        return node
    }

    private fun expandAll() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    fun dispose() {
        scope.cancel()
    }
}
