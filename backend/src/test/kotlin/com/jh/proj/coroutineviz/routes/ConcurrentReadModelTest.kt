package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.events.dispatcher.ThreadAssigned
import com.jh.proj.coroutineviz.models.CoroutineState
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #135: readers never iterate a collection the event pipeline is mutating. The session,
 * thread-activity and session-list endpoints (and the read models behind them) are served
 * while several writers ingest thousands of events into the same session. Every response must
 * be 2xx with a well-formed body; before the fix the handlers iterated the live snapshot map and
 * per-thread activity lists unlocked and could fail with a 500 mid-write.
 *
 * The HTTP test uses the real application module (auth off, in-memory storage), so requests go
 * through the per-IP "api" rate limit (60/min, ADR-029); its readers' request budget stays below
 * it. The in-process test hammers the read models directly with many more reads: the runtime
 * snapshot, the projected thread activity and hierarchy, and [SessionManager.listSessions].
 */
class ConcurrentReadModelTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
    }

    private fun events(
        sessionId: String,
        writer: Int,
        index: Int,
    ): List<VizEvent> {
        val id = "w$writer-c$index"
        val job = "job-$id"
        val scope = "scope-w$writer"
        val threadId = (writer * THREADS_PER_WRITER + index % THREADS_PER_WRITER).toLong()
        return listOf(
            CoroutineCreated(sessionId, 0, System.nanoTime(), id, job, null, scope, id),
            CoroutineStarted(sessionId, 0, System.nanoTime(), id, job, null, scope, id),
            ThreadAssigned(
                sessionId,
                0,
                System.nanoTime(),
                id,
                job,
                null,
                scope,
                id,
                threadId = threadId,
                threadName = "worker-$threadId",
                dispatcherName = "Dispatchers.Default",
            ),
            CoroutineSuspended(sessionId, 0, System.nanoTime(), id, job, null, scope, id, reason = "delay"),
            CoroutineResumed(sessionId, 0, System.nanoTime(), id, job, null, scope, id),
            CoroutineCompleted(sessionId, 0, System.nanoTime(), id, job, null, scope, id),
        )
    }

    @Test
    fun `session, threads and session list stay 2xx while events are ingested concurrently`() =
        testApplication {
            application { module() }

            val created = client.post("/api/sessions?name=concurrent-read-model")
            assertEquals(HttpStatusCode.Created, created.status)
            val sessionId =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["sessionId"]!!
                    .jsonPrimitive.content
            val session = assertNotNull(SessionManager.getSession(sessionId))

            val failures = ConcurrentLinkedQueue<String>()
            val responses = AtomicInteger()
            val paths = listOf("/api/sessions/$sessionId", "/api/sessions/$sessionId/threads", "/api/sessions")

            coroutineScope {
                val writers =
                    List(WRITERS) { w ->
                        launch(Dispatchers.Default) {
                            repeat(COROUTINES_PER_WRITER) { i ->
                                events(sessionId, w, i).forEach { session.ingest(it) }
                                // Pace the writers so ingestion spans the readers' requests.
                                if (i % PACE_EVERY == 0) delay(1)
                            }
                        }
                    }
                val readers =
                    paths.map { path ->
                        launch(Dispatchers.Default) {
                            repeat(REQUESTS_PER_READER) {
                                @Suppress("TooGenericExceptionCaught")
                                try {
                                    val response = client.get(path)
                                    val body = response.bodyAsText()
                                    if (!response.status.isSuccess()) {
                                        failures += "$path -> ${response.status}: ${body.take(MAX_BODY)}"
                                    } else {
                                        Json.parseToJsonElement(body)
                                        responses.incrementAndGet()
                                    }
                                } catch (t: Throwable) {
                                    failures += "$path threw $t"
                                }
                                delay(READ_INTERVAL_MS)
                            }
                        }
                    }
                withTimeout(TIMEOUT_MS) { (writers + readers).joinAll() }
            }

            assertTrue(failures.isEmpty(), "non-2xx or failed reads: ${failures.take(3)}")
            assertEquals(paths.size * REQUESTS_PER_READER, responses.get())

            // After ingestion the endpoints report every event.
            val expected = WRITERS * COROUTINES_PER_WRITER
            val snapshot = Json.parseToJsonElement(client.get("/api/sessions/$sessionId").bodyAsText()).jsonObject
            assertEquals(expected, snapshot["coroutineCount"]!!.jsonPrimitive.int)
            assertEquals(expected, snapshot["coroutines"]!!.jsonArray.size)
            assertEquals(expected * EVENTS_PER_COROUTINE, snapshot["eventCount"]!!.jsonPrimitive.int)
        }

    @Test
    fun `readers see consistent views while several writers ingest thousands of events`() =
        runBlocking {
            val session = SessionManager.createSession("concurrent-read-model")
            try {
                val failures = ConcurrentLinkedQueue<Throwable>()
                val reads = AtomicLong()
                val writing = AtomicBoolean(true)

                val readers =
                    List(READERS) { r ->
                        launch(Dispatchers.Default) {
                            while (writing.get()) {
                                try {
                                    readOnce(session, r)
                                    reads.incrementAndGet()
                                } catch (
                                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                                ) {
                                    failures += t
                                }
                                yield()
                            }
                        }
                    }

                val writers =
                    List(WRITERS) { w ->
                        launch(Dispatchers.Default) {
                            repeat(COROUTINES_PER_WRITER) { i ->
                                events(session.sessionId, w, i).forEach { session.ingest(it) }
                            }
                        }
                    }
                withTimeout(TIMEOUT_MS) { writers.joinAll() }

                // Let the bus subscriber drain into the projection, still under concurrent reads.
                val expected = WRITERS * COROUTINES_PER_WRITER
                withTimeout(TIMEOUT_MS) {
                    while (session.projectionService
                            .getThreadActivity()
                            .values
                            .sumOf { it.size } < expected
                    ) {
                        delay(POLL_MS)
                    }
                }
                writing.set(false)
                readers.joinAll()

                assertTrue(failures.isEmpty(), "reads threw: ${failures.take(3).map { it.toString() }}")
                assertTrue(reads.get() > 0, "readers never ran")

                assertFinalState(session, expected)
            } finally {
                SessionManager.deleteSession(session.sessionId)
            }
        }

    /** Every ingested event is accounted for once the writers and the projection are done. */
    private fun assertFinalState(
        session: VizSession,
        expected: Int,
    ) {
        assertEquals(expected * EVENTS_PER_COROUTINE, session.store.all().size)
        assertEquals(expected, session.snapshot.coroutineCount)
        val nodes = session.snapshot.nodes()
        assertEquals(expected, nodes.size)
        assertTrue(nodes.all { it.state == CoroutineState.COMPLETED }, "every coroutine completed")
        assertTrue(nodes.all { it.threadName != null }, "every coroutine has its thread")
        assertEquals(expected, session.projectionService.getHierarchyTree().size)
        assertEquals(WRITERS * THREADS_PER_WRITER, session.projectionService.getThreadActivity().size)
        val listed = SessionManager.listSessions().firstOrNull { it.sessionId == session.sessionId }
        if (listed != null) assertEquals(expected, listed.coroutineCount)
    }

    private fun readOnce(
        session: VizSession,
        reader: Int,
    ) {
        when (reader % READERS) {
            0 -> {
                val nodes = session.snapshot.nodes()
                check(nodes.all { it.id.isNotEmpty() })
            }
            1 -> {
                var active = 0
                for (node in session.snapshot.coroutines.values) {
                    if (node.state == CoroutineState.ACTIVE) active++
                }
                check(active >= 0)
                check(session.snapshot.coroutineCount >= 0)
            }
            2 -> {
                val activity = session.projectionService.getThreadActivity()
                check(activity.values.all { lane -> lane.all { it.threadName.isNotEmpty() } })
            }
            3 -> session.projectionService.getHierarchyTree()
            else -> SessionManager.listSessions()
        }
    }

    private companion object {
        const val WRITERS = 4
        const val COROUTINES_PER_WRITER = 300
        const val EVENTS_PER_COROUTINE = 6
        const val THREADS_PER_WRITER = 4
        const val PACE_EVERY = 10

        // 3 readers x 15 + the create and the final read stay under the 60/min "api" limit.
        const val REQUESTS_PER_READER = 15
        const val READ_INTERVAL_MS = 5L
        const val MAX_BODY = 200
        const val TIMEOUT_MS = 60_000L

        // In-process readers of the read models themselves (no HTTP, no rate limit).
        const val READERS = 5
        const val POLL_MS = 10L
    }
}
