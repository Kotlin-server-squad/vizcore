package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.models.CoroutineState
import kotlinx.serialization.Serializable

/** One round (spec "Rounds"): an anchor and its subtree. */
@Serializable
data class RoundSummary(
    val roundId: String,
    val anchorId: String,
    val anchorName: String?,
    val anchorOrigin: CoroutineOrigin?,
    val anchorCreatedNanos: Long,
    val anchorCreatedSeq: Long,
    val inProgress: Boolean,
    val countsByState: Map<CoroutineState, Int>,
    val memberCount: Int,
)

/**
 * Round anchoring moved from the plugin's RoundGrouping (spec "Rounds"): anchors are every genuine root
 * plus every direct child of a promoted container root; a container is a singleton round. Round ids are
 * derived, never stored. A coroutine whose parent chain reaches an untracked parent has no round
 * ([anchorOf] returns `null`).
 */
class RoundAnchoring private constructor(
    private val view: SummaryView,
    val anchorIds: Set<String>,
) {
    private val resolved = HashMap<String, String>()
    private val unresolvable = HashSet<String>()

    fun roundIdOf(id: String): String? = anchorOf(id)?.let(::roundId)

    fun anchorOf(id: String): String? {
        val chain = ArrayList<String>()
        val limit = view.all.size + 1
        var current: String? = id
        var answer: String? = null
        while (current != null && answer == null && chain.size <= limit) {
            val node: String = current
            when {
                node in unresolvable -> current = null
                node in resolved -> answer = resolved[node]
                else -> {
                    chain += node
                    if (node in anchorIds) answer = node else current = trackedParentOf(node)
                }
            }
        }
        val result = answer
        if (result == null) unresolvable += chain else chain.forEach { resolved[it] = result }
        return result
    }

    private fun trackedParentOf(id: String): String? = view.get(id)?.parentId?.takeIf { view.get(it) != null }

    /** Every round, newest anchor first. */
    fun rounds(): List<RoundSummary> {
        val members = HashMap<String, MutableList<CoroutineSummary>>()
        for (s in view.all) {
            val anchor = anchorOf(s.id) ?: continue
            members.getOrPut(anchor) { mutableListOf() } += s
        }
        return anchorIds
            .mapNotNull { anchorId ->
                val anchor = view.get(anchorId) ?: return@mapNotNull null
                val roundMembers = members[anchorId].orEmpty()
                RoundSummary(
                    roundId = roundId(anchorId),
                    anchorId = anchorId,
                    anchorName = anchor.name,
                    anchorOrigin = anchor.origin,
                    anchorCreatedNanos = anchor.firstObserved.appNanos,
                    anchorCreatedSeq = anchor.firstObserved.seq,
                    inProgress = roundMembers.any { !it.isTerminal },
                    countsByState = roundMembers.groupingBy { it.state }.eachCount(),
                    memberCount = roundMembers.size,
                )
            }.sortedWith(compareByDescending<RoundSummary> { it.anchorCreatedNanos }.thenByDescending { it.anchorCreatedSeq })
    }

    companion object {
        fun roundId(anchorId: String): String = "round:$anchorId"

        fun of(view: SummaryView): RoundAnchoring {
            val anchors = HashSet<String>()
            val containers = HashSet<String>()
            for (s in view.all) {
                if (s.parentId == null) {
                    anchors += s.id
                    if (s.promotedAtSeq != null) containers += s.id
                }
            }
            for (s in view.all) {
                val parent = s.parentId
                if (parent != null && parent in containers) anchors += s.id
            }
            return RoundAnchoring(view, anchors)
        }
    }
}
