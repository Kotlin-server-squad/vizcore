package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * Each "How it works" page draws the session tree as it will look at one moment of its demo
 * ("What you will see"). That picture lives in the frontend's `will-see.json`, written by hand.
 * This test runs every demo pictured there and checks that each coroutine in the picture is
 * really created, at the depth it is drawn at, so renaming or moving a coroutine in a demo fails
 * here until its guide is updated. States are not checked: they depend on the moment.
 */
class GuideLabelsTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("picturedDemos")
    fun `every coroutine a guide pictures is created at the depth it is drawn at`(demoId: String) {
        val created = run(demoId)
        val depthOf = depths(created)

        val wrong =
            pictured(demoId).filter { (label, depth) -> depthOf[label] != depth }.map { (label, depth) ->
                "$label: pictured at depth $depth, " + (depthOf[label]?.let { "created at depth $it" } ?: "never created")
            }
        assertEquals(emptyList(), wrong, "will-see.json does not match the $demoId demo")
    }

    /** Each coroutine's depth: 0 for the demo's root, 1 for its children, and so on. */
    private fun depths(created: List<CoroutineCreated>): Map<String?, Int> {
        val byId = created.associateBy { it.coroutineId }

        fun depth(event: CoroutineCreated): Int = event.parentCoroutineId?.let(byId::get)?.let { depth(it) + 1 } ?: 0
        return created.associate { it.label to depth(it) }
    }

    private fun run(demoId: String): List<CoroutineCreated> =
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
                session.store.all().filterIsInstance<CoroutineCreated>()
            } finally {
                session.close()
            }
        }

    private fun Any.isTerminal() = this is CoroutineCompleted || this is CoroutineCancelled || this is CoroutineFailed

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
            else -> error("will-see.json pictures '$demoId'; add it to GuideLabelsTest.start")
        }

    companion object {
        /** Fast enough for a quick test, slow enough that a demo's steps keep their order. */
        private const val SCALE = 0.02

        /** Declared as a test input in build.gradle.kts, so editing it re-runs this test. */
        private val pictures: JsonObject by lazy {
            val file = File("../frontend/src/content/gallery/will-see.json")
            check(file.exists()) { "missing ${file.absolutePath}" }
            Json.parseToJsonElement(file.readText()).jsonObject
        }

        private fun pictured(demoId: String): List<Pair<String, Int>> =
            pictures[demoId]!!.jsonObject["coroutines"]!!.jsonArray.map {
                val row = it.jsonObject
                row["label"]!!.jsonPrimitive.content to row["depth"]!!.jsonPrimitive.int
            }

        @JvmStatic
        fun picturedDemos(): List<String> = pictures.keys.toList()
    }
}
