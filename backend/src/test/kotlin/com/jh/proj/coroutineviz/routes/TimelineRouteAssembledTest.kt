package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Assembled-app (Ktor Test Host) acceptance gate for CR-01 (08.4).
 *
 * This is the load-bearing proof that the same-FQN duplicate-DTO shadowing hazard is gone.
 * It boots the REAL `:backend` [module] over the assembled runtime classpath (the only test
 * shape that can observe the collision — module-isolated tests provably cannot, because each
 * module's compiler shadows the dependency copy with its own local source, per RESEARCH.md
 * Pitfall 1). Before the 08.4 deletion, the stale `:backend` `TimelineEventSummary` (lacking
 * the `suspensionPoint` ctor param) could win the classloader race and produce the 08.3 live
 * HTTP 500 (`NoSuchMethodError`).
 *
 * The test drives a real session through Created -> Started -> Suspended (the Suspended carrying
 * a real [SuspensionPoint] with a concrete `file:line`), polls the timeline route until the async
 * projection converges, then asserts the route serves a `coroutine.suspended` event whose
 * `suspensionPoint.fileName`/`lineNumber` are populated — provable only if the CORE copy (with the
 * `suspensionPoint` field) is the one loaded.
 */
class TimelineRouteAssembledTest {
    private var seq = 0L

    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
    }

    private fun ApplicationTestBuilder.jsonClient() =
        createClient {
            install(ContentNegotiation) {
                json(appJson)
            }
        }

    /**
     * Drive the real coroutine lifecycle Created -> Started -> Suspended on [session], where the
     * Suspended carries a real [SuspensionPoint] with a concrete file:line (mirrors the live demo
     * frame). Then poll the IN-PROCESS projection until it observes the suspended event — polling
     * the rate-limited HTTP route in a tight loop would trip the per-IP rate limiter (SHAR-02).
     */
    private suspend fun driveSuspendedCoroutine(
        session: VizSession,
        coroutineId: String,
    ) {
        // The EventBus is replay=0: an event sent before the ProjectionService collector has
        // subscribed has no live receiver and is lost. Deterministically await a live subscriber
        // (the projection's collect launched in ProjectionService.init) before emitting — this
        // closes the startup race without sleeping (EventBus.subscriptionCount contract).
        session.eventBus.subscriptionCount.first { it >= 1 }

        session.send(
            CoroutineCreated(
                sessionId = session.sessionId,
                seq = ++seq,
                tsNanos = 0L,
                coroutineId = coroutineId,
                jobId = "job-$coroutineId",
                parentCoroutineId = null,
                scopeId = "io",
                label = coroutineId,
            ),
        )
        session.send(
            CoroutineStarted(
                sessionId = session.sessionId,
                seq = ++seq,
                tsNanos = 1L,
                coroutineId = coroutineId,
                jobId = "job-$coroutineId",
                parentCoroutineId = null,
                scopeId = "io",
                label = coroutineId,
            ),
        )
        session.send(
            CoroutineSuspended(
                sessionId = session.sessionId,
                seq = ++seq,
                tsNanos = 2L,
                coroutineId = coroutineId,
                jobId = "job-$coroutineId",
                parentCoroutineId = null,
                scopeId = "io",
                label = coroutineId,
                reason = "delay",
                suspensionPoint =
                    SuspensionPoint(
                        function = "handleRequest",
                        fileName = "SpringVizcoreDemoApplication.kt",
                        lineNumber = 75,
                        reason = "delay",
                    ),
            ),
        )

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val observed = session.projectionService.getCoroutineTimeline(coroutineId)?.events
            if (observed != null && observed.any { it.kind == "coroutine.suspended" }) break
            Thread.sleep(10)
        }
    }

    @Test
    fun `assembled timeline route serves a real suspensionPoint frame from the core DTO (CR-01)`() =
        testApplication {
            application { module() }

            // Create the session through the SAME manager the assembled module() routes read from.
            val session = SessionManager.createSession("timeline-assembled-cr01")
            val coroutineId = "request-cr01"
            driveSuspendedCoroutine(session, coroutineId)

            val client = jsonClient()
            val response = client.get("/api/sessions/${session.sessionId}/coroutines/$coroutineId/timeline")

            assertEquals(HttpStatusCode.OK, response.status, "assembled timeline route should return 200 OK")
            val body = response.bodyAsText()
            assertTrue(body.isNotBlank(), "timeline response body should not be blank")

            val timeline = appJson.parseToJsonElement(body).jsonObject
            val events = timeline["events"]?.jsonArray
            assertNotNull(events, "timeline payload must carry an 'events' array")

            // Load-bearing CR-01 assertion: the suspended event carries a REAL file:line via the
            // nested suspensionPoint field. This field exists ONLY on the core TimelineEventSummary;
            // if the stale :backend copy were loaded, the route would 500 (08.3) or omit the field.
            val suspended =
                events.single { it.jsonObject["kind"]?.jsonPrimitive?.content == "coroutine.suspended" }.jsonObject
            val suspensionPoint = suspended["suspensionPoint"]?.jsonObject
            assertNotNull(suspensionPoint, "the coroutine.suspended event must carry a suspensionPoint frame")
            assertEquals(
                "SpringVizcoreDemoApplication.kt",
                suspensionPoint["fileName"]?.jsonPrimitive?.content,
                "suspensionPoint must surface the real source file name",
            )
            val lineNumber = suspensionPoint["lineNumber"]?.jsonPrimitive?.content?.toIntOrNull()
            assertNotNull(lineNumber, "suspensionPoint must carry a line number, not null")
            assertTrue(lineNumber > 0, "suspensionPoint must carry a real line number, not a stub")
        }
}
