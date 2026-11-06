package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A session without usable summaries (the state a table is in once abandoned after an apply failure) answers 503
 * `summaries-unavailable`, never a health (H-3); its other reads still answer. The seam is a store whose sessions keep no
 * summaries: `readSummaries` then throws the same exception an abandoned table does.
 */
class SessionHealthUnavailableTest {
    private val store = SessionHealthUnavailableTestStore()

    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        store.clearAll()
        SessionManager.clearAll()
        IngestStates.clear()
    }

    @Test
    fun `a session whose summaries are unavailable answers 503 summaries-unavailable and its facts still answer`() =
        testApplication {
            application {
                module()
                SessionManager.useStore(store)
            }
            startApplication()
            store.createSession("app-1")

            val health = client.get("/api/sessions/app-1/health")
            assertEquals(HttpStatusCode.ServiceUnavailable, health.status)
            assertTrue(health.bodyAsText().contains("\"code\":\"summaries-unavailable\""), health.bodyAsText())

            val session = client.get("/api/sessions/app-1")
            assertEquals(HttpStatusCode.OK, session.status, session.bodyAsText())
            assertTrue(session.bodyAsText().contains("\"origin\":\"UNKNOWN\""), session.bodyAsText())
            assertTrue(session.bodyAsText().contains("\"captureStartAppNanos\":null"), session.bodyAsText())
        }
}
