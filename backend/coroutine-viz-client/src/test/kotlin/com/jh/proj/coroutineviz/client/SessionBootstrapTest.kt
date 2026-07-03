package com.jh.proj.coroutineviz.client

import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-level proofs for [createSession]'s optional `correlation` query param (CORR-01 / D-11).
 *
 * Uses an in-process [testApplication] (already on the client test classpath via
 * ktor-server-test-host — NO new dependency, T-09-SC) standing up a `POST /api/sessions`
 * route that CAPTURES the inbound query parameters and returns the canonical
 * `201 {"sessionId": "srv-1", ...}` body. Asserting against the captured params proves
 * exactly what hit the wire:
 *  - correlation supplied  → `correlation=<token>` present (alongside `name`), server id returned
 *  - correlation omitted   → NO `correlation` param emitted (back-compat), server id returned
 */
class SessionBootstrapTest {
    /** Captures the query params of the single POST the createSession call issues. */
    private class CapturedRequest {
        val name = AtomicReference<String?>(null)
        val correlation = AtomicReference<String?>(null)
        val sawCorrelationParam = AtomicReference(false)
    }

    /**
     * Stand up an in-process backend whose `POST /api/sessions` records the inbound
     * query params into [captured] and replies with the canonical create response, then
     * run [block] against a test [io.ktor.client.HttpClient] bound to it.
     */
    private fun withCapturingBackend(
        captured: CapturedRequest,
        serverSessionId: String,
        block: suspend (httpClient: io.ktor.client.HttpClient) -> Unit,
    ) = testApplication {
        routing {
            post("/api/sessions") {
                val params = call.request.queryParameters
                captured.name.set(params["name"])
                captured.correlation.set(params["correlation"])
                captured.sawCorrelationParam.set(params.contains("correlation"))
                call.respondText(
                    """{"sessionId":"$serverSessionId","message":"ok"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Created,
                )
            }
        }
        // A bare test client is enough: createSession only needs POST + a JSON body.
        val httpClient = createClient { }
        // Sanity probe so the test fails loudly if the route shape ever drifts.
        assertEquals(HttpStatusCode.NotFound, httpClient.get("/__absent__").status)
        block(httpClient)
    }

    @Test
    fun `correlation supplied is forwarded as a query param and server id is returned`() {
        val captured = CapturedRequest()
        withCapturingBackend(captured, serverSessionId = "srv-1") { httpClient ->
            val sessionId =
                createSession(
                    httpClient = httpClient,
                    backendUrl = "",
                    appName = "order-service",
                    token = "jwt",
                    correlation = "tok-123",
                )
            assertEquals("srv-1", sessionId, "the SERVER-assigned id must be returned (Pitfall 1)")
        }
        assertEquals("order-service", captured.name.get(), "the name param must still be sent")
        assertTrue(captured.sawCorrelationParam.get(), "the correlation param must be present when supplied")
        assertEquals("tok-123", captured.correlation.get(), "the supplied correlation token must hit the wire")
    }

    @Test
    fun `empty first response is retried and the second attempt's session id is returned`() {
        // Simulates the observed premain-time race: the first response arrives with a success
        // status but an EMPTY body; the retry must absorb it and return the server id.
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        testApplication {
            routing {
                post("/api/sessions") {
                    if (attempts.incrementAndGet() == 1) {
                        call.respondText("", ContentType.Application.Json, HttpStatusCode.Created)
                    } else {
                        call.respondText(
                            """{"sessionId":"srv-retry","message":"ok"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Created,
                        )
                    }
                }
            }
            val httpClient = createClient { }
            val sessionId =
                createSession(
                    httpClient = httpClient,
                    backendUrl = "",
                    appName = "retry-app",
                    token = "",
                )
            assertEquals("srv-retry", sessionId, "the retry must recover from an empty-body response")
        }
        assertEquals(2, attempts.get(), "exactly one retry after the empty first response")
    }

    @Test
    fun `persistent failure surfaces an actionable error - not a bare JSON parse crash`() {
        testApplication {
            routing {
                post("/api/sessions") {
                    call.respondText("", ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                }
            }
            val httpClient = createClient { }
            val failure =
                kotlin.runCatching {
                    createSession(httpClient = httpClient, backendUrl = "", appName = "doomed", token = "")
                }.exceptionOrNull()
            assertNotNull(failure, "persistent failure must throw")
            assertTrue(
                failure.message.orEmpty().contains("attempts") && failure.message.orEmpty().contains("429"),
                "error must say how many attempts were made and the last HTTP status; was: ${failure.message}",
            )
        }
    }

    @Test
    fun `correlation omitted emits no correlation param - back-compat`() {
        val captured = CapturedRequest()
        withCapturingBackend(captured, serverSessionId = "srv-2") { httpClient ->
            val sessionId =
                createSession(
                    httpClient = httpClient,
                    backendUrl = "",
                    appName = "order-service",
                    token = "jwt",
                )
            assertEquals("srv-2", sessionId, "the SERVER-assigned id must be returned even with no correlation")
        }
        assertEquals("order-service", captured.name.get(), "the name param must still be sent")
        assertNotNull(captured.name.get())
        assertEquals(false, captured.sawCorrelationParam.get(), "no correlation param when none is supplied (back-compat)")
        assertNull(captured.correlation.get(), "the captured correlation must be absent when omitted")
    }
}
