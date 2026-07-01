package com.jh.coroutinevisualizer.toolwindow

import com.jh.coroutinevisualizer.api.HierarchyNodeDto

/** A positioned node in the parent-child graph. Coordinates are the top-left corner in pixels. */
data class GraphNode(
    val id: String,
    val name: String,
    val state: String,
    val isLeak: Boolean,
    val x: Int,
    val y: Int,
)

/** A directed parent -> child edge between two positioned nodes. */
data class GraphEdge(
    val fromId: String,
    val toId: String,
)

/** The full positioned graph plus the canvas size needed to paint it. */
data class GraphModel(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val width: Int,
    val height: Int,
)

/**
 * Pure, layered top-down layout of the parent->child coroutine forest. No Swing here so the geometry
 * stays unit-testable. v1 uses a simple grid-by-level: depth drives the row (y), a stable pre-order
 * DFS assigns a global column index (x). Parents always sit above their children.
 */
object GraphLayout {
    const val NODE_W = 140
    const val NODE_H = 28
    const val COL_GAP = 160
    const val ROW_GAP = 70
    const val MARGIN = 20

    fun compute(
        hierarchy: List<HierarchyNodeDto>,
        leakIds: Set<String>,
    ): GraphModel {
        if (hierarchy.isEmpty()) {
            return GraphModel(emptyList(), emptyList(), 0, 0)
        }

        val byId = hierarchy.associateBy { it.id }
        val roots =
            hierarchy.filter { node ->
                node.parentId == null || node.parentId !in byId
            }

        val depthById = HashMap<String, Int>()
        val orderById = HashMap<String, Int>()
        var nextColumn = 0

        // Stable pre-order DFS from each root: assigns depth (row) and a global column index.
        fun visit(
            node: HierarchyNodeDto,
            depth: Int,
        ) {
            if (node.id in depthById) return
            depthById[node.id] = depth
            orderById[node.id] = nextColumn++
            for (childId in node.children) {
                val child = byId[childId] ?: continue
                visit(child, depth + 1)
            }
        }
        for (root in roots) {
            visit(root, 0)
        }

        val nodes =
            hierarchy.mapNotNull { node ->
                val depth = depthById[node.id] ?: return@mapNotNull null
                val order = orderById[node.id] ?: return@mapNotNull null
                GraphNode(
                    id = node.id,
                    name = node.name,
                    state = node.state,
                    isLeak = node.id in leakIds,
                    x = MARGIN + order * COL_GAP,
                    y = MARGIN + depth * ROW_GAP,
                )
            }

        val edges =
            hierarchy.mapNotNull { node ->
                val parentId = node.parentId
                if (parentId != null && parentId in byId && node.id in depthById) {
                    GraphEdge(fromId = parentId, toId = node.id)
                } else {
                    null
                }
            }

        val maxX = nodes.maxOfOrNull { it.x } ?: 0
        val maxY = nodes.maxOfOrNull { it.y } ?: 0
        return GraphModel(
            nodes = nodes,
            edges = edges,
            width = maxX + NODE_W + MARGIN,
            height = maxY + NODE_H + MARGIN,
        )
    }
}
