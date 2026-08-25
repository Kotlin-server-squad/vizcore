package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the SSE egress edge concerns (Plan 03 Task 2):
 *  - `/stream` carries `X-Accel-Buffering: no` + `Cache-Control: no-cache`, set BEFORE the
 *    response commits (PERF-03, Pitfall P3),
 *  - `/stream` is NEVER gzipped even when the client offers `Accept-Encoding: gzip`
 *    (text/event-stream excluded; T-10-11), while a bulk JSON route MAY be,
 *  - `/metrics` exposes three independent drop counters: events.dropped (store),
 *    events.dropped.bus, events.dropped.sampling (D-11).
 */
class SseHeadersTest {
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
            install(ContentNegotiation) { json() }
        }

    @Test
    fun `stream carries anti-buffering headers set before commit`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val session = SessionManager.createSession("hdr-test")
            val sessionId = session.sessionId

            // The handler for an existing session never completes; stream incrementally and
            // assert the headers (committed at the first frame) on Dispatchers.Default real time.
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    client.prepareGet("/api/sessions/$sessionId/stream").execute { response ->
                        assertEquals(HttpStatusCode.OK, response.status)
                        assertEquals(
                            "no",
                            response.headers["X-Accel-Buffering"],
                            "PERF-03: X-Accel-Buffering: no must be present on /stream",
                        )
                        assertEquals(
                            "no-cache",
                            response.headers[HttpHeaders.CacheControl],
                            "PERF-03: Cache-Control: no-cache must be present on /stream",
                        )
                    }
                }
            }
        }

    @Test
    fun `stream is never gzipped even with Accept-Encoding gzip`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val session = SessionManager.createSession("gzip-test")
            val sessionId = session.sessionId

            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    client
                        .prepareGet("/api/sessions/$sessionId/stream") {
                            header(HttpHeaders.AcceptEncoding, "gzip")
                        }.execute { response ->
                            assertEquals(HttpStatusCode.OK, response.status)
                            assertNull(
                                response.headers[HttpHeaders.ContentEncoding],
                                "T-10-11: /stream must never carry Content-Encoding (text/event-stream excluded)",
                            )
                        }
                }
            }
        }

    @Test
    fun `metrics exposes three independent drop counters`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            // Touch a session so the per-session metric callbacks are wired.
            SessionManager.createSession("metrics-test")

            val body = client.get("/metrics").bodyAsText()
            assertTrue(
                body.contains("events_dropped_total"),
                "store-drop counter events.dropped must be present",
            )
            assertTrue(
                body.contains("events_dropped_bus_total"),
                "bus-drop counter events.dropped.bus must be present (D-11)",
            )
            assertTrue(
                body.contains("events_dropped_sampling_total"),
                "sampling/shed-drop counter events.dropped.sampling must be present (D-11)",
            )
        }

    @Test
    fun `bulk json route may be gzipped`() =
        testApplication {
            application { module() }
            val client = createClient { }

            val response =
                client.get("/api/scenarios") {
                    header(HttpHeaders.AcceptEncoding, "gzip")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val encoding = response.headers[HttpHeaders.ContentEncoding]
            assertNotNull(encoding, "a bulk JSON route may be gzipped")
            assertEquals("gzip", encoding)
        }
}
