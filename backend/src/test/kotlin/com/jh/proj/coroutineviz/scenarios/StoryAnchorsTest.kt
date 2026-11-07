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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Story mode (spec S-9, "Story anchors") moves the session page to the moment each guide chapter
 * describes: the anchor, the nth lifecycle event of one coroutine. The anchors and the states the
 * tree shows there live in the frontend's `story-anchors.json`, written by hand. This test runs
 * every demo listed there and proves each anchor: the event happens, and at that event the tree
 * has every expected state. So a demo that changes fails here until its story follows.
 *
 * An expected state must also be certain in a real run, not only in this one (plan P-6). Here
 * the Basics run on virtual time, where two coroutines that wake at the same scripted instant run
 * in a fixed order; a real run may run them the other way round. So a state counts only when it
 * is set by the anchor event itself, is a descendant's end at an ancestor's end (vizcore emits a
 * parent's terminal event after its children's), or holds from [MARGIN_SECONDS] before the anchor
 * to [MARGIN_SECONDS] after it.
 */
class StoryAnchorsTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("guidedDemos")
    fun `every chapter's anchor happens, and the tree then shows what its guide expects`(demoId: String) {
        val run = run(demoId)
        val problems = check(run.timeline, steps(demoId), run.offClock)
        assertTrue(problems.isEmpty(), "$demoId:\n" + problems.joinToString("\n"))
    }

    // --- the certainty rule (P-6) on hand-made runs ---

    @Test
    fun `a state that changes just after the anchor is not certain`() {
        val run =
            listOf(
                created("a") to 0.0,
                started("a") to 0.0,
                created("b") to 0.0,
                started("b") to 0.0,
                suspended("b") to 1.0,
                // the anchor
                suspended("a") to 2.0,
                resumed("b") to 2.1,
            )
        val problems = check(run, listOf(Step(Anchor("CoroutineSuspended", "a", 1), mapOf("b" to "SUSPENDED"))))
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("b changes state"), problems.single())
    }

    @Test
    fun `the anchor's own state and a child's end at its parent's end are certain`() {
        val run =
            listOf(
                created("parent") to 0.0,
                started("parent") to 0.0,
                created("child", parent = "parent") to 0.0,
                started("child") to 0.0,
                completedEvent("child") to 3.0,
                completedEvent("parent") to 3.0,
            )
        val steps = listOf(Step(Anchor("CoroutineCompleted", "parent", 1), mapOf("parent" to "COMPLETED", "child" to "COMPLETED")))
        assertEquals(emptyList(), check(run, steps))
    }

    @Test
    fun `a missing anchor, a wrong state and a step back in time are reported`() {
        val run =
            listOf(
                created("a") to 0.0,
                started("a") to 0.0,
                suspended("a") to 1.0,
                resumed("a") to 2.0,
            )
        val problems =
            check(
                run,
                listOf(
                    Step(Anchor("CoroutineResumed", "a", 1), mapOf("a" to "SUSPENDED")),
                    Step(Anchor("CoroutineSuspended", "a", 1), emptyMap()),
                    Step(Anchor("CoroutineSuspended", "a", 2), emptyMap()),
                ),
            )
        assertEquals(3, problems.size, problems.toString())
        assertTrue(problems[0].contains("is ACTIVE"), problems[0])
        assertTrue(problems[1].contains("comes before the chapter above"), problems[1])
        assertTrue(problems[2].contains("never happens"), problems[2])
    }

    @Test
    fun `two coroutines' moments at one instant are not ordered, and a time off the clock proves nothing`() {
        val offClockResume = resumed("a")
        val run =
            listOf(
                created("a") to 0.0,
                started("a") to 0.0,
                created("b") to 0.0,
                started("b") to 0.0,
                suspended("a") to 1.0,
                suspended("b") to 1.0,
                offClockResume to 3.0,
            )
        val ordering =
            check(
                run,
                listOf(Step(Anchor("CoroutineSuspended", "a", 1), emptyMap()), Step(Anchor("CoroutineSuspended", "b", 1), emptyMap())),
            )
        assertEquals(1, ordering.size, ordering.toString())
        assertTrue(ordering.single().contains("may come before the chapter above's in a real run"), ordering.single())
        val offClock =
            check(run, listOf(Step(Anchor("CoroutineResumed", "a", 1), mapOf("b" to "SUSPENDED"))), offClock = setOf(offClockResume.seq))
        assertEquals(1, offClock.size, offClock.toString())
        assertEquals(emptyList(), check(run, listOf(Step(Anchor("CoroutineResumed", "a", 1), mapOf("b" to "SUSPENDED")))))
    }

    // --- the check ---

    private data class Anchor(
        val event: String,
        val coroutine: String,
        val occurrence: Int,
    )

    private data class Step(
        val anchor: Anchor?,
        val expect: Map<String, String>,
    )

    /** A chapter's moment in one run: its anchor, the anchor event's index and its coroutine's id. */
    private data class Moment(
        val anchor: Anchor,
        val index: Int,
        val anchorId: String,
    )

    /**
     * What is wrong with [steps] on this run: [timeline] holds its events in seq order, each with its
     * scripted second; [offClock] the seqs whose second is not reliable (see [VirtualDemoRun.offClock]).
     */
    private fun check(
        timeline: List<Pair<VizEvent, Double>>,
        steps: List<Step>,
        offClock: Set<Long> = emptySet(),
    ): List<String> {
        val judge = Judge(timeline, offClock)
        val problems = mutableListOf<String>()
        var previous: Pair<Int, String>? = null
        steps.forEachIndexed { stepIndex, step ->
            val anchor = step.anchor ?: return@forEachIndexed
            val chapter = "chapter ${stepIndex + 1}"
            val id = judge.idOf(anchor.coroutine, problems, chapter) ?: return@forEachIndexed
            val index = judge.indexOf(anchor, id)
            if (index == null) {
                problems += "$chapter: ${anchor.coroutine}'s ${anchor.event} #${anchor.occurrence} never happens"
                return@forEachIndexed
            }
            previous?.let { (before, beforeId) -> judge.ordering(before, beforeId, index, id)?.let { problems += "$chapter: $it" } }
            previous = index to id
            val tree = judge.treeAfter(index)
            for ((label, expected) in step.expect) {
                val other = judge.idOf(label, problems, chapter) ?: continue
                judge.stateProblem(Moment(anchor, index, id), label, other, tree[other], expected)?.let { problems += "$chapter: $it" }
            }
        }
        return problems
    }

    /** The facts of one run the check judges by. */
    private inner class Judge(
        private val timeline: List<Pair<VizEvent, Double>>,
        private val offClock: Set<Long>,
    ) {
        private val events = timeline.map { it.first }
        private val created = events.filterIsInstance<CoroutineCreated>()
        private val byId = created.associateBy { it.coroutineId }

        fun idOf(
            label: String,
            problems: MutableList<String>,
            chapter: String,
        ): String? {
            val matches = created.filter { it.label == label }.map { it.coroutineId }.distinct()
            if (matches.size != 1) problems += "$chapter: '$label' names ${matches.size} coroutines, not one"
            return matches.singleOrNull()
        }

        fun indexOf(
            anchor: Anchor,
            id: String,
        ): Int? =
            events.indices
                .filter { lifecycleName(events[it]) == anchor.event && (events[it] as CoroutineEvent).coroutineId == id }
                .getOrNull(anchor.occurrence - 1)

        /**
         * Why the anchor at event [index] may not follow the one at [before] in a real run, or null when
         * it does: the same event, a later event of the same coroutine, an ancestor's end (which follows
         * every event of its descendants), or a later one at least [MARGIN_SECONDS] afterwards (both on
         * the clock).
         */
        fun ordering(
            before: Int,
            beforeId: String,
            index: Int,
            id: String,
        ): String? {
            val gap = timeline[index].second - timeline[before].second
            val onClock = events[index].seq !in offClock && events[before].seq !in offClock
            return when {
                index < before -> "its moment comes before the chapter above's"
                index == before || id == beforeId -> null
                events[index].isTerminal() && isDescendant(beforeId, id) -> null
                onClock && gap >= MARGIN_SECONDS - EPSILON -> null
                else -> "its moment may come before the chapter above's in a real run (%.2f s apart)".format(gap)
            }
        }

        /** What is wrong with [label]'s state at the anchor, or null when it is the expected one and certain. */
        fun stateProblem(
            moment: Moment,
            label: String,
            id: String,
            actual: String?,
            expected: String,
        ): String? =
            when {
                actual != expected ->
                    "at ${moment.anchor.coroutine}'s ${moment.anchor.event} #${moment.anchor.occurrence}, $label is $actual, " +
                        "the guide expects $expected"
                !certain(id, moment.anchorId, moment.index) ->
                    "$label changes state within $MARGIN_SECONDS s of the anchor (%.2f s), so a real run could show it differently"
                        .format(timeline[moment.index].second)
                else -> null
            }

        /** Whether [id]'s state at the anchor (event [index]) is the same in every real run (plan P-6). */
        private fun certain(
            id: String,
            anchorId: String,
            index: Int,
        ): Boolean {
            val anchorEvent = events[index]
            val transitions =
                timeline.withIndex().filter { (_, entry) ->
                    stateOf(entry.first) != null &&
                        (entry.first as CoroutineEvent).coroutineId == id
                }
            val set = transitions.lastOrNull { it.index <= index }?.value
            val next = transitions.firstOrNull { it.index > index }?.value
            val at = timeline[index].second
            // A judgement by time needs every time it compares to be on the clock.
            val onClock = listOfNotNull(anchorEvent, set?.first, next?.first).none { it.seq in offClock }
            val stable =
                set != null &&
                    onClock &&
                    at - set.second >= MARGIN_SECONDS - EPSILON &&
                    (next == null || next.second - at >= MARGIN_SECONDS - EPSILON)
            return id == anchorId || (anchorEvent.isTerminal() && isDescendant(id, anchorId)) || stable
        }

        private fun isDescendant(
            id: String,
            ancestorId: String,
        ): Boolean = generateSequence(byId[id]?.parentCoroutineId) { byId[it]?.parentCoroutineId }.any { it == ancestorId }

        /** Coroutine id to state after events 0..[index], as the page's `projectCoroutines` folds them. */
        fun treeAfter(index: Int): Map<String, String> {
            val tree = mutableMapOf<String, String>()
            for (event in events.subList(0, index + 1)) {
                val state = stateOf(event) ?: continue
                val id = (event as CoroutineEvent).coroutineId
                // Like the page: a lifecycle event of a coroutine never created changes nothing.
                if (event is CoroutineCreated || id in tree) tree[id] = state
            }
            return tree
        }
    }

    private fun stateOf(event: VizEvent): String? =
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

    /** The event's wire name, when it is one of the eight lifecycle events an anchor may name. */
    private fun lifecycleName(event: VizEvent): String? = stateOf(event)?.let { event::class.simpleName }

    private fun VizEvent.isTerminal() = this is CoroutineCompleted || this is CoroutineCancelled || this is CoroutineFailed

    // --- running the demos ---

    /** One run of a demo: its events in seq order, each with the scripted second it was sent at, and the seqs off the clock. */
    private class Run(
        val timeline: List<Pair<VizEvent, Double>>,
        val offClock: Set<Long>,
    )

    private fun run(demoId: String): Run {
        val virtual = VIRTUAL_TIME_DEMOS[demoId]
        if (virtual != null) {
            var run: VirtualDemoRun? = null
            runTest { run = runOnVirtualTime("story-$demoId", virtual) }
            val done = assertNotNull(run)
            return Run(done.events.map { it to done.at(it) / 1000.0 }, done.offClock)
        }
        val events = runOnRealTime(demoId).sortedBy { it.seq }
        val start = events.minOf { it.tsNanos }
        return Run(events.map { it to (it.tsNanos - start) / 1_000_000_000.0 / REAL_TIME_SCALE }, emptySet())
    }

    private fun runOnRealTime(demoId: String): List<VizEvent> =
        runBlocking {
            val session = VizSession("story-$demoId")
            try {
                val run = assertNotNull(DemoRuns(maxConcurrent = 0, timeScale = REAL_TIME_SCALE).tryStart())
                withTimeout(30.seconds) {
                    withContext(run) { startOnRealTime(demoId, session) }.join()
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

    private suspend fun startOnRealTime(
        demoId: String,
        session: VizSession,
    ): Job =
        when (demoId) {
            // Real Dispatchers.IO and Default: no virtual time here.
            "dispatcher" -> BasicScenarios.runDispatcherScenario(session)
            else -> error("story-anchors.json has a story for '$demoId'; add it to StoryAnchorsTest")
        }

    // Tiny events for the rule's own tests.
    private var seq = 0L

    private fun created(
        label: String,
        parent: String? = null,
    ) = CoroutineCreated("s", ++seq, 0, label, "j-$label", parent, "scope", label)

    private fun started(id: String) = CoroutineStarted("s", ++seq, 0, id, "j-$id", null, "scope", id)

    private fun suspended(id: String) = CoroutineSuspended("s", ++seq, 0, id, "j-$id", null, "scope", id, "delay")

    private fun resumed(id: String) = CoroutineResumed("s", ++seq, 0, id, "j-$id", null, "scope", id)

    private fun completedEvent(id: String) = CoroutineCompleted("s", ++seq, 0, id, "j-$id", null, "scope", id)

    companion object {
        /** The Basics that run on virtual time; Dispatcher needs real dispatchers ([startOnRealTime]). */
        private val VIRTUAL_TIME_DEMOS: Map<String, suspend (VizSession, CoroutineContext) -> Job> =
            mapOf(
                "nested" to { session, context -> BasicScenarios.runNestedCoroutines(session, context) },
                "parallel" to { session, context -> BasicScenarios.runParallelExecution(session, context) },
                "cancellation" to { session, context -> BasicScenarios.runCancellationScenario(session, context) },
                "deep-nesting" to { session, context -> BasicScenarios.runDeepNesting(session, 5, context) },
                "mixed" to { session, context -> BasicScenarios.runMixedScenario(session, context) },
                "exception" to { session, context -> BasicScenarios.runExceptionScenario(session, context) },
            )

        /** Half of scripted speed: Dispatcher takes about 6 s and keeps 125 ms of real margin. */
        private const val REAL_TIME_SCALE = 0.5

        /** How long, in scripted seconds, an expected state must hold on each side of the anchor (P-6). */
        private const val MARGIN_SECONDS = 0.25

        private const val EPSILON = 1e-9

        /** Declared as a test input in build.gradle.kts, so editing it re-runs this test. */
        private val stories: JsonObject by lazy {
            val file = File("../frontend/src/content/gallery/story-anchors.json")
            check(file.exists()) { "missing ${file.absolutePath}" }
            Json.parseToJsonElement(file.readText()).jsonObject
        }

        private fun steps(demoId: String): List<Step> =
            stories[demoId]!!.jsonArray.map { element ->
                val entry = element.jsonObject
                val anchor =
                    entry["anchor"]?.jsonObject?.let {
                        Anchor(
                            it["event"]!!.jsonPrimitive.content,
                            it["coroutine"]!!.jsonPrimitive.content,
                            it["occurrence"]!!.jsonPrimitive.int,
                        )
                    }
                val expect = entry["expect"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
                Step(anchor, expect)
            }

        @JvmStatic
        fun guidedDemos(): List<String> = stories.keys.toList()
    }
}
