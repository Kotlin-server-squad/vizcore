package com.jh.proj.coroutineviz.persistence

import com.jh.proj.coroutineviz.persistence.tables.EventsTable
import com.jh.proj.coroutineviz.persistence.tables.SessionsTable
import com.jh.proj.coroutineviz.persistence.tables.SharesTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.NotExists
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * DB-aware retention policy (PERS-03). A NEW sibling to the in-memory
 * [com.jh.proj.coroutineviz.session.RetentionPolicy] — it uses ADR-015 defaults
 * (30d / 100 000 events / 60 min) and bulk SQL deletes against the persistent
 * store instead of the in-memory map. The in-memory policy is left untouched.
 *
 * `cleanup()` performs two bulk operations via the Exposed DSL (parameterized,
 * no string SQL — T-03-12):
 *
 * 1. **max-age delete** — delete `sessions` whose `created_at` is older than
 *    `now - maxAgeDays`, GUARDED by a `NOT EXISTS` active-share subquery so a
 *    session backing a live share link is never deleted (Pitfall 6 / ADR-019).
 *    A share is "active" when `expires_at IS NULL` (never expires) OR
 *    `expires_at > now`. The FK cascade removes the deleted sessions' events and
 *    shares.
 * 2. **per-session event trim** — for any session exceeding
 *    [maxEventsPerSession], delete the OLDEST events (lowest `seq`) so the row
 *    count is at most the cap.
 *
 * The background loop launches in a caller-supplied application-lifecycle
 * [CoroutineScope] (structured concurrency — CLAUDE.md forbids the global
 * scope), mirroring the in-memory policy's start/stop/cleanup shape with an
 * injectable [clock] for deterministic tests.
 *
 * @property clock current time in epoch millis; override in tests.
 */
@OptIn(ExperimentalTime::class)
class DbRetentionPolicy(
    private val db: Database,
    private val maxAgeDays: Int = 30,
    private val maxEventsPerSession: Int = 100_000,
    private val cleanupIntervalMinutes: Long = 60,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(DbRetentionPolicy::class.java)
    private var job: Job? = null

    private val intervalMs: Long = cleanupIntervalMinutes * 60_000L
    private val maxAgeMs: Long = maxAgeDays.toLong() * 86_400_000L

    /**
     * Start the periodic cleanup coroutine in [scope] (typically the application
     * scope). No-op if already running.
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job =
            scope.launch {
                logger.info(
                    "DB retention started: maxAgeDays={}, maxEventsPerSession={}, intervalMin={}",
                    maxAgeDays,
                    maxEventsPerSession,
                    cleanupIntervalMinutes,
                )
                while (isActive) {
                    delay(intervalMs)
                    val removed = cleanup()
                    if (removed > 0) {
                        logger.info("DB retention removed {} session(s)", removed)
                    }
                }
            }
    }

    /** Stop the background cleanup. Safe to call multiple times. */
    fun stop() {
        job?.cancel()
        job = null
        logger.info("DB retention stopped")
    }

    /**
     * Run a single cleanup pass: max-age delete (active-share-guarded) followed
     * by per-session event trim. Returns the number of sessions deleted.
     */
    fun cleanup(): Int {
        val now = Instant.fromEpochMilliseconds(clock())
        val cutoff = Instant.fromEpochMilliseconds(clock() - maxAgeMs)

        val deletedSessions =
            transaction(db) {
                // NOT EXISTS (active share) guard: a share is active when it never
                // expires (expires_at IS NULL) OR expires in the future.
                val activeShareSubquery =
                    SharesTable
                        .selectAll()
                        .where {
                            (SharesTable.sessionId eq SessionsTable.id) and
                                (SharesTable.expiresAt.isNull() or (SharesTable.expiresAt greater now))
                        }

                SessionsTable.deleteWhere {
                    (SessionsTable.createdAt less cutoff) and NotExists(activeShareSubquery)
                }
            }

        trimEvents()

        return deletedSessions
    }

    /**
     * Per-session event trim. For each session whose event count exceeds the cap,
     * delete the oldest events (lowest `seq`) until `count <= maxEventsPerSession`.
     * The retained set is the newest `maxEventsPerSession` events.
     */
    private fun trimEvents() {
        transaction(db) {
            // Group all (session_id, seq) pairs by session to find over-cap sessions.
            val seqsBySession: Map<String, List<Long>> =
                EventsTable
                    .selectAll()
                    .map { it[EventsTable.sessionId] to it[EventsTable.seq] }
                    .groupBy({ it.first }, { it.second })

            for ((sessionId, seqs) in seqsBySession) {
                if (seqs.size <= maxEventsPerSession) continue

                // Keep the newest `maxEventsPerSession` by seq; the cutoff is the
                // smallest kept seq, and anything strictly below it is trimmed.
                val keepCutoffSeq = seqs.sortedDescending()[maxEventsPerSession - 1]
                EventsTable.deleteWhere {
                    (EventsTable.sessionId eq sessionId) and (EventsTable.seq less keepCutoffSeq)
                }
            }
        }
    }
}
