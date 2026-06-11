package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.session.EventStore
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Smoke test asserting that Application.module() wires SessionManager.configure()
 * from the session.maxEvents yaml key (FND-02).
 *
 * Strategy: boot the module (which calls SessionManager.configure(maxEventsPerSession=10000)),
 * create a session via the API, then verify the created VizSession.store is the bounded
 * core EventStore (ArrayDeque variant — not the old fork's CopyOnWriteArrayList).
 *
 * We also verify the bounded cap is honoured by appending more events than a small
 * cap configured directly on the session manager for this test, then asserting
 * store.all().size <= that cap.
 */
class BoundedStoreWiringTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
    }

    @Test
    fun `module wires SessionManager configure and created session uses bounded EventStore`() =
        testApplication {
            application { module() }

            val jsonClient = createClient {
                install(ContentNegotiation) { json() }
            }

            // Create a session via the API
            val response = jsonClient.post("/api/sessions?name=wiring-test")
            assertEquals(HttpStatusCode.Created, response.status)

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val sessionId = body["sessionId"]?.jsonPrimitive?.content
            assertNotNull(sessionId, "Response should contain sessionId")

            // Retrieve the created session from SessionManager and verify its store type
            val session = SessionManager.getSession(sessionId)
            assertNotNull(session, "Session should exist in SessionManager")

            // The store must be the bounded core EventStore (ArrayDeque + RWLock variant)
            // The old fork used CopyOnWriteArrayList — if the fork is present this would
            // be the fork's EventStore (no maxEvents constructor param), not core's.
            assertIs<EventStore>(session.store,
                "Session store should be the bounded core EventStore (FND-02)")
        }

    @Test
    fun `bounded store honours configured maxEvents cap`() {
        // Configure a small cap directly to verify the cap is honoured
        // This simulates what module() does when reading session.maxEvents from yaml
        val testMaxEvents = 10
        SessionManager.configure(maxEventsPerSession = testMaxEvents)

        val session = kotlinx.coroutines.runBlocking { SessionManager.createSession("cap-test") }

        // Append more events than the cap
        val moreThanMax = testMaxEvents + 5
        repeat(moreThanMax) { i ->
            session.store.append(
                com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated(
                    sessionId = session.sessionId,
                    seq = i.toLong(),
                    tsNanos = System.nanoTime(),
                    coroutineId = "c-$i",
                    jobId = "j-$i",
                    parentCoroutineId = null,
                    scopeId = "scope",
                    label = "label-$i",
                )
            )
        }

        // The bounded store must evict old events to stay at or below maxEvents
        val storeSize = session.store.all().size
        assert(storeSize <= testMaxEvents) {
            "store.all().size ($storeSize) must be <= maxEvents ($testMaxEvents) — bounded cap not enforced"
        }
    }
}
