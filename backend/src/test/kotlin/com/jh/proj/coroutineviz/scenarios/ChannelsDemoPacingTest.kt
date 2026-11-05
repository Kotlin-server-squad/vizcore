package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.channel.ChannelBufferStateChanged
import com.jh.proj.coroutineviz.events.channel.ChannelReceiveCompleted
import com.jh.proj.coroutineviz.events.channel.ChannelReceiveStarted
import com.jh.proj.coroutineviz.events.channel.ChannelSendCompleted
import com.jh.proj.coroutineviz.events.channel.ChannelSendStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The Channels demos are paced so a person can follow them, as the Basics are
 * ([BasicsDemoPacingTest]): each runs 10-30 s in steps of 0.5-3 s, its coroutines start, wait and
 * end at different moments, and they are named after the story the demo tells. Before, each ended
 * within two seconds, so the hand-overs, the full buffer and the competing workers were over before
 * anyone saw them.
 *
 * The demos run here at [SCALE] of their scripted speed (`scenarios.timeScale`); every time below
 * is scaled back up to scripted time.
 */
class ChannelsDemoPacingTest {
    private class Run(
        val events: List<VizEvent>,
    ) {
        private val start = events.minOf { it.tsNanos }
        private val coroutines = events.filterIsInstance<CoroutineCreated>()
        private val idOf = coroutines.associate { it.label to it.coroutineId }

        val labels: Set<String> = coroutines.mapNotNull { it.label }.toSet()

        /** Scripted milliseconds from the demo's first event to [event]. */
        fun at(event: VizEvent): Double = (event.tsNanos - start) / 1_000_000.0 / SCALE

        val durationMillis: Double get() = events.maxOf { at(it) }

        inline fun <reified T : CoroutineEvent> of(label: String): T? = events.filterIsInstance<T>().firstOrNull { it.label == label }

        fun endedAt(label: String): Double {
            val end =
                of<CoroutineCompleted>(label) ?: of<CoroutineCancelled>(label) ?: of<CoroutineFailed>(label)
            return at(assertNotNull(end, "$label never ended"))
        }

        fun completed(label: String) = of<CoroutineCompleted>(label) != null

        private fun id(label: String) = assertNotNull(idOf[label], "no coroutine $label")

        /** Each send by [label] as (started, completed), in scripted ms, in order. */
        fun sendsBy(label: String): List<Pair<Double, Double>> {
            val id = id(label)
            val started = events.filterIsInstance<ChannelSendStarted>().filter { it.coroutineId == id }.sortedBy { it.seq }
            val completed = events.filterIsInstance<ChannelSendCompleted>().filter { it.coroutineId == id }.sortedBy { it.seq }
            return started.zip(completed) { s, c -> at(s) to at(c) }
        }

        /** Each receive by [label] that got a value, as (started, completed), in scripted ms, in order. */
        fun receivesBy(label: String): List<Pair<Double, Double>> {
            val id = id(label)
            val started = events.filterIsInstance<ChannelReceiveStarted>().filter { it.coroutineId == id }.sortedBy { it.seq }
            val completed = events.filterIsInstance<ChannelReceiveCompleted>().filter { it.coroutineId == id }.sortedBy { it.seq }
            return started.zip(completed) { s, c -> at(s) to at(c) }
        }

        /** The values [label] received, in order. */
        fun receivedValuesBy(label: String): List<String> {
            val id = id(label)
            return events
                .filterIsInstance<ChannelReceiveCompleted>()
                .filter { it.coroutineId == id }
                .sortedBy { it.seq }
                .map { it.valueDescription }
        }

        /** How long each value waited in the channel: from its send completing to its receive completing, in scripted ms. */
        fun waitedInChannel(): Map<String, Double> {
            val sent = events.filterIsInstance<ChannelSendCompleted>().associate { it.valueDescription to at(it) }
            return events
                .filterIsInstance<ChannelReceiveCompleted>()
                .associate { it.valueDescription to at(it) - assertNotNull(sent[it.valueDescription], "${it.valueDescription} never sent") }
        }

        /** How full the channel is after each change, in the order the session received them. */
        fun bufferSizes(): List<Int> = events.filterIsInstance<ChannelBufferStateChanged>().sortedBy { it.seq }.map { it.currentSize }
    }

    private suspend fun start(
        id: String,
        session: VizSession,
    ): Job =
        when (id) {
            "channel-rendezvous" -> ChannelScenarios.runRendezvous(session)
            "channel-buffered" -> ChannelScenarios.runBuffered(session)
            "channel-fan-out" -> ChannelScenarios.runFanOut(session)
            else -> error("not a Channels demo: $id")
        }

    /** Each demo runs once; the tests below read different things from the same run. */
    private fun run(id: String): Run = runs.getOrPut(id) { execute(id) }

    private fun execute(id: String): Run =
        runBlocking {
            val session = VizSession("pacing-$id")
            try {
                val run = assertNotNull(DemoRuns(maxConcurrent = 0, timeScale = SCALE).tryStart())
                withTimeout(20.seconds) {
                    withContext(run) { start(id, session) }.join()
                    // A parent's terminal event can follow its job's completion by a moment.
                    while (session.store.all().count { it.isTerminal() } < session.store.all().count { it is CoroutineCreated }) {
                        delay(5)
                    }
                }
                Run(session.store.all())
            } finally {
                session.close()
            }
        }

    private fun VizEvent.isTerminal() = this is CoroutineCompleted || this is CoroutineCancelled || this is CoroutineFailed

    @ParameterizedTest(name = "{0}")
    @MethodSource("channelIds")
    fun `each Channels demo runs for 10 to 30 seconds`(id: String) {
        val run = run(id)

        assertTrue(run.durationMillis in 10_000.0..30_000.0, "$id runs for ${run.durationMillis.roundToLong()} ms")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("channelIds")
    fun `each step of a Channels demo lasts 0,5 to 3 seconds`(id: String) {
        val steps =
            run(id)
                .events
                .filterIsInstance<CoroutineSuspended>()
                .filter { it.reason == "delay" }
                .map { "${it.label}" to (assertNotNull(it.durationMillis) / SCALE).roundToLong() }

        assertTrue(steps.size >= 5, "$id has only ${steps.size} steps")
        assertEquals(emptyList(), steps.filterNot { (_, millis) -> millis in 500..3_000 }, "$id steps outside 0.5-3 s")
    }

    /**
     * Events less than 0.2 s of scripted time apart belong to one moment. A demo the user can
     * follow has many such moments, and no single burst carries most of what it shows.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("channelIds")
    fun `a Channels demo shows its work at many separate moments`(id: String) {
        val run = run(id)
        val times = run.events.map { run.at(it) }.sorted()
        val moments =
            times.fold(mutableListOf<MutableList<Double>>()) { moments, t ->
                if (moments.isEmpty() || t - moments.last().last() >= 200.0) moments += mutableListOf(t) else moments.last() += t
                moments
            }

        assertTrue(moments.size >= 8, "$id shows its events at only ${moments.size} moments")
        val largest = moments.maxOf { it.size }
        assertTrue(largest <= times.size / 2, "$id shows $largest of its ${times.size} events at one moment")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("channelIds")
    fun `the coroutines of a Channels demo are named after its story`(id: String) {
        assertEquals(storyLabels.getValue(id), run(id).labels)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("channelIds")
    fun `a coroutine waiting at a channel is shown suspended until it goes on`(id: String) {
        val events = run(id).events
        val waits =
            events.filterIsInstance<CoroutineSuspended>().filter {
                it.waitKind == WaitKind.CHANNEL_SEND || it.waitKind == WaitKind.CHANNEL_RECEIVE
            }

        assertTrue(waits.isNotEmpty(), "$id records no wait at a channel")
        for (wait in waits) {
            val next =
                events.firstOrNull {
                    it.seq > wait.seq &&
                        (it is CoroutineResumed || it is CoroutineCompleted || it is CoroutineCancelled || it is CoroutineFailed) &&
                        (it as CoroutineEvent).coroutineId == wait.coroutineId
                }
            assertTrue(next is CoroutineResumed || next is CoroutineCancelled, "$id: ${wait.label} stays suspended at the channel")
        }
    }

    @Test
    fun `a plate changes hands only when the chef and the waiter are both at the pass`() {
        val run = run("channel-rendezvous")
        val sends = run.sendsBy("chef")
        val receives = run.receivesBy("waiter")

        assertEquals(5, sends.size, "the chef hands over five plates")
        assertEquals(5, receives.size, "the waiter takes five plates")
        val (firstAsked, firstGot) = receives.first()
        assertTrue(firstGot - firstAsked >= 1_000, "the waiter waits at the pass for the first plate, ${firstGot - firstAsked} ms")
        sends.drop(1).forEachIndexed { i, (started, completed) ->
            assertTrue(completed - started >= 1_000, "the chef waits at the pass with plate ${i + 2}, ${completed - started} ms")
        }
        // A hand-over needs both: the send and the receive of each plate are under way at the same
        // time (each starts before the other completes). Comparing intervals, not the instants the
        // two completions were recorded, keeps this steady however the threads are scheduled.
        sends.zip(receives).forEachIndexed { i, (send, receive) ->
            assertTrue(
                send.first <= receive.second && receive.first <= send.second,
                "plate ${i + 1}: sent ${send.first}-${send.second} ms, received ${receive.first}-${receive.second} ms",
            )
        }
        assertEquals(listOf("soup", "salad", "steak", "pasta", "dessert"), run.receivedValuesBy("waiter"), "the plates, in order")
        assertTrue(run.labels.all { run.completed(it) })
    }

    @Test
    fun `the print queue fills to 5, the office waits for a free slot, and the printer drains it`() {
        val run = run("channel-buffered")
        val sizes = run.bufferSizes()

        assertEquals(5, sizes.max(), "the queue fills up")
        assertEquals(0, sizes.last(), "the printer empties it")
        val waits = run.sendsBy("office").map { (started, completed) -> completed - started }
        assertEquals(8, waits.size, "the office sends eight jobs")
        assertTrue(waits.count { it >= 500 } >= 2, "the office waits for a free slot at least twice: $waits")
        assertTrue(run.waitedInChannel().getValue("job-2") >= 2_000, "a job waits in the queue while the printer is busy")
        assertTrue(run.endedAt("printer") - run.endedAt("office") >= 10_000, "the printer keeps printing long after the office is done")
        assertTrue(run.labels.all { run.completed(it) })
    }

    @Test
    fun `each ticket goes to exactly one agent, and all three agents work`() {
        val run = run("channel-fan-out")
        val byAgent = (1..3).associate { "agent-$it" to run.receivedValuesBy("agent-$it") }

        assertEquals((1..12).map { "ticket-$it" }.sorted(), byAgent.values.flatten().sorted(), "every ticket, exactly once")
        byAgent.forEach { (agent, tickets) -> assertTrue(tickets.size >= 2, "$agent handled only $tickets") }
        // A hand-over to an agent who is already waiting takes no time; a ticket that arrives while
        // all three are busy waits in the queue until one of them is free.
        val waited = run.waitedInChannel().filterValues { it >= 500 }
        assertTrue(waited.size >= 2, "tickets wait in the queue while all three agents are busy, waited: $waited")
        assertTrue(run.labels.all { run.completed(it) })
    }

    companion object {
        /** Well below 1 so the three demos take seconds here, well above 0 so their moments stay apart. */
        private const val SCALE = 0.1

        private val runs = ConcurrentHashMap<String, Run>()

        private val storyLabels =
            mapOf(
                "channel-rendezvous" to setOf("dinner-service", "chef", "waiter"),
                "channel-buffered" to setOf("office-printing", "office", "printer"),
                "channel-fan-out" to setOf("support-desk", "ticket-inbox", "agent-1", "agent-2", "agent-3"),
            )

        @JvmStatic
        fun channelIds(): List<String> = storyLabels.keys.toList()
    }
}
