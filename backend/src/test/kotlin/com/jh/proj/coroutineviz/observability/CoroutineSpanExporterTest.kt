package com.jh.proj.coroutineviz.observability

import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wave-0 proof for [CoroutineSpanExporter] (OTEL-02 / D-03..D-09) driving a real [VizSession]
 * through its `EventBus`, asserting span shape against an [InMemorySpanExporter]:
 *  - one span per lifecycle, started on Created / ended on terminal,
 *  - causality parentage from `parentCoroutineId` (NOT ThreadLocal),
 *  - missing-parent → root (accepted v1),
 *  - status ERROR for Failed AND Cancelled, OK for Completed (D-05),
 *  - suspensions as span events not child spans (D-09),
 *  - session-close leak sweep ends open spans ERROR/"leaked" (D-08),
 *  - span start timestamp derives from `createdAtEpochMs` wall-clock (Pitfall 5).
 *
 * Uses `@org.junit.jupiter.api.Test` (repo convention under `useJUnitPlatform`).
 */
@Suppress("TooManyFunctions") // event-ctor + await helpers inflate the count past the class threshold
class CoroutineSpanExporterTest {
    private lateinit var inMemory: InMemorySpanExporter
    private lateinit var provider: SdkTracerProvider
    private lateinit var tracer: Tracer
    private val seq = AtomicLong(0)

    @BeforeEach
    fun setUp() {
        inMemory = InMemorySpanExporter.create()
        provider =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(inMemory))
                .build()
        tracer = provider.get("test")
    }

    @AfterEach
    fun tearDown() {
        provider.shutdown().join(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    // --- helpers ----------------------------------------------------------------

    /** Subscribe the exporter and await its live subscription above the construction baseline. */
    private fun newExporterOn(session: VizSession): CoroutineSpanExporter {
        val baseline = session.eventBus.subscriptionCount.value
        val exporter = CoroutineSpanExporter(session, tracer)
        runBlocking {
            withTimeout(SUBSCRIBE_TIMEOUT_MS) {
                session.eventBus.subscriptionCount.first { it > baseline }
            }
        }
        return exporter
    }

    /** Send [event] and busy-wait until at least [expectedEnded] spans have been exported. */
    private fun sendAndAwaitEnded(
        session: VizSession,
        event: VizEvent,
        expectedEnded: Int,
    ) {
        session.send(event)
        awaitEnded(expectedEnded)
    }

    private fun awaitEnded(expected: Int) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (inMemory.finishedSpanItems.size < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
        }
    }

    /** Busy-wait until the exporter has at least [expected] open (started, not-terminal) spans. */
    private fun awaitOpen(
        exporter: CoroutineSpanExporter,
        expected: Int,
    ) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (exporter.openSpanCount() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
        }
    }

    private fun created(
        session: VizSession,
        id: String,
        parentId: String? = null,
        label: String? = "co-$id",
        createdAtEpochMs: Long = DEFAULT_EPOCH_MS,
    ) = CoroutineCreated(
        sessionId = session.sessionId,
        seq = seq.incrementAndGet(),
        tsNanos = System.nanoTime(),
        coroutineId = id,
        jobId = "job-$id",
        parentCoroutineId = parentId,
        scopeId = "scope-$id",
        label = label,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun completed(
        session: VizSession,
        id: String,
    ) = CoroutineCompleted(
        sessionId = session.sessionId,
        seq = seq.incrementAndGet(),
        tsNanos = System.nanoTime(),
        coroutineId = id,
        jobId = "job-$id",
        parentCoroutineId = null,
        scopeId = "scope-$id",
        label = "co-$id",
    )

    private fun cancelled(
        session: VizSession,
        id: String,
    ) = CoroutineCancelled(
        sessionId = session.sessionId,
        seq = seq.incrementAndGet(),
        tsNanos = System.nanoTime(),
        coroutineId = id,
        jobId = "job-$id",
        parentCoroutineId = null,
        scopeId = "scope-$id",
        label = "co-$id",
        cause = "explicit cancel",
    )

    private fun failed(
        session: VizSession,
        id: String,
    ) = CoroutineFailed(
        sessionId = session.sessionId,
        seq = seq.incrementAndGet(),
        tsNanos = System.nanoTime(),
        coroutineId = id,
        jobId = "job-$id",
        parentCoroutineId = null,
        scopeId = "scope-$id",
        label = "co-$id",
        exceptionType = "java.lang.IllegalStateException",
        message = "boom",
        stackTrace = listOf("frame-1", "frame-2"),
    )

    private fun suspended(
        session: VizSession,
        id: String,
    ) = CoroutineSuspended(
        sessionId = session.sessionId,
        seq = seq.incrementAndGet(),
        tsNanos = System.nanoTime(),
        coroutineId = id,
        jobId = "job-$id",
        parentCoroutineId = null,
        scopeId = "scope-$id",
        label = "co-$id",
        reason = "delay",
        durationMillis = 100,
        suspensionPoint =
            SuspensionPoint(
                function = "myFunction",
                fileName = "MyFile.kt",
                lineNumber = 42,
                reason = "delay",
            ),
    )

    private fun spanByCoroutineId(id: String): SpanData? =
        inMemory.finishedSpanItems.firstOrNull { it.attributes.asMap().any { (k, v) -> k.key == "coroutine.id" && v == id } }

    // --- tests ------------------------------------------------------------------

    @Test
    fun `one CoroutineCreated then CoroutineCompleted yields exactly one OK span`() {
        val session = VizSession(sessionId = "s-ok")
        newExporterOn(session)

        session.send(created(session, "A"))
        sendAndAwaitEnded(session, completed(session, "A"), expectedEnded = 1)

        val spans: List<SpanData> = inMemory.finishedSpanItems
        assertEquals(1, spans.size, "exactly one span ended for one lifecycle")
        assertEquals(StatusCode.OK, spans[0].status.statusCode, "Completed → OK (D-05)")
        session.close()
    }

    @Test
    fun `child span parent is resolved from parentCoroutineId not ThreadLocal`() {
        val session = VizSession(sessionId = "s-parent")
        newExporterOn(session)

        session.send(created(session, "A"))
        session.send(created(session, "B", parentId = "A"))
        session.send(completed(session, "B"))
        sendAndAwaitEnded(session, completed(session, "A"), expectedEnded = 2)

        val parent = assertNotNull(spanByCoroutineId("A"), "parent span A present")
        val child = assertNotNull(spanByCoroutineId("B"), "child span B present")
        assertEquals(
            parent.spanContext.spanId,
            child.parentSpanContext.spanId,
            "child.parentSpanId == parent.spanId (causality parentage, D-06)",
        )
        session.close()
    }

    @Test
    fun `child whose parent has no open span becomes a root span`() {
        val session = VizSession(sessionId = "s-orphan")
        newExporterOn(session)

        // parentCoroutineId references an id that was never created/open → root (Pitfall 4).
        sendAndAwaitEnded(session, created(session, "B", parentId = "ghost"), expectedEnded = 0)
        session.send(completed(session, "B"))
        awaitEnded(1)

        val child = assertNotNull(spanByCoroutineId("B"), "orphan child span present")
        assertTrue(!child.parentSpanContext.isValid, "missing parent → root (setNoParent)")
        session.close()
    }

    @Test
    fun `CoroutineFailed ends span ERROR with recorded exception`() {
        val session = VizSession(sessionId = "s-fail")
        newExporterOn(session)

        session.send(created(session, "A"))
        sendAndAwaitEnded(session, failed(session, "A"), expectedEnded = 1)

        val span = assertNotNull(spanByCoroutineId("A"))
        assertEquals(StatusCode.ERROR, span.status.statusCode, "Failed → ERROR (D-05)")
        assertTrue(span.events.any { it.name == "exception" }, "exception recorded on span")
        session.close()
    }

    @Test
    fun `CoroutineCancelled ends span ERROR not OK`() {
        val session = VizSession(sessionId = "s-cancel")
        newExporterOn(session)

        session.send(created(session, "A"))
        sendAndAwaitEnded(session, cancelled(session, "A"), expectedEnded = 1)

        val span = assertNotNull(spanByCoroutineId("A"))
        assertEquals(StatusCode.ERROR, span.status.statusCode, "Cancelled → ERROR not OK (D-05)")
        session.close()
    }

    @Test
    fun `CoroutineSuspended adds a span event not a child span`() {
        val session = VizSession(sessionId = "s-suspend")
        newExporterOn(session)

        session.send(created(session, "A"))
        session.send(suspended(session, "A"))
        sendAndAwaitEnded(session, completed(session, "A"), expectedEnded = 1)

        val spans: List<SpanData> = inMemory.finishedSpanItems
        assertEquals(1, spans.size, "suspension is an event, NOT a new child span (D-09)")
        val span = assertNotNull(spanByCoroutineId("A"))
        val suspendEvent = span.events.firstOrNull { it.name == "suspended" }
        assertNotNull(suspendEvent, "a 'suspended' span event is present")
        val attrs = suspendEvent.attributes.asMap().mapKeys { it.key.key }
        assertEquals("myFunction", attrs["suspension.function"])
        assertEquals("MyFile.kt:42", attrs["suspension.location"])
        session.close()
    }

    @Test
    fun `session close sweeps still-open span as ERROR leaked`() {
        val session = VizSession(sessionId = "s-leak")
        val exporter = newExporterOn(session)

        // Created with NO terminal event, then close → leak sweep ends it (D-08). Await the span
        // being OPEN before close() so the sweep cannot race ahead of the bus-collector.
        session.send(created(session, "A"))
        awaitOpen(exporter, 1)
        session.close()
        awaitEnded(1)

        val span = assertNotNull(spanByCoroutineId("A"), "leaked span was force-closed")
        assertEquals(StatusCode.ERROR, span.status.statusCode, "leaked span → ERROR")
        assertTrue(span.status.description.contains("leaked"), "status carries 'leaked' message")
        val leaked =
            span.attributes
                .asMap()
                .entries
                .firstOrNull { it.key.key == "coroutine.leaked" }
        assertEquals(true, leaked?.value, "coroutine.leaked=true attribute set")
    }

    @Test
    fun `span start timestamp derives from createdAtEpochMs and falls back when zero`() {
        val session = VizSession(sessionId = "s-ts")
        newExporterOn(session)

        val epochMs = 1_700_000_000_000L // a fixed 2023 wall-clock instant
        session.send(created(session, "A", createdAtEpochMs = epochMs))
        sendAndAwaitEnded(session, completed(session, "A"), expectedEnded = 1)
        val withEpoch = assertNotNull(spanByCoroutineId("A"))
        assertEquals(
            Instant.ofEpochMilli(epochMs).toEpochMilli(),
            Instant.ofEpochSecond(0, withEpoch.startEpochNanos).toEpochMilli(),
            "start timestamp == createdAtEpochMs (NOT tsNanos / 1970)",
        )

        // createdAtEpochMs == 0L falls back to ~now (Pitfall 5) — not 1970.
        val before = System.currentTimeMillis()
        session.send(created(session, "B", createdAtEpochMs = 0L))
        sendAndAwaitEnded(session, completed(session, "B"), expectedEnded = 2)
        val fallback = assertNotNull(spanByCoroutineId("B"))
        val startMs = Instant.ofEpochSecond(0, fallback.startEpochNanos).toEpochMilli()
        assertTrue(startMs >= before - CLOCK_SKEW_MS, "0L fell back to now(), not the epoch origin")
        assertNull(
            fallback.attributes
                .asMap()
                .entries
                .firstOrNull { it.key.key == "coroutine.leaked" }
                ?.value,
            "a normally-completed span is not flagged leaked",
        )
        session.close()
    }

    companion object {
        private const val DEFAULT_EPOCH_MS = 1_700_000_000_000L
        private const val SUBSCRIBE_TIMEOUT_MS = 2_000L
        private const val AWAIT_MS = 2_000L
        private const val POLL_MS = 5L
        private const val CLOCK_SKEW_MS = 60_000L
    }
}
