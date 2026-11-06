package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import io.ktor.websocket.Frame
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * A socket or stream that allocates ingest state while its session is being deleted drops that state again when it ends
 * (the cleanup in IngestRoutes' and the SSE route's `finally`). The seam deletes the session just before the state is
 * allocated: the delete's own `forget` has run, so only that cleanup can remove the state allocated after it.
 */
class IngestStateCleanupTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    @Test
    fun `an ingest socket whose session is deleted as its state is allocated leaves no state behind`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            val id = ws.createSessionNamed("cleanup-ws")
            var deleted = false
            IngestStates.beforeStateAllocatedForTest = { sid ->
                if (sid == id && !deleted) {
                    deleted = true
                    SessionManager.closeSession(sid)
                }
            }
            runCatching {
                ws.webSocket("/api/sessions/$id/ingest") {
                    runCatching { send(Frame.Text(HealthEvents.created(id, "a", sec(1)).frameText())) }
                    // The server closes the socket (session not found); drain until it does.
                    withTimeout(5_000) { while (incoming.receiveCatching().isSuccess) Unit }
                }
            }
            assertTrue(deleted, "the seam ran")
            eventually { !IngestStates.states.containsKey(id) }
        }

    @Test
    fun `an SSE stream whose session is deleted as its state is allocated leaves no state behind`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("cleanup-sse")
            var deleted = false
            IngestStates.beforeStateAllocatedForTest = { sid ->
                if (sid == id && !deleted) {
                    deleted = true
                    SessionManager.closeSession(sid)
                }
            }
            // The stream subscribes to the session's watermarks (allocating state), sees the session gone and ends.
            runCatching {
                withTimeout(5_000) {
                    client.prepareGet("/api/sessions/$id/stream").execute { response ->
                        val channel = response.bodyAsChannel()
                        while (channel.readUTF8Line() != null) Unit
                    }
                }
            }
            assertTrue(deleted, "the seam ran")
            eventually { !IngestStates.states.containsKey(id) }
        }
}
