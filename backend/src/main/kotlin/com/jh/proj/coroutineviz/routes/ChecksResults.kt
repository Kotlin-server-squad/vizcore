package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.checksystem.ValidationResult
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.SessionManager
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/** One rule of a checks run: passed when none of its results failed (spec C-5). */
@Serializable
data class RuleOutcome(
    val name: String,
    val passed: Boolean,
    val failures: Int,
)

/** The last checks run of a session (spec C-5, T-8): the last event it validated, when, and each rule's outcome. */
@Serializable
data class ChecksRun(
    val checkedSeq: Long,
    val ranAtEpochMs: Long,
    val rules: List<RuleOutcome>,
    val failedRuleCount: Int,
) {
    companion object {
        fun of(
            results: List<ValidationResult>,
            events: List<VizEvent>,
            ranAtEpochMs: Long,
        ): ChecksRun {
            val rules =
                results.groupBy { it.ruleName }.map { (name, outcomes) ->
                    val failures = outcomes.count { it !is ValidationResult.Pass }
                    RuleOutcome(name, passed = failures == 0, failures = failures)
                }
            return ChecksRun(events.maxOfOrNull { it.seq } ?: 0L, ranAtEpochMs, rules, rules.count { !it.passed })
        }
    }
}

/** `GET /api/validate/session/{id}` (spec C-5): the last run, null when never run, and how many events came since. */
@Serializable
data class ChecksStatus(
    val sessionId: String,
    val lastRun: ChecksRun?,
    val newEventsSince: Long?,
)

/** The last checks run per session (spec C-5): the session row's `checks` column in database mode, a map in memory. */
internal object ChecksResults {
    private val memory = ConcurrentHashMap<String, ChecksRun>()

    private fun database(): ExposedSessionStore? = SessionManager.backingStore() as? ExposedSessionStore

    fun save(
        sessionId: String,
        run: ChecksRun,
    ) {
        val store = database()
        if (store == null) memory[sessionId] = run else store.saveChecks(sessionId, appJson.encodeToString(ChecksRun.serializer(), run))
    }

    fun load(sessionId: String): ChecksRun? {
        val store = database() ?: return memory[sessionId]
        return store.loadChecks(sessionId)?.let { json ->
            runCatching { appJson.decodeFromString(ChecksRun.serializer(), json) }.getOrNull()
        }
    }

    fun status(
        sessionId: String,
        run: ChecksRun?,
        lastSeq: Long,
    ) = ChecksStatus(sessionId, run, run?.let { maxOf(0L, lastSeq - it.checkedSeq) })

    fun forget(sessionId: String) {
        memory.remove(sessionId)
    }

    internal fun clear() = memory.clear()
}
