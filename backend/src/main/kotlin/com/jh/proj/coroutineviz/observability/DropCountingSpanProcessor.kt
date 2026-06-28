package com.jh.proj.coroutineviz.observability

import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor
import java.util.concurrent.atomic.AtomicLong

/**
 * A pass-through [SpanProcessor] decorator that counts ended spans (D-11).
 *
 * Wraps a delegate (in practice a bounded [io.opentelemetry.sdk.trace.export.BatchSpanProcessor])
 * via Kotlin's `by` delegation so every [SpanProcessor] method — `onStart`, `isStartRequired`,
 * `isEndRequired`, `forceFlush`, `shutdown` — forwards unchanged. Only [onEnd] is overridden:
 * it increments [spansEnded] and then forwards to the delegate (which enqueues the span on its
 * bounded queue, dropping silently on overflow).
 *
 * ## Why a counter, not a custom queue (RESEARCH Open Q1 / D-11)
 * The `BatchSpanProcessor` already owns the bounded-queue / drop-on-overflow / non-blocking
 * worker-thread behavior (ADR-020). A pure decorator cannot directly observe that queue's
 * internal drop count — so v1 deliberately does NOT hand-roll a replacement queue. Instead it
 * exposes [spansEnded] (an app-level "spans handed to the pipeline" signal) and relies on the
 * SDK's own self-monitoring metrics for the authoritative drop number. This mirrors the
 * `AtomicLong`→Micrometer bridge idiom (`MetricsWiring.sseSamplingDroppedGauge`).
 *
 * @property spansEnded monotonic count of spans passed through [onEnd] since construction.
 */
class DropCountingSpanProcessor(
    private val delegate: SpanProcessor,
) : SpanProcessor by delegate {
    /** Monotonic count of spans that have ended and been handed to the delegate pipeline. */
    val spansEnded = AtomicLong(0)

    override fun onEnd(span: ReadableSpan) {
        spansEnded.incrementAndGet()
        delegate.onEnd(span)
    }
}
