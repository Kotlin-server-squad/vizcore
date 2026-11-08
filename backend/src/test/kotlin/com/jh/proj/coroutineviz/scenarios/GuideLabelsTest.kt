package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineBodyCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Each "How it works" page draws the session tree as it will look at one moment of its demo
 * ("What you will see"). That picture lives in the frontend's `will-see.json`, written by hand.
 * This test runs every demo pictured there and replays its events: at some point of the run the
 * tree must be exactly the picture (the same coroutines, each at its depth and in its state), and
 * that point must lie within [TOLERANCE_SECONDS] of the pictured moment (`atSeconds`, scripted
 * time). So renaming, moving or retiming a coroutine in a demo fails here until its guide follows.
 * A coroutine waiting at a channel, a mutex or a semaphore is recorded as suspended, so a picture
 * may show one waiting: it is checked like any other state.
 *
 * Matching a point of the event sequence, rather than the tree at one timestamp, keeps the test
 * steady: at test speed a long chain of steps drifts a few tenths of a second behind its script.
 */
class GuideLabelsTest {
    /** One coroutine as the tree shows it: under which parent (null for the root), how deep, in which state. */
    private data class Row(
        val parent: String?,
        val depth: Int,
        val state: String,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("picturedDemos")
    fun `a guide pictures the tree of a real moment of its demo, at about the time it names`(demoId: String) {
        val timeline = timeline(demoId)
        val events = timeline.map { it.first }
        val picture = pictured(demoId)
        val at = atSeconds(demoId)

        // The scripted seconds at which the tree equals the picture, as [from, to) windows.
        val created = events.filterIsInstance<CoroutineCreated>().associateBy { it.coroutineId }
        val tree = mutableMapOf<String, Row>()
        val windows = mutableListOf<ClosedFloatingPointRange<Double>>()
        var matchingSince: Double? = null
        events.forEachIndexed { i, event ->
            apply(event, created, tree)
            val now = timeline[i].second
            // The tree after the last event stays on screen.
            val next = timeline.getOrNull(i + 1)?.second ?: Double.POSITIVE_INFINITY
            if (tree == picture) {
                if (matchingSince == null) matchingSince = now
                if (next > now) {
                    // A flash between two events (a step resuming and suspending again) is no moment.
                    if (next - matchingSince!! >= MIN_VISIBLE_SECONDS) windows += matchingSince!!..next
                    matchingSince = null
                }
            } else {
                matchingSince = null
            }
        }

        assertTrue(
            windows.isNotEmpty(),
            "$demoId: the tree never shows the picture for a moment; at $at s it is ${treeAt(timeline, at)}",
        )
        assertTrue(
            windows.any { at in (it.start - TOLERANCE_SECONDS)..(it.endInclusive + TOLERANCE_SECONDS) },
            "$demoId: the picture shows the tree at ${windows.map { "%.1f-%.1f s".format(it.start, it.endInclusive) }}, not at about $at s",
        )
    }

    private fun scripted(nanos: Long): Double = nanos / 1_000_000_000.0 / SCALE

    /** The tree after every event up to [at] scripted seconds, for the failure message. */
    private fun treeAt(
        timeline: List<Pair<VizEvent, Double>>,
        at: Double,
    ): Map<String, Row> {
        val created = timeline.map { it.first }.filterIsInstance<CoroutineCreated>().associateBy { it.coroutineId }
        val tree = mutableMapOf<String, Row>()
        timeline.filter { it.second <= at }.forEach { apply(it.first, created, tree) }
        return tree
    }

    /**
     * The demo's events in order, each with the scripted second it was sent at. A demo whose picture
     * depends on which coroutine gets a lock first runs on virtual time, where that order is exact;
     * the others run on real time at [SCALE].
     */
    private fun timeline(demoId: String): List<Pair<VizEvent, Double>> {
        if (demoId in VIRTUAL_TIME_DEMOS) {
            var run: VirtualDemoRun? = null
            runTest { run = runOnVirtualTime("guide-$demoId") { session, context -> startOnVirtualTime(demoId, session, context) } }
            val done = assertNotNull(run)
            return done.events.map { it to done.at(it) / 1000.0 }
        }
        val events = run(demoId).sortedBy { it.seq }
        val start = events.minOf { it.tsNanos }
        return events.map { it to scripted(it.tsNanos - start) }
    }

    private suspend fun startOnVirtualTime(
        demoId: String,
        session: VizSession,
        context: CoroutineContext,
    ): Job =
        when (demoId) {
            "sync/mutex/counter" -> SyncScenarios.runCounter(session, context)
            "sync/mutex/bank-transfer" -> SyncScenarios.runBankTransfer(session, context)
            "sync/mutex/cache" -> SyncScenarios.runCache(session, context)
            "sync/mutex/deadlock-demo" -> SyncScenarios.runDeadlock(session, context)
            "sync/semaphore/connection-pool" -> SyncScenarios.runConnectionPool(session, context)
            "sync/semaphore/rate-limiter" -> SyncScenarios.runRateLimiter(session, context)
            "sync/semaphore/file-processor" -> SyncScenarios.runFileProcessor(session, context)
            "sync/semaphore/resource-timeout" -> SyncScenarios.runResourceTimeout(session, context)
            "sync/semaphore/producer-consumer" -> SyncScenarios.runProducerConsumer(session, context)
            "sync/combined/ecommerce" -> SyncScenarios.runOrderProcessing(session, context)
            "order-processing" -> RealLifeScenarios.runOrderProcessing(session, context)
            "user-registration" -> RealLifeScenarios.runUserRegistration(session, context)
            "report-generation" -> RealLifeScenarios.runReportGeneration(session, context)
            else -> error("$demoId does not run on virtual time")
        }

    /** Applies one event to [tree] (label to depth and state), as the session view would show it. */
    private fun apply(
        event: VizEvent,
        created: Map<String, CoroutineCreated>,
        tree: MutableMap<String, Row>,
    ) {
        fun depth(c: CoroutineCreated): Int = c.parentCoroutineId?.let(created::get)?.let { depth(it) + 1 } ?: 0
        val state =
            when (event) {
                is CoroutineCreated -> "CREATED"
                is CoroutineStarted, is CoroutineResumed -> "ACTIVE"
                is CoroutineSuspended -> "SUSPENDED"
                is CoroutineBodyCompleted -> "WAITING_FOR_CHILDREN"
                is CoroutineCompleted -> "COMPLETED"
                is CoroutineCancelled -> "CANCELLED"
                is CoroutineFailed -> "FAILED"
                else -> null
            }
        val coroutine = (event as? CoroutineEvent)?.let { created[it.coroutineId] }
        val label = coroutine?.label
        if (state != null && label != null) {
            tree[label] = Row(coroutine.parentCoroutineId?.let(created::get)?.label, depth(coroutine), state)
        }
    }

    private fun run(demoId: String): List<VizEvent> =
        runBlocking {
            val session = VizSession("guide-$demoId")
            try {
                val run = assertNotNull(DemoRuns(maxConcurrent = 0, timeScale = SCALE).tryStart())
                withTimeout(20.seconds) {
                    withContext(run) { start(demoId, session) }.join()
                    // A parent's terminal event can follow its job's completion by a moment.
                    while (session.store.all().count { it.isTerminal() } < session.store.all().count { it is CoroutineCreated }) {
                        delay(5)
                    }
                }
                session.store.all()
            } finally {
                session.close()
            }
        }

    private fun VizEvent.isTerminal() = this is CoroutineCompleted || this is CoroutineCancelled || this is CoroutineFailed

    private suspend fun start(
        demoId: String,
        session: VizSession,
    ): Job =
        when (demoId) {
            "nested" -> BasicScenarios.runNestedCoroutines(session)
            "parallel" -> BasicScenarios.runParallelExecution(session)
            "cancellation" -> BasicScenarios.runCancellationScenario(session)
            "deep-nesting" -> BasicScenarios.runDeepNesting(session, 5)
            "mixed" -> BasicScenarios.runMixedScenario(session)
            "exception" -> BasicScenarios.runExceptionScenario(session)
            "dispatcher" -> BasicScenarios.runDispatcherScenario(session)
            "channel-rendezvous" -> ChannelScenarios.runRendezvous(session)
            "channel-buffered" -> ChannelScenarios.runBuffered(session)
            "channel-fan-out" -> ChannelScenarios.runFanOut(session)
            "flow/simple" -> FlowScenarios.simpleFlow(session)
            "flow/operators" -> FlowScenarios.flowOperators(session)
            "flow/stateflow" -> FlowScenarios.stateFlowDemo(session)
            "flow/sharedflow" -> FlowScenarios.sharedFlowDemo(session)
            else -> startPattern(demoId, session)
        }

    private suspend fun startPattern(
        demoId: String,
        session: VizSession,
    ): Job =
        when (demoId) {
            "patterns/retry" -> PatternScenarios.runRetry(session)
            "patterns/producer-consumer" -> PatternScenarios.runProducerConsumer(session)
            "patterns/fan-out-fan-in" -> PatternScenarios.runFanOutFanIn(session)
            "patterns/supervisor" -> PatternScenarios.runSupervisor(session)
            "patterns/circuit-breaker" -> PatternScenarios.runCircuitBreaker(session)
            else -> error("will-see.json pictures '$demoId'; add it to GuideLabelsTest.start")
        }

    companion object {
        /**
         * The demos whose picture is checked on virtual time: who gets a lock or a permit first decides their tree, or
         * (the Real life demos) a long chain of joined steps would drift on real time.
         */
        private val VIRTUAL_TIME_DEMOS =
            setOf(
                "sync/mutex/counter",
                "sync/mutex/bank-transfer",
                "sync/mutex/cache",
                "sync/mutex/deadlock-demo",
                "sync/semaphore/connection-pool",
                "sync/semaphore/rate-limiter",
                "sync/semaphore/file-processor",
                "sync/semaphore/resource-timeout",
                "sync/semaphore/producer-consumer",
                "sync/combined/ecommerce",
                "order-processing",
                "user-registration",
                "report-generation",
            )

        /** A tenth of scripted speed: each demo takes 1-2 s here. */
        private const val SCALE = 0.1

        /** How far, in scripted seconds, the pictured moment may lie from where the tree matches. */
        private const val TOLERANCE_SECONDS = 1.0

        /** How long, in scripted seconds, the tree must keep the pictured shape to count as a moment a person sees. */
        private const val MIN_VISIBLE_SECONDS = 0.2

        /** Declared as a test input in build.gradle.kts, so editing it re-runs this test. */
        private val pictures: JsonObject by lazy {
            val file = File("../frontend/src/content/gallery/will-see.json")
            check(file.exists()) { "missing ${file.absolutePath}" }
            Json.parseToJsonElement(file.readText()).jsonObject
        }

        private fun atSeconds(demoId: String): Double = pictures[demoId]!!.jsonObject["atSeconds"]!!.jsonPrimitive.double

        /**
         * The picture as the page draws it: rows in tree order, so a row's parent is the nearest
         * row above it that is one level shallower.
         */
        private fun pictured(demoId: String): Map<String, Row> {
            val path = mutableListOf<String>()
            return pictures[demoId]!!.jsonObject["coroutines"]!!.jsonArray.associate {
                val row = it.jsonObject
                val label = row["label"]!!.jsonPrimitive.content
                val depth = row["depth"]!!.jsonPrimitive.int
                while (path.size > depth) path.removeLast()
                val parent = path.lastOrNull()
                path += label
                label to Row(parent, depth, row["state"]!!.jsonPrimitive.content)
            }
        }

        @JvmStatic
        fun picturedDemos(): List<String> = pictures.keys.toList()
    }
}
