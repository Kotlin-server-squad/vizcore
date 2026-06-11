package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
}
