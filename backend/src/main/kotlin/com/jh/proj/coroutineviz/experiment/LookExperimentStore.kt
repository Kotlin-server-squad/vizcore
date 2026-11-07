package com.jh.proj.coroutineviz.experiment

import org.slf4j.LoggerFactory

/** How a browser came to its first look: given one at random, or it had (or was linked to) one already. */
enum class EntryKind { ASSIGNED, CHOSE }

/** One anonymous measurement from a browser; ids and look names are validated before they get here. */
sealed interface LookEvent {
    data class Entry(
        val look: String,
        val kind: EntryKind,
    ) : LookEvent

    /** A session page was shown in [look]. */
    data class Seen(
        val look: String,
    ) : LookEvent

    /** [ms] of visible time on the session page in [look]. */
    data class Time(
        val look: String,
        val ms: Long,
    ) : LookEvent

    /** The person changed the look in the menu. */
    data class Switch(
        val from: String,
        val to: String,
    ) : LookEvent
}

/** A browser taking part. [entryLook] and [entryKind] are null until its entry arrives. */
data class VisitorRow(
    val anonId: String,
    val entryLook: String?,
    val entryKind: EntryKind?,
    val firstSeenAt: Long,
)

data class ExposureRow(
    val anonId: String,
    val look: String,
    val sessionsSeen: Int,
    val visibleMs: Long,
)

data class SwitchRow(
    val anonId: String,
    val from: String,
    val to: String,
    val times: Int,
)

data class VoteRow(
    val anonId: String,
    val look: String,
    val rating: Int,
    val comment: String?,
    val votedAt: Long,
)

/** Everything recorded, for [LookResults]. */
data class ExperimentData(
    val visitors: List<VisitorRow> = emptyList(),
    val exposures: List<ExposureRow> = emptyList(),
    val switches: List<SwitchRow> = emptyList(),
    val votes: List<VoteRow> = emptyList(),
)

/**
 * Where the look experiment keeps its measurements: the database when the server has one
 * ([ExposedLookExperimentStore]), else memory ([InMemoryLookExperimentStore]).
 *
 * Both keep at most `maxVisitors` browsers; anything from a new browser beyond that is dropped (the
 * methods return false), so random ids cannot grow the store without end. Everything else is bounded by
 * that: per browser at most one row per look, per look pair and per look's vote.
 */
interface LookExperimentStore {
    /** Records [events] for [anonId]; its first entry wins. False when the browser is new and the store full. */
    fun record(
        anonId: String,
        events: List<LookEvent>,
        at: Long,
    ): Boolean

    /** Puts [anonId]'s vote for [look], replacing an earlier one. False when the browser is new and the store full. */
    fun putVote(
        anonId: String,
        look: String,
        rating: Int,
        comment: String?,
        at: Long,
    ): Boolean

    fun snapshot(): ExperimentData

    companion object {
        const val DEFAULT_MAX_VISITORS = 50_000
    }
}

/** The look experiment kept in memory (no database): lost on restart. */
class InMemoryLookExperimentStore(
    private val maxVisitors: Int = LookExperimentStore.DEFAULT_MAX_VISITORS,
) : LookExperimentStore {
    private val lock = Any()
    private val visitors = LinkedHashMap<String, VisitorRow>()
    private val exposures = LinkedHashMap<Pair<String, String>, ExposureRow>()
    private val switches = LinkedHashMap<Triple<String, String, String>, SwitchRow>()
    private val votes = LinkedHashMap<Pair<String, String>, VoteRow>()
    private val full = FullWarning()

    override fun record(
        anonId: String,
        events: List<LookEvent>,
        at: Long,
    ): Boolean =
        synchronized(lock) {
            if (!admit(anonId, at)) return false
            events.forEach { event ->
                when (event) {
                    is LookEvent.Entry -> {
                        val visitor = visitors.getValue(anonId)
                        if (visitor.entryKind == null) visitors[anonId] = visitor.copy(entryLook = event.look, entryKind = event.kind)
                    }
                    is LookEvent.Seen -> exposure(anonId, event.look) { it.copy(sessionsSeen = it.sessionsSeen + 1) }
                    is LookEvent.Time -> exposure(anonId, event.look) { it.copy(visibleMs = it.visibleMs + event.ms) }
                    is LookEvent.Switch -> {
                        val key = Triple(anonId, event.from, event.to)
                        val row = switches[key] ?: SwitchRow(anonId, event.from, event.to, 0)
                        switches[key] = row.copy(times = row.times + 1)
                    }
                }
            }
            true
        }

    override fun putVote(
        anonId: String,
        look: String,
        rating: Int,
        comment: String?,
        at: Long,
    ): Boolean =
        synchronized(lock) {
            if (!admit(anonId, at)) return false
            votes[anonId to look] = VoteRow(anonId, look, rating, comment, at)
            true
        }

    override fun snapshot(): ExperimentData =
        synchronized(lock) {
            ExperimentData(visitors.values.toList(), exposures.values.toList(), switches.values.toList(), votes.values.toList())
        }

    private fun admit(
        anonId: String,
        at: Long,
    ): Boolean {
        val room = anonId in visitors || visitors.size < maxVisitors
        if (room) visitors.putIfAbsent(anonId, VisitorRow(anonId, null, null, at)) else full.once(maxVisitors)
        return room
    }

    private fun exposure(
        anonId: String,
        look: String,
        change: (ExposureRow) -> ExposureRow,
    ) {
        val key = anonId to look
        exposures[key] = change(exposures[key] ?: ExposureRow(anonId, look, 0, 0))
    }
}

/** Logs once that the store is full, not once per dropped request. */
internal class FullWarning {
    @Volatile private var warned = false

    fun once(maxVisitors: Int) {
        if (warned) return
        warned = true
        LoggerFactory
            .getLogger(LookExperimentStore::class.java)
            .warn("Look experiment: {} browsers recorded, the most it keeps; new browsers are no longer counted", maxVisitors)
    }
}
