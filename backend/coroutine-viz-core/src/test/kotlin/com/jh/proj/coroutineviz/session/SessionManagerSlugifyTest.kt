package com.jh.proj.coroutineviz.session

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the UAT gap "session name with spaces breaks ingest + polling"
 * (GAP-SESSIONID-SPACES): a run-config name with spaces must yield a URL-safe
 * session id at the minting site, while an already-safe name passes through
 * unchanged. See .planning/debug/DEBUG-sessionid-unencoded-paths.md.
 */
class SessionManagerSlugifyTest {
    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
    }

    @Test
    fun `createSession slugifies a name with spaces into a url-safe id`() {
        val session = runBlocking { SessionManager.createSession("demo boot jar") }
        assertTrue(
            session.sessionId.matches(Regex("^demo-boot-jar-\\d+$")),
            "expected a slugified '^demo-boot-jar-<millis>$' id, got '${session.sessionId}'",
        )
        // The whole id is restricted to the URL-safe allow-set.
        assertTrue(
            session.sessionId.matches(Regex("^[A-Za-z0-9._-]+$")),
            "minted id contains a char outside [A-Za-z0-9._-]: '${session.sessionId}'",
        )
    }

    @Test
    fun `slugifySessionName leaves an already-safe name byte-identical`() {
        val safe = "order-service_v1.2"
        assertEquals(safe, slugifySessionName(safe), "a URL-safe name must pass through unchanged")
    }

    @Test
    fun `slugifySessionName maps unicode slash percent and hash to dash`() {
        // 'é' (U+00E9), '/', '%', '#' are each outside the allow-set → each becomes '-'.
        assertEquals("a-b-c-d-e", slugifySessionName("a/b%c#dée"))

        val out = slugifySessionName("space /%#? é tab\tend")
        assertTrue(
            out.matches(Regex("^[A-Za-z0-9._-]+$")),
            "an unsafe char survived slugification: '$out'",
        )
    }
}
