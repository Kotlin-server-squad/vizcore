package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import java.util.TreeMap

/** Read access to a session's summaries, for the derivation and the backend. */
interface SummaryView {
    fun get(id: String): CoroutineSummary?

    /** All tracked summaries (full and stubs), in first-observation order. */
    val all: Collection<CoroutineSummary>

    /** Coroutines created after the tracked capacity was reached (only counted). */
    val untrackedCount: Long

    /** Sequence of the last event applied (any kind, including watermarks), 0 before any. */
    val lastEventSeq: Long

    /**
     * Greatest app-clock time of any event applied, `null` before any. A max, not the last one: appends are
     * not in timestamp order. Attached sessions receive events only through ingest, so all carry the app clock.
     */
    val latestEventAppNanos: Long?
}

/**
 * The per-session summary table (spec C-2): one [CoroutineSummary] per tracked coroutine, updated on
 * every event by [apply] and never rebuilt from retained events. It also records container promotion
 * ([CoroutineSummary.promotedAtSeq]: set the first time a running genuine root qualifies; cleared when
 * that root finishes unless it got a new direct child after promotion), enforces [trackedCapacity], and
 * reduces finished summaries to stubs beyond
 * [fullSummaryTarget] (finished non-failed first, then failed, oldest first), checked on every creation
 * and every terminal event.
 *
 * Not thread-safe: the owner serializes [apply] and reads (VizSession does both under its send lock).
 */
class SummaryTable(
    private val fullSummaryTarget: Int = HealthThresholds.DEFAULT_FULL_SUMMARY_TARGET,
    private val trackedCapacity: Int = HealthThresholds.DEFAULT_TRACKED_CAPACITY,
) : SummaryView {
    private val summaries = LinkedHashMap<String, CoroutineSummary>()
    private val subtrees = SubtreeIndex()

    // Finished summaries eligible for reduction, keyed by first-observation seq so the oldest goes first.
    private val finishedOk = TreeMap<Long, String>()
    private val finishedFailed = TreeMap<Long, String>()

    /** Summaries that are not stubs. */
    var fullSummaryCount: Int = 0
        private set

    override var untrackedCount: Long = 0L
        private set

    override var lastEventSeq: Long = 0L
        private set

    override var latestEventAppNanos: Long? = null
        private set

    override fun get(id: String): CoroutineSummary? = summaries[id]

    override val all: Collection<CoroutineSummary> get() = summaries.values

    fun apply(event: VizEvent) {
        lastEventSeq = event.seq
        latestEventAppNanos = maxOf(latestEventAppNanos ?: Long.MIN_VALUE, event.tsNanos)
        if (event !is CoroutineEvent) return
        if (event is CoroutineCreated) onCreated(event) else onUpdate(event)
    }

    private fun onCreated(e: CoroutineCreated) {
        if (e.coroutineId in summaries) return
        // Growth counts even for a child beyond capacity: the root is still a container.
        val parent = e.parentCoroutineId?.let { summaries[it] }
        if (parent != null && parent.promotedAtSeq != null && !parent.grewAfterPromotion) {
            summaries[parent.id] = parent.copy(grewAfterPromotion = true, lastModifiedSeq = e.seq)
        }
        if (summaries.size >= trackedCapacity) {
            untrackedCount++
            return
        }
        summaries[e.coroutineId] = SummaryUpdater.created(e)
        fullSummaryCount++
        subtrees.add(e.coroutineId, e.parentCoroutineId)
        promoteIfQualified(subtrees.rootOf(e.coroutineId), e.seq)
        reduceOverTarget(e.seq)
    }

    private fun onUpdate(event: CoroutineEvent) {
        val current = summaries[event.coroutineId] ?: return
        val next = SummaryUpdater.update(current, event)
        if (next !== current) {
            summaries[event.coroutineId] = next
            if (!current.isTerminal && next.isTerminal) onTerminal(next, event.seq)
        }
    }

    private fun onTerminal(
        s: CoroutineSummary,
        seq: Long,
    ) {
        // A finished root that never grew after promotion was a request, not a container (spec amendment).
        if (s.parentId == null && s.promotedAtSeq != null && !s.grewAfterPromotion) {
            summaries[s.id] = s.copy(promotedAtSeq = null, lastModifiedSeq = seq)
        }
        subtrees.close(s.id)
        (if (s.failed) finishedFailed else finishedOk)[s.firstObserved.seq] = s.id
        promoteIfQualified(subtrees.rootOf(s.id), seq)
        reduceOverTarget(seq)
    }

    private fun promoteIfQualified(
        rootId: String,
        seq: Long,
    ) {
        val root = summaries[rootId] ?: return
        if (root.parentId != null || root.isTerminal || root.promotedAtSeq != null) return
        if (subtrees.qualifiesAsContainer(rootId)) {
            summaries[rootId] = root.copy(promotedAtSeq = seq, lastModifiedSeq = seq)
        }
    }

    /** Reduction changes fields, so the stub's lastModifiedSeq becomes the triggering event's [seq] (C-2). */
    private fun reduceOverTarget(seq: Long) {
        while (fullSummaryCount > fullSummaryTarget) {
            val id = (finishedOk.pollFirstEntry() ?: finishedFailed.pollFirstEntry())?.value ?: return
            summaries[id] = summaries.getValue(id).toStub().copy(lastModifiedSeq = seq)
            fullSummaryCount--
        }
    }
}
