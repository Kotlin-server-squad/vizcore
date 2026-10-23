package com.jh.proj.coroutineviz.health

/**
 * Incremental structure for container-root promotion. For each tracked node it keeps how many nodes in
 * its subtree are still open, and for each parent how many of its direct-child subtrees are fully
 * closed. [add] and [close] cost O(depth). A child created before its parent (the DebugProbes poll gives
 * no parent-first order) waits in [pendingChildren] and is linked when the parent arrives; until then, and
 * forever if the parent never arrives, it is its own root. Malformed ancestry (a self-parent, or a pending
 * child that is already an ancestor of the arriving parent) is never linked, so walks always terminate,
 * which matters because they run under the session's send lock.
 */
internal class SubtreeIndex {
    private val parentOf = HashMap<String, String>()
    private val directChildren = HashMap<String, Int>()
    private val openInSubtree = HashMap<String, Int>()
    private val closedChildSubtrees = HashMap<String, Int>()
    private val closed = HashSet<String>()
    private val pendingChildren = HashMap<String, MutableList<String>>()

    fun add(
        id: String,
        parentId: String?,
    ) {
        if (id in openInSubtree) return
        openInSubtree[id] = 0
        if (parentId != null && parentId != id) {
            if (parentId in openInSubtree) link(id, parentId) else pendingChildren.getOrPut(parentId) { mutableListOf() } += id
        }
        adjust(id, +1)
        val idRoot = rootOf(id)
        pendingChildren.remove(id)?.forEach { child -> if (child != idRoot) adopt(child, id) }
    }

    fun close(id: String) {
        if (id in openInSubtree && closed.add(id)) adjust(id, -1)
    }

    fun rootOf(id: String): String {
        var node = id
        while (true) {
            node = parentOf[node] ?: return node
        }
    }

    /** Child rule only: at least 3 direct children, at least half of them fully finished; the caller checks root-ness and liveness. */
    fun qualifiesAsContainer(id: String): Boolean {
        val children = directChildren[id] ?: 0
        val closed = closedChildSubtrees[id] ?: 0
        return children >= HealthThresholds.CONTAINER_MIN_DIRECT_CHILDREN && closed * 2 >= children
    }

    private fun link(
        child: String,
        parent: String,
    ) {
        parentOf[child] = parent
        directChildren.merge(parent, 1, Int::plus)
        // The new child's subtree starts empty (closed); adjust() in add() opens it.
        closedChildSubtrees.merge(parent, 1, Int::plus)
    }

    /** Links a child that was created before [parent] and carries its open count up the new chain. */
    private fun adopt(
        child: String,
        parent: String,
    ) {
        parentOf[child] = parent
        directChildren.merge(parent, 1, Int::plus)
        val open = openInSubtree.getValue(child)
        if (open == 0) closedChildSubtrees.merge(parent, 1, Int::plus) else adjust(parent, open)
    }

    private fun adjust(
        start: String,
        delta: Int,
    ) {
        var node: String? = start
        while (node != null) {
            val before = openInSubtree.getValue(node)
            val after = before + delta
            openInSubtree[node] = after
            val parent = parentOf[node]
            if (parent != null) {
                if (before == 0 && after > 0) closedChildSubtrees.merge(parent, -1, Int::plus)
                if (before > 0 && after == 0) closedChildSubtrees.merge(parent, 1, Int::plus)
            }
            node = parent
        }
    }
}
