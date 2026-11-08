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

    /**
     * Creation events received after the tracked capacity was reached (only counted). This equals the number of
     * coroutines beyond capacity because every producer emits one `CoroutineCreated` per coroutine. A repeated
     * `CoroutineCreated` for an untracked id (e.g. a re-delivered event) counts again and may overcount; that is
     * accepted, as deduplicating would mean remembering every untracked id, and the value is only read as "at least".
     */
    val untrackedCount: Long

    /** Sequence of the last event applied (any kind, including watermarks), 0 before any. */
    val lastEventSeq: Long

    /**
     * Greatest app-clock time of any event applied, `null` before any. A max, not the last one: appends are
     * not in timestamp order. Valid only while every applied event carries the app clock: attached sessions
     * receive events only through ingest, so they do. Backend-generated monitor events must not be applied to
     * an attached session's table.
     */
    val latestEventAppNanos: Long?

    /**
     * Sequence of the first event applied, 0 before any. A table that saw every event of its session (the in-memory
     * table of a live session) tells a trimmed store apart from one that never held more.
     */
    val firstEventSeq: Long

    /** Smallest app-clock time of any event applied, `null` before any: the capture's start (spec C-3 `captureStartAppNanos`). */
    val earliestEventAppNanos: Long?
}

/**
 * The per-session summary table (spec C-2): one [CoroutineSummary] per tracked coroutine, updated on
 * every event by [apply] and never rebuilt from retained events. It also records container promotion
 * ([CoroutineSummary.promotedAtSeq]: set the first time a running genuine root qualifies; when that root
 * finishes it is cleared unless the root got a new direct child after promotion and was cancelled), enforces
 * [trackedCapacity], and reduces finished summaries to stubs beyond [fullSummaryTarget] (finished non-failed
 * first, then failed, oldest first), checked on every creation and every terminal event.
 *
 * Once a coroutine was beyond capacity the table stays closed: a table restored under a larger capacity (see [restore])
 * does not start tracking again, so it still covers the first coroutines of the session and nothing after a gap.
 *
 * Not thread-safe: the owner serializes [apply] and reads (VizSession does both under its send lock).
 */
class SummaryTable(
    private val fullSummaryTarget: Int = HealthThresholds.DEFAULT_FULL_SUMMARY_TARGET,
    private val trackedCapacity: Int = HealthThresholds.DEFAULT_TRACKED_CAPACITY,
) : SummaryView {
    constructor(limits: SummaryLimits) : this(limits.fullSummaryTarget, limits.trackedCapacity)

    private val summaries = LinkedHashMap<String, CoroutineSummary>()

    // Replay only (see [seed]): admits exactly the coroutines the durable table tracks, instead of the capacity rule, so
    // a replay agrees with the live table's decisions even when the limits changed since (a restore under new limits).
    private var admitted: ((String) -> Boolean)? = null
    private val subtrees = SubtreeIndex()

    // Finished summaries eligible for reduction, keyed by a per-table creation counter (first-observation
    // order, the LinkedHashMap order) so the oldest goes first. Event seqs are not unique keys: the DB has no
    // unique (session_id, seq), so a stored log can repeat one.
    private val finishedOk = TreeMap<Long, String>()
    private val finishedFailed = TreeMap<Long, String>()
    private val creationOrder = HashMap<String, Long>()
    private var nextCreationOrder = 0L

    /** Summaries that are not stubs. */
    internal var fullSummaryCount: Int = 0
        private set

    override var untrackedCount: Long = 0L
        private set

    override var lastEventSeq: Long = 0L
        private set

    override var latestEventAppNanos: Long? = null
        private set

    override var firstEventSeq: Long = 0L
        private set

    override var earliestEventAppNanos: Long? = null
        private set

    override fun get(id: String): CoroutineSummary? = summaries[id]

    override val all: Collection<CoroutineSummary> get() = summaries.values

    fun apply(event: VizEvent) {
        observe(event.seq, event.tsNanos)
        if (event !is CoroutineEvent) return
        if (event is CoroutineCreated) onCreated(event) else onUpdate(event)
    }

    private fun observe(
        seq: Long,
        appNanos: Long,
    ) {
        if (firstEventSeq == 0L) firstEventSeq = seq
        lastEventSeq = seq
        latestEventAppNanos = maxOf(latestEventAppNanos ?: Long.MIN_VALUE, appNanos)
        earliestEventAppNanos = minOf(earliestEventAppNanos ?: Long.MAX_VALUE, appNanos)
    }

    private fun onCreated(e: CoroutineCreated) = track(e.coroutineId, e.parentCoroutineId, e.seq) { SummaryUpdater.created(e) }

    private fun track(
        id: String,
        parentId: String?,
        seq: Long,
        create: () -> CoroutineSummary,
    ) {
        if (id in summaries) return
        // Growth counts even for a child beyond capacity: the root is still a container.
        val parent = parentId?.let { summaries[it] }
        if (parent != null && parent.promotedAtSeq != null && !parent.grewAfterPromotion) {
            summaries[parent.id] = parent.copy(grewAfterPromotion = true, lastModifiedSeq = seq)
        }
        val admit = admitted?.invoke(id) ?: (untrackedCount == 0L && summaries.size < trackedCapacity)
        if (!admit) {
            untrackedCount++
            // A table restored under a smaller target may be over it with nothing running: reduce on this creation too.
            reduceOverTarget(seq)
            return
        }
        summaries[id] = create()
        creationOrder[id] = nextCreationOrder++
        fullSummaryCount++
        subtrees.add(id, parentId)
        promoteIfQualified(subtrees.rootOf(id), seq)
        reduceOverTarget(seq)
    }

    /**
     * Replay seed (spec "Modes", Replay; used by [SummaryFold]): every coroutine of [durable] first observed before
     * [retainedFrom] (its creation event was trimmed) and at or before [atSeq] is created again from its summary, and
     * finished at its terminal transition when that is in the same range, all in seq order through the same bookkeeping
     * as [apply]. Promotion and round anchoring depend only on creations and terminals, so they are those of the cursor.
     * Nothing in between is known, so nothing is invented: a seeded coroutine reads as created until a retained event
     * says more. From then on the table admits exactly the coroutines [durable] tracks (it saw every creation, so a coroutine
     * created by then is tracked there if and only if the live table admitted it), whatever this table's limits are.
     * Call it on an empty table, before any [apply].
     */
    internal fun seed(
        durable: SummaryView,
        retainedFrom: Long,
        atSeq: Long,
    ) {
        check(lastEventSeq == 0L && summaries.isEmpty()) { "seed an empty table only" }
        admitted = { id -> durable.get(id) != null }
        val trimmed = { seq: Long -> seq < retainedFrom && seq <= atSeq }
        val steps = ArrayList<SeedStep>()
        for (s in durable.all) {
            if (!trimmed(s.firstObserved.seq)) continue
            steps += SeedStep(s.firstObserved, s, creation = true)
            s.terminal?.takeIf { trimmed(it.seq) }?.let { steps += SeedStep(it, s, creation = false) }
        }
        steps.sortBy { it.at.seq }
        for (step in steps) {
            val s = step.summary
            observe(step.at.seq, step.at.appNanos)
            if (step.creation) {
                track(s.id, s.parentId, step.at.seq) { SummaryUpdater.createdFrom(s) }
            } else {
                val current = summaries[s.id] ?: continue
                val next = SummaryUpdater.finishedAs(current, s)
                summaries[s.id] = next
                onTerminal(next, step.at.seq)
            }
        }
    }

    /**
     * Loads [state] into this empty table: the summaries in their order, the subtree index from their parents (added in
     * first-observation order, then closed for the finished ones, which yields the original counts), and the creation
     * order and reduction queues from the full (non-stub) summaries, so promotion and reduction go on as before.
     */
    private fun load(state: SummaryTableState) {
        check(summaries.isEmpty() && lastEventSeq == 0L) { "restore into an empty table only" }
        require(state.version == 1) { "unknown summary state version ${state.version}" }
        for (s in state.summaries) {
            summaries[s.id] = s
            subtrees.add(s.id, s.parentId)
            if (!s.reduced) {
                creationOrder[s.id] = nextCreationOrder++
                fullSummaryCount++
            }
        }
        for (s in state.summaries) {
            if (!s.isTerminal) continue
            subtrees.close(s.id)
            if (!s.reduced) (if (s.failed) finishedFailed else finishedOk)[creationOrder.getValue(s.id)] = s.id
        }
        untrackedCount = state.untrackedCount
        lastEventSeq = state.lastEventSeq
        latestEventAppNanos = state.latestEventAppNanos
        firstEventSeq = state.firstEventSeq
        earliestEventAppNanos = state.earliestEventAppNanos
    }

    companion object {
        /** The table [state] was taken from, ready for more events (spec C-4). Use the original's limits. */
        fun restore(
            state: SummaryTableState,
            fullSummaryTarget: Int = HealthThresholds.DEFAULT_FULL_SUMMARY_TARGET,
            trackedCapacity: Int = HealthThresholds.DEFAULT_TRACKED_CAPACITY,
        ): SummaryTable = SummaryTable(fullSummaryTarget, trackedCapacity).also { it.load(state) }

        /**
         * The table [state] was taken from, going on with [limits], which may differ from the ones it was taken under (a
         * restart with new settings). Restoring drops nothing and changes nothing by itself. Under a smaller capacity every
         * summary and stub stays and no new coroutine is tracked; finished summaries above a smaller target are reduced to
         * stubs at the next creation or terminal event, as usual. Under a larger capacity a table that had room tracks up to
         * the new capacity, and one that had already overflowed stays closed (see the class).
         */
        fun restore(
            state: SummaryTableState,
            limits: SummaryLimits,
        ): SummaryTable = restore(state, limits.fullSummaryTarget, limits.trackedCapacity)
    }

    private class SeedStep(
        val at: Transition,
        val summary: CoroutineSummary,
        val creation: Boolean,
    )

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
        // A finished root stays a container only if it grew after promotion and was cancelled (server shutdown);
        // otherwise it was a request, which must not stay split (spec amendment).
        // Known edge: a lone child created after promotion but first observed after this terminal event (possible
        // within the final poll) finds the promotion already cleared, so the root's rounds merge; accepted as rare.
        if (s.parentId == null && s.promotedAtSeq != null && !(s.grewAfterPromotion && s.cancelled && !s.failed)) {
            summaries[s.id] = s.copy(promotedAtSeq = null, lastModifiedSeq = seq)
        }
        subtrees.close(s.id)
        val queue = if (s.failed) finishedFailed else finishedOk
        queue[creationOrder.getValue(s.id)] = s.id
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
            creationOrder.remove(id)
            summaries[id] = summaries.getValue(id).toStub().copy(lastModifiedSeq = seq)
            fullSummaryCount--
        }
    }
}
