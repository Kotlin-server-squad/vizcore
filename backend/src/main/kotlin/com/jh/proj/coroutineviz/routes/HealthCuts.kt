package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.health.RetentionInfo
import com.jh.proj.coroutineviz.health.SummaryView
import com.jh.proj.coroutineviz.health.Transition

/**
 * The exact inputs one health response used (spec C-3 `asOfSeq`): a detached copy of the summaries, the ingest facts the
 * derivation saw ([ingest]'s transport is the one it reported), the retention facts, and the retained transitions the
 * derivation read (possibly blocked evidence), at [seq].
 */
internal class HealthCut(
    val sessionId: String,
    val seq: Long,
    val summaries: SummaryView,
    val ingest: IngestSnapshot,
    val retention: RetentionInfo,
    val transitions: Map<String, List<Transition>>,
) {
    val watermarkNanos: Long? get() = ingest.watermark?.appNanos
    val transportRevision: Long get() = ingest.transport.revision

    /** Same binding: a follow-up naming one names the other (plan E-17). */
    fun sameBinding(other: HealthCut): Boolean =
        seq == other.seq && watermarkNanos == other.watermarkNanos && transportRevision == other.transportRevision

    /** Same binding and same evidence: one may stand for the other. */
    fun equivalent(other: HealthCut): Boolean = sameBinding(other) && retention == other.retention && transitions == other.transitions
}

/**
 * Health cuts kept briefly so the follow-up reads bound to a health (`asOfSeq`, `asOfWatermark`, `asOfTransport`) answer
 * from exactly what that health saw (plan E-17): exact by construction, so a busy app's view makes progress while events
 * keep arriving. Up to [PER_SESSION] per session, least recently used dropped first; a hit refreshes a cut's [TTL_MS];
 * [MAX_TOTAL] in all. Only a cut that is gone (expired, dropped for space, kept by another backend process) leaves a read
 * to rebuild the past from retained events, which may answer `STALE`. Process-local, like [IngestStates]; a summary copy
 * shares its (immutable) summaries with the session, so a cut costs one map per kept response.
 */
internal object HealthCuts {
    const val PER_SESSION = 8
    const val MAX_TOTAL = 256
    const val TTL_MS = 60_000L

    @Volatile
    internal var clock: () -> Long = System::currentTimeMillis

    /** Test seam: lookups so far (a read must look its cut up once). */
    @Volatile
    internal var findsForTest = 0

    private class Entry(
        val cut: HealthCut,
        var usedAtMs: Long,
    )

    // Guarded by itself. Each session's entries least recently used first.
    private val bySession = HashMap<String, ArrayDeque<Entry>>()
    private var total = 0

    /**
     * Keeps [cut]; an equivalent kept cut (same binding and evidence) is replaced. Two cuts with one binding but different
     * evidence are both kept, and a follow-up gets the newer (a database trim between two healths at one seq, Part 5b-2).
     */
    fun remember(cut: HealthCut) =
        synchronized(bySession) {
            val entries = bySession.getOrPut(cut.sessionId) { ArrayDeque() }
            if (entries.removeAll { it.cut.equivalent(cut) }) total--
            entries.addLast(Entry(cut, clock()))
            total++
            if (entries.size > PER_SESSION) {
                entries.removeFirst()
                total--
            }
            while (total > MAX_TOTAL) dropLeastRecentlyUsed()
        }

    /**
     * The kept cut of [sessionId] at [seq] whose watermark is [watermarkNanos] (null names a cut without one) and, when
     * given, whose transport revision is [transportRevision] (else the newest revision kept), or null. A hit counts
     * as a use.
     */
    fun find(
        sessionId: String,
        seq: Long,
        watermarkNanos: Long?,
        transportRevision: Long?,
    ): HealthCut? =
        synchronized(bySession) {
            findsForTest++
            val now = clock()
            val entries = bySession[sessionId] ?: return null
            val hit =
                entries
                    .filter {
                        it.cut.seq == seq &&
                            it.cut.watermarkNanos == watermarkNanos &&
                            (transportRevision == null || it.cut.transportRevision == transportRevision) &&
                            now - it.usedAtMs <= TTL_MS
                    }.let { matches ->
                        // The newest revision; of equal ones the most recently used (entries are in use order).
                        val newest = matches.maxOfOrNull { it.cut.transportRevision }
                        matches.lastOrNull { it.cut.transportRevision == newest }
                    } ?: return null
            hit.usedAtMs = now
            entries.remove(hit)
            entries.addLast(hit)
            hit.cut
        }

    fun forget(sessionId: String) =
        synchronized(bySession) {
            bySession.remove(sessionId)?.let { total -= it.size }
        }

    internal fun clear() =
        synchronized(bySession) {
            bySession.clear()
            total = 0
            findsForTest = 0
            clock = System::currentTimeMillis
        }

    private fun dropLeastRecentlyUsed() {
        val (sessionId, entries) = bySession.entries.minBy { it.value.first().usedAtMs }
        entries.removeFirst()
        total--
        if (entries.isEmpty()) bySession.remove(sessionId)
    }
}
