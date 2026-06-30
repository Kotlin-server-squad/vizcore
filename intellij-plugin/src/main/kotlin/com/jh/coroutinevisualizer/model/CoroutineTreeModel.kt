package com.jh.coroutinevisualizer.model

import com.jh.coroutinevisualizer.api.HierarchyNodeDto
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Builds a Swing tree from a coroutine hierarchy and diffs it across [apply] calls.
 *
 * The diff reuses the same [DefaultMutableTreeNode] instance for an unchanged coroutine id
 * (replacing only its `userObject`), so a live JTree's expansion and selection survive a refresh.
 */
class CoroutineTreeModel {
    private val root = DefaultMutableTreeNode()
    val treeModel: DefaultTreeModel = DefaultTreeModel(root)

    private val nodesById = mutableMapOf<String, DefaultMutableTreeNode>()

    /**
     * Reconcile the tree to [hierarchy]. Nodes for ids present in both the old and new state are
     * reused; their rows (and parent links) are updated in place. Ids absent from [hierarchy] are
     * removed. [leakIds] marks the corresponding rows as leaked.
     */
    fun apply(
        hierarchy: List<HierarchyNodeDto>,
        leakIds: Set<String>,
    ) {
        val incomingIds = hierarchy.mapTo(mutableSetOf()) { it.id }

        removeStaleNodes(incomingIds)
        upsertNodes(hierarchy, leakIds)
        wireParents(hierarchy)
    }

    private fun removeStaleNodes(incomingIds: Set<String>) {
        val staleIds = nodesById.keys.filter { it !in incomingIds }
        for (id in staleIds) {
            val node = nodesById.remove(id) ?: continue
            if (node.parent != null) {
                treeModel.removeNodeFromParent(node)
            }
        }
    }

    private fun upsertNodes(
        hierarchy: List<HierarchyNodeDto>,
        leakIds: Set<String>,
    ) {
        for (dto in hierarchy) {
            val row = toRow(dto, leakIds)
            val existing = nodesById[dto.id]
            if (existing == null) {
                nodesById[dto.id] = DefaultMutableTreeNode(row)
            } else {
                existing.userObject = row
                if (existing.parent != null) {
                    treeModel.nodeChanged(existing)
                }
            }
        }
    }

    private fun wireParents(hierarchy: List<HierarchyNodeDto>) {
        for (dto in hierarchy) {
            val node = nodesById.getValue(dto.id)
            val desiredParent = dto.parentId?.let { nodesById[it] } ?: root
            if (node.parent != desiredParent) {
                if (node.parent != null) {
                    treeModel.removeNodeFromParent(node)
                }
                treeModel.insertNodeInto(node, desiredParent, desiredParent.childCount)
            }
        }
    }

    private fun toRow(
        dto: HierarchyNodeDto,
        leakIds: Set<String>,
    ): CoroutineRow {
        val ageMs =
            if (dto.createdAtNanos > 0) {
                (System.nanoTime() - dto.createdAtNanos).coerceAtLeast(0) / NANOS_PER_MILLI
            } else {
                0
            }
        return CoroutineRow(
            id = dto.id,
            name = dto.name,
            state = dto.state,
            dispatcherName = dto.dispatcherName,
            ageMs = ageMs,
            childCount = dto.children.size,
            isLeak = dto.id in leakIds,
        )
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
