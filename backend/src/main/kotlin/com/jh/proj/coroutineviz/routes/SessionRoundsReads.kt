package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.health.Capped
import com.jh.proj.coroutineviz.health.CoroutineSummary
import com.jh.proj.coroutineviz.health.IncidentKind
import com.jh.proj.coroutineviz.models.CoroutineState
import io.ktor.http.Parameters
import kotlinx.serialization.Serializable

/** One round of `GET …/rounds` (spec C-3, plan E-8): core's round facts plus whether it holds a problem. */
@Serializable
data class RoundEntry(
    val roundId: String,
    val anchorId: String,
    val anchorName: String?,
    val anchorOrigin: CoroutineOrigin?,
    val anchorCreatedNanos: Long,
    val anchorCreatedSeq: Long,
    val inProgress: Boolean,
    val countsByState: Map<CoroutineState, Int>,
    val memberCount: Int,
    val problem: Boolean,
    val problemKinds: List<IncidentKind>,
    /** With `q`: the matching members (coroutine ids, newest first), else null. */
    val matches: Capped<String>? = null,
    /** Replay only: members whose state at the cursor is not known (plan E-3); their counted state is a guess. */
    val unknownMembers: Int = 0,
)

/** A page of rounds, problem rounds first, then newest anchor first (plan E-8, E-9). */
@Serializable
data class RoundsPage(
    val asOfSeq: Long,
    val total: Int,
    val items: List<RoundEntry>,
    val nextCursor: String?,
    /** 0 outside replay; in replay, how many coroutines' state at the cursor is not known. */
    val unknownAtSeq: Int,
    /** With `q`: matching coroutines that belong to no round (their ancestry reaches an untracked parent), else null. */
    val ungroupedMatches: Capped<String>? = null,
    /** App-clock time of the observation this page's problem marks are about (health `asOf`, spec H-2); null before any. */
    val asOfAppNanos: Long? = null,
)

internal const val MAX_QUERY_LENGTH = 200

/**
 * `GET /api/sessions/{id}/rounds?cursor=&limit=&q=[&problemsOnly=true][&includeVizcore=true]` with the view's binding:
 * every round of [view], filtered and paged. `q` is the server-side "find in all rounds" (spec T-4, S-12): a
 * case-insensitive substring of a member's name, creation function or creation file.
 */
internal fun listRounds(
    view: SessionView,
    params: Parameters,
): RoundsPage {
    view.requireNoUnknown()
    val query = findQuery(params)
    val problemsOnly = params["problemsOnly"] == "true"
    val includeVizcore = params["includeVizcore"] == "true"
    val limit = pageLimit(params)
    val derived = view.derive()
    val kindsByAnchor = derived.problemRounds.associate { it.anchorId to it.kinds }
    val summaries = view.input.summaries
    val membersByAnchor = summaries.all.groupBy { derived.anchoring.anchorOf(it.id) }
    // Find leaves vizcore's own coroutines out unless asked, before it counts and caps the matches (spec "Origin").
    val findable = { m: CoroutineSummary -> includeVizcore || derived.originOf(m) != CoroutineOrigin.VIZCORE }
    val entries =
        derived.anchoring
            .rounds()
            .mapNotNull { r ->
                val kinds = kindsByAnchor[r.anchorId].orEmpty()
                val anchor = summaries.get(r.anchorId)
                val vizcoreAnchor = anchor != null && derived.originOf(anchor) == CoroutineOrigin.VIZCORE
                val members = membersByAnchor[r.anchorId].orEmpty()
                val matches = query?.let { q -> matching(members, q, findable) }
                when {
                    problemsOnly && kinds.isEmpty() -> null
                    !includeVizcore && vizcoreAnchor && kinds.isEmpty() -> null
                    matches != null && matches.isEmpty() -> null
                    else ->
                        RoundEntry(
                            roundId = r.roundId,
                            anchorId = r.anchorId,
                            anchorName = r.anchorName,
                            anchorOrigin = r.anchorOrigin,
                            anchorCreatedNanos = r.anchorCreatedNanos,
                            anchorCreatedSeq = r.anchorCreatedSeq,
                            inProgress = r.inProgress,
                            countsByState = r.countsByState,
                            memberCount = r.memberCount,
                            problem = kinds.isNotEmpty(),
                            problemKinds = kinds,
                            matches = matches?.let { m -> Capped.of(m.map { it.id }) },
                            unknownMembers = members.count { it.id in view.bound.unknownIds },
                        )
                }
            }.sortedWith(compareBy(::roundKey))
    val page = Keyset.page(entries, ::roundKey, params["cursor"], limit)
    val ungrouped = query?.let { q -> matching(membersByAnchor[null].orEmpty(), q, findable) }
    return RoundsPage(
        view.seq,
        entries.size,
        page.items,
        page.nextCursor,
        view.unknownAtSeq,
        ungrouped?.let { m ->
            Capped.of(m.map { it.id })
        },
        // The derivation's own observation time (H-2), the one its problem marks are about.
        derived.health.asOf?.appNanos,
    )
}

/** `q`, trimmed and lower-cased; null when absent or blank, 400 when too long. */
private fun findQuery(params: Parameters): String? {
    val needle = params["q"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (needle.length > MAX_QUERY_LENGTH) badParameter("q is longer than $MAX_QUERY_LENGTH characters")
    return needle.lowercase()
}

/** The [members] find returns for [needle], newest first, leaving out what [findable] rejects. */
private fun matching(
    members: List<CoroutineSummary>,
    needle: String,
    findable: (CoroutineSummary) -> Boolean,
): List<CoroutineSummary> = members.filter { it.matches(needle) && findable(it) }.sortedWith(NEWEST_FIRST_OBSERVED)

/** The page key of a round: problem rounds first, newest anchor first, then anchor id (plan E-8, E-9). */
internal fun roundKey(r: RoundEntry) = Keyset.Key(if (r.problem) 0 else 1, r.anchorCreatedNanos, r.anchorCreatedSeq, r.anchorId)

private fun CoroutineSummary.matches(needle: String): Boolean =
    listOfNotNull(name, creationLocation?.function, creationLocation?.fileName).any { it.lowercase().contains(needle) }

private val NEWEST_FIRST_OBSERVED = compareByDescending<CoroutineSummary> { it.firstObserved.seq }.thenBy { it.id }
