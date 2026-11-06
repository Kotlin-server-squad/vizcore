package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.health.SessionHealthDerivation
import com.jh.proj.coroutineviz.health.SessionKind
import com.jh.proj.coroutineviz.health.SessionOrigin
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.OriginBucket
import com.jh.proj.coroutineviz.session.SessionManager

private const val NANOS_PER_MS = 1_000_000L

/**
 * `/metrics` by origin (spec C-3, plan E-15): captured, active, dispatcher load and long-lived from the view's summary copy
 * (one cut), peak and throughput from the projection's per-origin buckets (peak at least the active count). Leaks are the
 * long-lived rule's, every origin, with their origin and frozen ages.
 */
internal fun metricsByOrigin(
    base: MetricsResponse,
    view: SessionView,
): MetricsResponse {
    val derived = view.derive()
    val summaries = view.input.summaries.all
    val longLived = SessionHealthDerivation.longLivedByOrigin(view.input)
    val buckets = view.session.metricsProjection.byOrigin()
    val demo = view.input.kind == SessionKind.DEMO
    val unknownSession = derived.health.sessionOrigin == SessionOrigin.UNKNOWN

    // Which projection buckets feed an effective origin: demos are all APP, a session of unknown origin all UNKNOWN.
    fun bucketsOf(origin: CoroutineOrigin): List<OriginBucket> =
        when {
            demo -> if (origin == CoroutineOrigin.APP) OriginBucket.entries else emptyList()
            unknownSession -> if (origin == CoroutineOrigin.UNKNOWN) OriginBucket.entries else emptyList()
            else ->
                listOf(
                    when (origin) {
                        CoroutineOrigin.APP -> OriginBucket.APP
                        CoroutineOrigin.VIZCORE -> OriginBucket.VIZCORE
                        CoroutineOrigin.UNKNOWN -> OriginBucket.UNREPORTED
                    },
                )
        }

    fun slice(origin: CoroutineOrigin): OriginSlice {
        val members = summaries.filter { derived.originOf(it) == origin }
        val active = members.filter { !it.isTerminal }
        val fed = bucketsOf(origin).mapNotNull { buckets[it] }
        val throughputs = fed.map { it.throughputPerSec }
        return OriginSlice(
            captured = members.size,
            active = active.size,
            // Every bucket merged into one origin (demo, unknown session): the session's own peak is that origin's.
            peak = maxOf(if (fed.size == OriginBucket.entries.size) base.peak else fed.maxOfOrNull { it.peak } ?: 0, active.size),
            throughputPerSec = if (throughputs.any { it == null }) null else throughputs.sumOf { it ?: 0.0 },
            dispatcherUtilization = active.groupingBy { it.dispatcher ?: "unknown" }.eachCount(),
            longLived = longLived.count { it.first == origin },
        )
    }
    val retention = view.input.retention
    val rebuilt = SessionManager.backingStore() is ExposedSessionStore && retention?.trimmed == true
    return base.copy(
        leaks =
            longLived.map { (origin, age) ->
                LeakDto(age.coroutineId, age.name, age.ageNanos / NANOS_PER_MS, origin)
            },
        leaksAvailable = true,
        byOrigin =
            OriginMetrics(
                app = slice(CoroutineOrigin.APP),
                vizcore = slice(CoroutineOrigin.VIZCORE),
                unknown = slice(CoroutineOrigin.UNKNOWN),
                untracked = view.input.summaries.untrackedCount,
            ),
        retainedOnly = rebuilt,
        oldestRetainedAppNanos = retention?.oldestRetainedAppNanos?.takeIf { rebuilt },
    )
}

/**
 * Without usable summaries, the retention facts still say whether the projection figures cover retained events only: a
 * database session's oldest stored event later than seq 1, the first seq every session gets (5a D-8).
 */
internal fun MetricsResponse.withRetainedOnly(session: com.jh.proj.coroutineviz.session.VizSession): MetricsResponse {
    val oldest = (session.rehydratedEvents ?: session.store.all()).firstOrNull()
    val trimmed = SessionManager.backingStore() is ExposedSessionStore && oldest != null && oldest.seq > 1
    return if (trimmed) copy(retainedOnly = true, oldestRetainedAppNanos = oldest?.tsNanos) else this
}
