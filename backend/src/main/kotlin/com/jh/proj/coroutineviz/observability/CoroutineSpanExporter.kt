package com.jh.proj.coroutineviz.observability

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-session OTel span emitter (OTEL-02, D-03..D-09) — a near-exact structural clone of
 * [com.jh.proj.coroutineviz.session.MetricsProjection].
 *
 * Subscribes to the session [com.jh.proj.coroutineviz.session.EventBus] in `init` (NEVER
 * `GlobalScope`, D-10) and turns each coroutine *lifecycle* into ONE OTLP span (D-03):
 * - **start** on [CoroutineCreated]/[CoroutineStarted];
 * - **parentage** resolved from `parentCoroutineId` via [openSpans] and set EXPLICITLY with
 *   `setParent(Context.root().with(parent))` (D-06) — never `Span.current()`/ThreadLocal; a missing
 *   parent degrades to a root span via `setNoParent()` (accepted v1, Pitfall 4);
 * - **suspensions** are recorded as span events (`addEvent`), not child spans (D-09);
 * - **end** on the terminal event — OK for [CoroutineCompleted]; ERROR for BOTH [CoroutineFailed]
 *   AND [CoroutineCancelled] (D-05); [CoroutineFailed] also `recordException`s the type/message
 *   ONLY (NOT the full stack-trace list — caps leak surface, V7 / T-12-04);
 * - **start timestamp** = `createdAtEpochMs` wall-clock (`Instant.ofEpochMilli`), falling back to
 *   `Instant.now()` when `0L` — `tsNanos` (per-process `nanoTime`) is NEVER used as an epoch
 *   (two-clock trap, Pitfall 5).
 *
 * On session close the bus collector's `collect{}` stops (the [VizSession.sessionScope] is
 * cancelled); a `finally` block then runs [sweepOpenSpans], force-closing every still-open span as
 * ERROR/"leaked" (D-08) — self-cleaning, no `removeOnSessionClosed` registry leak (CR-02).
 *
 * The exporter touches ONLY the post-bus `EventBus.stream()` egress — NEVER [VizSession.send],
 * `sendLock`, or the store (governing invariant #1).
 */
class CoroutineSpanExporter(
    private val session: VizSession,
    private val tracer: Tracer,
) {
    /** coroutineId -> live (started, not-yet-ended) span. Bounded to the ACTIVE set (T-08-06). */
    private val openSpans = ConcurrentHashMap<String, Span>()

    /**
     * Count of currently-open (started, not-yet-terminal) spans. Exposed for deterministic
     * test synchronization on the async bus-collector path (mirrors how [MetricsProjection]
     * exposes `snapshot`); not part of the production wiring surface.
     */
    internal fun openSpanCount(): Int = openSpans.size

    init {
        // Subscribe exactly like MetricsProjection; the finally is the self-cleaning leak sweep
        // (RESEARCH Pattern 6) — preferred over addOnSessionClosed to avoid a CR-02 registry leak.
        session.sessionScope.launch {
            try {
                session.eventBus.stream().collect { event -> onEvent(event) }
            } finally {
                sweepOpenSpans()
            }
        }
    }

    private fun onEvent(event: VizEvent) {
        when (event) {
            is CoroutineCreated ->
                startSpan(
                    event.coroutineId,
                    event.parentCoroutineId,
                    event.jobId,
                    event.scopeId,
                    event.label,
                    event.createdAtEpochMs,
                )
            is CoroutineStarted ->
                startSpan(
                    event.coroutineId,
                    event.parentCoroutineId,
                    event.jobId,
                    event.scopeId,
                    event.label,
                    createdAtEpochMs = 0L,
                )

            is CoroutineSuspended -> onSuspended(event)
            is CoroutineResumed -> openSpans[event.coroutineId]?.addEvent("resumed")

            is CoroutineCompleted -> onTerminal(event.coroutineId, StatusCode.OK)
            is CoroutineCancelled -> onTerminal(event.coroutineId, StatusCode.ERROR)
            is CoroutineFailed -> onFailed(event)

            else -> Unit
        }
    }

    @Suppress("LongParameterList")
    private fun startSpan(
        coroutineId: String,
        parentCoroutineId: String?,
        jobId: String,
        scopeId: String,
        label: String?,
        createdAtEpochMs: Long,
    ) {
        // Idempotent on a Created→Started pair: keep the already-open span (one span per lifecycle).
        if (openSpans.containsKey(coroutineId)) return

        val name = label ?: "coroutine#$coroutineId"
        val builder = tracer.spanBuilder(name)

        val parent: Span? = parentCoroutineId?.let { openSpans[it] }
        if (parent != null) {
            // EXPLICIT parentage from causality data; Context.root() guarantees no ambient/
            // ThreadLocal parent leaks in (D-06, Pitfall 1).
            builder.setParent(Context.root().with(parent))
        } else {
            builder.setNoParent()
        }

        // Wall-clock start basis; NEVER feed tsNanos (System.nanoTime) to ofEpochMilli (Pitfall 5).
        val startInstant =
            createdAtEpochMs.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) } ?: Instant.now()
        val span = builder.setStartTimestamp(startInstant).startSpan()

        span.setAttribute("coroutine.id", coroutineId)
        parentCoroutineId?.let { span.setAttribute("coroutine.parent_id", it) }
        span.setAttribute("coroutine.job_id", jobId)
        span.setAttribute("coroutine.scope_id", scopeId)

        openSpans[coroutineId] = span
    }

    private fun onSuspended(event: CoroutineSuspended) {
        val span = openSpans[event.coroutineId] ?: return
        val point = event.suspensionPoint
        val location =
            point?.let { p -> p.fileName?.let { "$it:${p.lineNumber ?: -1}" } }
        val attrs =
            Attributes
                .builder()
                .put("suspension.reason", point?.reason ?: event.reason)
                .apply {
                    point?.function?.let { put("suspension.function", it) }
                    location?.let { put("suspension.location", it) }
                }.build()
        // A span EVENT, NOT a child span (D-09).
        span.addEvent("suspended", attrs)
    }

    private fun onFailed(event: CoroutineFailed) {
        val span = openSpans[event.coroutineId]
        if (span != null) {
            // Record the exception TYPE/MESSAGE only — the full stackTrace list is deliberately NOT
            // attached as span attributes (V7 / T-12-04, caps leak surface).
            val throwable = SyntheticCoroutineFailure(event.exceptionType, event.message)
            span.recordException(throwable)
        }
        onTerminal(event.coroutineId, StatusCode.ERROR)
    }

    private fun onTerminal(
        coroutineId: String,
        status: StatusCode,
    ) {
        // remove → bounds the live map to the ACTIVE set (MetricsProjection T-08-06 idiom).
        openSpans.remove(coroutineId)?.apply {
            setStatus(status)
            end()
        }
    }

    /**
     * Session-close sweep (D-08): any span still open is a never-terminated coroutine (or a lost
     * terminal). Force-close it as ERROR/"leaked" so nothing hangs open, then clear the map.
     */
    private fun sweepOpenSpans() {
        openSpans.values.forEach { span ->
            span.setStatus(StatusCode.ERROR, "leaked: session closed before terminal event")
            span.setAttribute("coroutine.leaked", true)
            span.end()
        }
        openSpans.clear()
    }

    /**
     * A minimal [Throwable] carrying ONLY the original exception type (as the message prefix) and
     * message so `Span.recordException` records `exception.type`/`exception.message` WITHOUT the
     * originating coroutine's full captured stack-trace frames (V7 / T-12-04). Its own stack trace
     * is suppressed.
     */
    private class SyntheticCoroutineFailure(
        exceptionType: String?,
        message: String?,
    ) : RuntimeException(buildMessage(exceptionType, message)) {
        init {
            stackTrace = emptyArray()
        }

        companion object {
            private fun buildMessage(
                exceptionType: String?,
                message: String?,
            ): String =
                listOfNotNull(exceptionType, message)
                    .joinToString(": ")
                    .ifEmpty { "coroutine failed" }
        }
    }
}
