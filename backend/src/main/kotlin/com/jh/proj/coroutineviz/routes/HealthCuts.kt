package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.health.RetentionInfo
import com.jh.proj.coroutineviz.health.SummaryView

/**
 * The exact inputs one health response used (spec C-3 `asOfSeq`): a detached copy of the summaries, the ingest facts the
 * derivation saw ([ingest]'s transport is the one it reported), and the retention facts, at [seq].
 */
internal class HealthCut(
    val sessionId: String,
    val seq: Long,
    val summaries: SummaryView,
    val ingest: IngestSnapshot,
    val retention: RetentionInfo,
    val createdAtMs: Long,
)

/**
 * Health cuts kept briefly so the follow-up reads bound to a health (`asOfSeq`) answer from exactly what that health saw
 * (plan E-17): exact by construction, so a busy app's view makes progress while events keep arriving. Only a cut that is
 * gone (expired, dropped for space, or kept by another backend process) leaves the read to rebuild the past from retained
 * events, which may answer `STALE`. Process-local, like [IngestStates]; a summary copy shares its (immutable) summaries
 * with the session, so a cut costs one map per kept response.
 */
internal object HealthCuts {
    const val PER_SESSION = 2
    const val MAX_TOTAL = 256
    const val TTL_MS = 60_000L

    @Volatile
    internal var clock: () -> Long = System::currentTimeMillis

    // Guarded by itself. Each session's cuts oldest first.
    private val bySession = HashMap<String, ArrayDeque<HealthCut>>()
    private var total = 0

    fun remember(cut: HealthCut) =
        synchronized(bySession) {
            val cuts = bySession.getOrPut(cut.sessionId) { ArrayDeque() }
            cuts.addLast(cut)
            total++
            if (cuts.size > PER_SESSION) {
                cuts.removeFirst()
                total--
            }
            while (total > MAX_TOTAL) dropOldest()
        }

    /** The newest kept cut of [sessionId] at [seq] that has not expired, or null. */
    fun find(
        sessionId: String,
        seq: Long,
    ): HealthCut? =
        synchronized(bySession) {
            val oldestAllowed = clock() - TTL_MS
            bySession[sessionId]?.lastOrNull { it.seq == seq && it.createdAtMs >= oldestAllowed }
        }

    fun forget(sessionId: String) =
        synchronized(bySession) {
            bySession.remove(sessionId)?.let { total -= it.size }
        }

    internal fun clear() =
        synchronized(bySession) {
            bySession.clear()
            total = 0
            clock = System::currentTimeMillis
        }

    private fun dropOldest() {
        val (sessionId, cuts) = bySession.entries.minBy { it.value.first().createdAtMs }
        cuts.removeFirst()
        total--
        if (cuts.isEmpty()) bySession.remove(sessionId)
    }
}
