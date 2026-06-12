package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetricsWiringTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
    }

    @Test
    fun `metrics endpoint exposes custom viz metrics at slash metrics`() =
        testApplication {
            application { module() }

            val response = client.get("/metrics")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            assertTrue(body.contains("viz_sessions_active"), "Should contain viz.sessions.active gauge")
        }

    @Test
    fun `sessions active gauge reflects session count`() =
        testApplication {
            application { module() }

            // Create a session to trigger the callback wiring
            val createClient =
                createClient {
                    install(ContentNegotiation) {
                        json()
                    }
                }
            createClient.post("/api/sessions?name=metrics-test")

            val response = client.get("/metrics")
            val body = response.bodyAsText()
            assertTrue(body.contains("viz_sessions_active"), "Should contain viz.sessions.active gauge")
            assertTrue(body.contains("viz_sessions_active 1.0"), "Session count should be 1.0")
        }

    @Test
    fun `all 7 ADR-020 metric names present after scenario run`() =
        testApplication {
            application { module() }

            val jsonClient =
                createClient {
                    install(ContentNegotiation) {
                        json()
                    }
                }

            // Create a session then run a fast scenario to drive events through
            jsonClient.post("/api/sessions?name=metrics-verify")
            // Run the nested scenario (fastest basic scenario)
            jsonClient.post("/api/scenarios/nested")

            val response = client.get("/metrics")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()

            // ADR-020 required metric names (Prometheus replaces '.' with '_')
            val requiredMetrics = listOf(
                "viz_sessions_active",
                "viz_sse_clients_active",
                "events_emitted",
                "events_dropped_total",   // Counter naming: may be _total or plain
                "events_buffer_size",
                "scenario_duration",
                "event_processing_duration",
            )

            // Check each required metric is present (Prometheus may suffix _total on counters,
            // use partial match to handle both plain and _total variants)
            val metricsToCheck = mapOf(
                "viz_sessions_active" to listOf("viz_sessions_active"),
                "viz_sse_clients_active" to listOf("viz_sse_clients_active"),
                "events_emitted" to listOf("events_emitted_total", "events_emitted"),
                "events_dropped" to listOf("events_dropped_total", "events_dropped"),
                "events_buffer_size" to listOf("events_buffer_size"),
                "scenario_duration" to listOf("scenario_duration_seconds", "scenario_duration"),
                "event_processing_duration" to listOf("event_processing_duration_seconds", "event_processing_duration"),
            )

            for ((metricDisplayName, candidates) in metricsToCheck) {
                val found = candidates.any { body.contains(it) }
                assertTrue(found, "Metrics output should contain $metricDisplayName (tried: $candidates)\nBody snippet: ${body.take(2000)}")
            }
        }

    @Test
    fun `viz sse clients active gauge increments while a stream is open and returns to zero after disconnect`() =
        testApplication {
            application { module() }

            val jsonClient =
                createClient {
                    install(ContentNegotiation) {
                        json()
                    }
                }
            val sseClient =
                createClient {
                    install(SSE)
                }

            // Reset the gauge in case a prior test left it non-zero
            sseClientsGauge.set(0)

            // Confirm gauge starts at 0
            val beforeBody = jsonClient.get("/metrics").bodyAsText()
            val beforeValue = parseSseClientsActiveValue(beforeBody)
            assertEquals(0.0, beforeValue, "viz_sse_clients_active should be 0.0 before any SSE connection")

            // Create a session that the SSE client will connect to
            val createResponse = jsonClient.post("/api/sessions?name=sse-gauge-test")
            assertEquals(HttpStatusCode.Created, createResponse.status)
            val createBody = createResponse.bodyAsText()
            // Extract sessionId from JSON response e.g. {"sessionId":"...","message":"..."}
            val sessionId = createBody.substringAfter("\"sessionId\":\"").substringBefore("\"")
            assertTrue(sessionId.isNotBlank(), "Session ID must be non-blank")

            // Run on Dispatchers.Default so delay/withTimeout use real time — testApplication's
            // virtual-time dispatcher would skip delays while the server runs on wall-clock time.
            withContext(Dispatchers.Default) {
                var sseError: Throwable? = null
                coroutineScope {
                    // Launch a coroutine that keeps the SSE connection open
                    val collectJob: Job =
                        launch {
                            try {
                                sseClient.sse("/api/sessions/$sessionId/stream") {
                                    // Consume events continuously to keep the connection alive
                                    incoming.collect { }
                                }
                            } catch (_: CancellationException) {
                                // Expected when the coroutine is cancelled
                            } catch (e: Exception) {
                                sseError = e
                            }
                        }

                    // Poll /metrics until viz_sse_clients_active >= 1.0 (bounded by 5 seconds)
                    var gaugeWhileOpen = 0.0
                    withTimeout(5_000L) {
                        while (true) {
                            val body = jsonClient.get("/metrics").bodyAsText()
                            val value = parseSseClientsActiveValue(body)
                            if (value >= 1.0) {
                                gaugeWhileOpen = value
                                break
                            }
                            kotlinx.coroutines.delay(50L)
                        }
                    }
                    assertTrue(
                        gaugeWhileOpen >= 1.0,
                        "viz_sse_clients_active should be >= 1.0 while SSE stream is open, got $gaugeWhileOpen (sseError: $sseError)",
                    )

                    // Cancel the SSE collecting coroutine and wait for it to finish
                    collectJob.cancelAndJoin()
                }

                // Poll /metrics until viz_sse_clients_active returns to 0.0 (bounded by 5 seconds).
                // The server handler is parked in bus.stream().collect and only notices the
                // disconnected client when it attempts a send, so publish an event each poll
                // to wake it and let the finally-block decrement run.
                val session = SessionManager.getSession(sessionId)
                var wakeSeq = 1_000L
                var gaugeAfterDisconnect = -1.0
                withTimeout(5_000L) {
                    while (true) {
                        session?.send(
                            CoroutineCreated(
                                sessionId = sessionId,
                                seq = wakeSeq++,
                                tsNanos = System.nanoTime(),
                                coroutineId = "wake-$wakeSeq",
                                jobId = "wake-job",
                                parentCoroutineId = null,
                                scopeId = "wake-scope",
                                label = "gauge-wake",
                            ),
                        )
                        val body = jsonClient.get("/metrics").bodyAsText()
                        val value = parseSseClientsActiveValue(body)
                        if (value == 0.0) {
                            gaugeAfterDisconnect = value
                            break
                        }
                        kotlinx.coroutines.delay(50L)
                    }
                }
                assertEquals(0.0, gaugeAfterDisconnect, "viz_sse_clients_active should return to 0.0 after SSE client disconnects")
            }
        }

    @Test
    fun `events buffer size gauge is deregistered when session is closed`() =
        testApplication {
            application { module() }

            val jsonClient =
                createClient {
                    install(ContentNegotiation) {
                        json()
                    }
                }

            // Create a session — onSessionCreated registers the per-session gauge
            val createResponse = jsonClient.post("/api/sessions?name=gauge-cleanup-test")
            assertEquals(HttpStatusCode.Created, createResponse.status)
            val sessionId =
                createResponse.bodyAsText().substringAfter("\"sessionId\":\"").substringBefore("\"")
            assertTrue(sessionId.isNotBlank(), "Session ID must be non-blank")

            val bodyWhileOpen = client.get("/metrics").bodyAsText()
            assertTrue(
                bodyWhileOpen.contains("events_buffer_size{sessionId=\"$sessionId\""),
                "events_buffer_size gauge for $sessionId should be registered while the session is open",
            )

            // Close the session — onSessionClosed must remove the gauge (WR-03:
            // otherwise the registry strongly references the dead session and
            // /metrics accumulates one stale series per session ever created)
            val deleteResponse = jsonClient.delete("/api/sessions/$sessionId")
            assertEquals(HttpStatusCode.OK, deleteResponse.status)

            val bodyAfterClose = client.get("/metrics").bodyAsText()
            assertFalse(
                bodyAfterClose.contains("events_buffer_size{sessionId=\"$sessionId\""),
                "events_buffer_size gauge for $sessionId must be deregistered after session close",
            )
        }

    /**
     * Parses the numeric value from a Prometheus metrics scrape for viz_sse_clients_active.
     * Finds the line starting with "viz_sse_clients_active " (no labels) and returns the
     * trailing number as a Double.
     */
    private fun parseSseClientsActiveValue(metricsBody: String): Double {
        val line =
            metricsBody.lines()
                .firstOrNull { it.startsWith("viz_sse_clients_active ") }
                ?: return 0.0
        return line.substringAfterLast(" ").trim().toDoubleOrNull() ?: 0.0
    }
}
