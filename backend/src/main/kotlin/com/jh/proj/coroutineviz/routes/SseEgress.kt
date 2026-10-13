package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.session.EventBatcher
import com.jh.proj.coroutineviz.session.EventSampler
import com.jh.proj.coroutineviz.session.StructuralAwareBuffer
import io.ktor.server.application.Application
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory

/**
 * Per-subscriber egress tuning for the SSE `/stream` chain (Plan 03, D-02/D-04/D-07).
 *
 * Safe-default-ON: with the defaults the wire is byte-identical to pre-Phase-10 behavior —
 * adaptive sampling stays at full fidelity below the load watermark, and the batcher emits
 * size-1 batches (rendered as single-event frames). Only under load do `event: batch` /
 * `event: dropped` frames appear (D-04 invisibility-until-load).
 *
 * @property defaultRate per-type fallback sampling rate (1.0 = keep all; structural always kept).
 * @property perTypeRates per-[VizEvent.kind] sampling-rate overrides.
 * @property adaptive whether the adaptive throughput gate is active (full fidelity below load).
 * @property batchCount flush size (count trigger) for the hybrid batcher (D-05).
 * @property batchWindowMs flush window (ms since the first pending element) for the batcher (D-05).
 * @property shedCapacity bound on the structural-aware sheddable lane (D-07).
 */
data class EgressConfig(
    val defaultRate: Double = 1.0,
    val perTypeRates: Map<String, Double> = emptyMap(),
    val adaptive: Boolean = true,
    val batchCount: Int = EventBatcher.BATCH_COUNT,
    val batchWindowMs: Long = EventBatcher.BATCH_WINDOW_MS,
    val shedCapacity: Int = StructuralAwareBuffer.DEFAULT_SHED_CAPACITY,
)

/**
 * Read the per-subscriber [EgressConfig] from `perf.egress.*` application config with safe,
 * default-ON values (the `Routing.kt:44,50` `propertyOrNull` idiom). Absent config → byte-identical
 * pre-Phase-10 behavior (adaptive on, default rate 1.0, the Plan 01/02 batch/shed defaults).
 */
fun Application.egressConfig(): EgressConfig {
    val cfg = environment.config

    fun double(
        path: String,
        default: Double,
    ): Double = cfg.propertyOrNull(path)?.getString()?.toDoubleOrNull() ?: default

    fun int(
        path: String,
        default: Int,
    ): Int = cfg.propertyOrNull(path)?.getString()?.toIntOrNull() ?: default

    fun long(
        path: String,
        default: Long,
    ): Long = cfg.propertyOrNull(path)?.getString()?.toLongOrNull() ?: default

    val adaptive = cfg.propertyOrNull("perf.egress.adaptive")?.getString()?.toBooleanStrictOrNull() ?: true
    return EgressConfig(
        defaultRate = double("perf.egress.defaultRate", 1.0),
        adaptive = adaptive,
        batchCount = int("perf.egress.batchCount", EventBatcher.BATCH_COUNT),
        batchWindowMs = long("perf.egress.batchWindowMs", EventBatcher.BATCH_WINDOW_MS),
        shedCapacity = int("perf.egress.shedCapacity", StructuralAwareBuffer.DEFAULT_SHED_CAPACITY),
    )
}

/** Default `/stream` heartbeat interval in seconds when `sse.heartbeatSeconds` is absent. */
const val DEFAULT_SSE_HEARTBEAT_SECONDS: Long = 15

/** `sse.heartbeatSeconds` is configured in seconds; the stream works in milliseconds. */
const val SECOND_MILLIS: Long = 1_000

/**
 * Longest `/stream` heartbeat interval the server will use. The SPA watchdog treats 45 s without
 * a frame as a lost connection, so a longer interval would make it drop healthy, quiet streams.
 */
const val MAX_SSE_HEARTBEAT_SECONDS: Long = 30

private val heartbeatLog = LoggerFactory.getLogger("com.jh.proj.coroutineviz.routes.SseHeartbeat")

/**
 * Read the `/stream` keep-alive interval from `sse.heartbeatSeconds` (same `propertyOrNull`
 * idiom as [egressConfig]), falling back to [DEFAULT_SSE_HEARTBEAT_SECONDS].
 */
fun Application.sseHeartbeatSeconds(): Long =
    resolveSseHeartbeatSeconds(
        environment.config
            .propertyOrNull("sse.heartbeatSeconds")
            ?.getString(),
    )

/**
 * The configured heartbeat interval, kept within `1..`[MAX_SSE_HEARTBEAT_SECONDS]. A missing or
 * unparseable value falls back to [DEFAULT_SSE_HEARTBEAT_SECONDS]; an out-of-range one is clamped
 * with a warning rather than silently breaking the SPA's dead-connection detection.
 */
internal fun resolveSseHeartbeatSeconds(raw: String?): Long {
    val configured = raw?.trim()?.toLongOrNull() ?: return DEFAULT_SSE_HEARTBEAT_SECONDS
    val clamped = configured.coerceIn(1, MAX_SSE_HEARTBEAT_SECONDS)
    if (clamped != configured) {
        heartbeatLog.warn(
            "sse.heartbeatSeconds={} is outside 1..{}; using {}",
            configured,
            MAX_SSE_HEARTBEAT_SECONDS,
            clamped,
        )
    }
    return clamped
}

/**
 * The `/stream` keep-alive frame: a NAMED event, because browser `EventSource` silently drops
 * SSE comments and the SPA watchdog has to observe it.
 *
 * It is a pure egress artifact and deliberately bypasses the whole egress chain: it is never
 * sampled, shed, batched or counted as dropped, carries no `seq` and no `id` (so it cannot
 * become a `Last-Event-ID` resume point), is never recorded in the store, and therefore never
 * appears in `/events` or in a replay.
 */
internal fun heartbeatFrame(): ServerSentEvent =
    ServerSentEvent(
        data = """{"ts":${System.currentTimeMillis()}}""",
        event = "heartbeat",
    )

/**
 * Build the consumer-side egress chain — `adaptive-sample → structural-shed → hybrid-batch`
 * (RESEARCH §2 order) — over [upstream] and render it as a flow of [ServerSentEvent] frames.
 *
 * All three primitives are per-subscriber state (one [EventSampler] + [StructuralAwareBuffer] +
 * [EventBatcher] per call), never shared across connections. The chain composes entirely on the
 * `stream()` CONSUMER side: it never reaches back into `VizSession.send` / the store (D-03,
 * Pitfall P1/P2). The store keeps 100% of every event upstream; a shed event is recoverable via
 * `/events`.
 *
 * Frame shapes (D-04 hybrid):
 *  - batch size 1 → today's single-event frame via [VizEvent.toSse] (byte-identical, back-compat).
 *  - batch size > 1 → an `event: batch` frame whose data is a JSON array encoded with the SAME
 *    [appJson] used for ingest/replay ("wire == ingest == replay").
 *  - whenever the [StructuralAwareBuffer] drop counter advances, an `event: dropped` control frame
 *    carrying the delta is interleaved — emitted egress-only, NEVER store.record'd or replayed
 *    (D-08, Pitfall P7).
 *
 * [onShedDelta] is invoked with each positive drop delta so the route can surface it to the
 * `events.dropped.sampling` metric (callback-to-Micrometer; core stays framework-free, D-11).
 */
fun sseEgressFrames(
    upstream: Flow<VizEvent>,
    config: EgressConfig,
    onShedDelta: (Long) -> Unit = {},
): Flow<ServerSentEvent> =
    flow {
        val sampler =
            EventSampler(
                defaultRate = config.defaultRate,
                perTypeRates = config.perTypeRates,
                adaptive = config.adaptive,
            )
        val shedBuffer = StructuralAwareBuffer(shedCapacity = config.shedCapacity)

        coroutineScope {
            // Pump upstream into the structural-aware buffer, applying the adaptive sampler at the
            // gate (structural events bypass sampling inside shouldKeep). Closing the buffer on
            // upstream completion lets the drain flow below terminate.
            launch {
                try {
                    upstream.collect { event ->
                        if (sampler.shouldKeep(event)) {
                            shedBuffer.offer(event)
                        }
                    }
                } finally {
                    shedBuffer.close()
                }
            }

            // Drain the buffer (lifecycle-first), batch it, and render frames. Between batches,
            // emit a `dropped` control frame whenever the shed counter has advanced.
            var lastReportedDrops = 0L
            EventBatcher
                .batched(shedBuffer.stream(), count = config.batchCount, windowMs = config.batchWindowMs)
                .collect { batch ->
                    if (batch.size == 1) {
                        emit(batch.single().toSse())
                    } else {
                        emit(batchToSse(batch))
                    }
                    lastReportedDrops = emitDroppedDelta(shedBuffer.dropped, lastReportedDrops, onShedDelta)
                }
            // Final drain: report any drops that landed after the last batch flush.
            emitDroppedDelta(shedBuffer.dropped, lastReportedDrops, onShedDelta)
        }
    }

/**
 * Emit an `event: dropped` control frame for the positive delta between [currentDrops] and
 * [lastReported], invoking [onShedDelta] for metrics, and return the new high-water mark.
 * No frame is emitted when the counter has not advanced.
 */
private suspend fun kotlinx.coroutines.flow.FlowCollector<ServerSentEvent>.emitDroppedDelta(
    currentDrops: Long,
    lastReported: Long,
    onShedDelta: (Long) -> Unit,
): Long {
    val delta = currentDrops - lastReported
    if (delta > 0) {
        onShedDelta(delta)
        emit(ServerSentEvent(event = "dropped", data = """{"count":$delta}"""))
    }
    return currentDrops
}

/**
 * Hybrid batch frame (D-04): `event: batch`, data = JSON array of [batch] encoded with the SAME
 * [appJson] used for single events and replay, so wire == ingest == replay.
 */
internal fun batchToSse(batch: List<VizEvent>): ServerSentEvent =
    ServerSentEvent(
        data = appJson.encodeToString(ListSerializer(PolymorphicSerializer(VizEvent::class)), batch),
        event = "batch",
    )
