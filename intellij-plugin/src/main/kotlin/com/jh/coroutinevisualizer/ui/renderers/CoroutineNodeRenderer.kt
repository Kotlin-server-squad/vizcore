package com.jh.coroutinevisualizer.ui.renderers

import com.intellij.icons.AllIcons
import com.jh.proj.coroutineviz.models.CoroutineNode
import com.jh.proj.coroutineviz.models.CoroutineState
import java.awt.Component
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer

/**
 * Custom tree cell renderer that displays coroutine nodes with state-colored icons.
 */
class CoroutineNodeRenderer : DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)

        val treeNode = value as? DefaultMutableTreeNode ?: return this
        val coroutine = treeNode.userObject as? CoroutineNode ?: return this

        text = buildLabel(coroutine)
        icon = getStateIcon(coroutine.state)
        toolTipText = buildTooltip(coroutine)

        return this
    }

    private fun buildLabel(coroutine: CoroutineNode): String {
        val name = coroutine.label ?: coroutine.id
        return "$name [${coroutine.state}]"
    }

    private fun getStateIcon(state: CoroutineState): Icon =
        when (state) {
            CoroutineState.CREATED -> AllIcons.Actions.AddFile
            CoroutineState.ACTIVE -> AllIcons.Actions.Execute
            CoroutineState.SUSPENDED -> AllIcons.Actions.Pause
            CoroutineState.WAITING_FOR_CHILDREN -> AllIcons.Actions.ShowAsTree
            CoroutineState.COMPLETED -> AllIcons.Actions.Checked
            CoroutineState.CANCELLED -> AllIcons.Actions.Cancel
            CoroutineState.FAILED -> AllIcons.General.Error
        }

    private fun buildTooltip(coroutine: CoroutineNode): String =
        """
        ID: ${coroutine.id}
        Job: ${coroutine.jobId}
        Parent: ${coroutine.parentId ?: "none"}
        Scope: ${coroutine.scopeId}
        State: ${coroutine.state}
        """.trimIndent()
}
