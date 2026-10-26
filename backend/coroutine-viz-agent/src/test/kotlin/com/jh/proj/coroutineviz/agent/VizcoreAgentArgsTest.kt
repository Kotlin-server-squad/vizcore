package com.jh.proj.coroutineviz.agent

import com.jh.proj.coroutineviz.client.VizcoreClient
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit proofs for [AgentBootstrap.parseArgs] (IDE-01 agent-args; T-13-01 tamper mitigation)
 * plus the bootstrap fail-soft guarantee.
 *
 * `parseArgs` is tested as pure logic. [AgentBootstrap.start] is exercised ONLY against a
 * guaranteed-closed port (no live backend involved) — a propagated bootstrap exception would
 * surface (via the premain shim) as a host-JVM abort, so "returns normally on failure" is the
 * contract under test. Uses `@org.junit.jupiter.api.Test` because
 * `kotlin-test-junit` alone is undiscovered under `useJUnitPlatform()` (STATE.md 11-02 / 09-02
 * precedent).
 */
class VizcoreAgentArgsTest {
    @Test
    fun `an unreachable backend never aborts the host JVM and leaves the agent retrying`() {
        // Port 1 -> connection refused on every bootstrap attempt. A propagated bootstrap error
        // would reach the host as 'processing of -javaagent failed' (Phase 15 UAT blocker). Since
        // #254 the agent is not disabled either: the client keeps creating the session in the
        // background, and the agent says so on stderr.
        val stderr = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(stderr, true))
        val client =
            try {
                AgentBootstrap.start("app=t,backend=http://127.0.0.1:1,token=")
            } finally {
                System.setErr(original)
            }
        try {
            val output = stderr.toString()
            assertNotNull(client, "an unreachable backend must not disable the agent: $output")
            assertNull(client.sessionIdOrNull)
            assertTrue("backend at http://127.0.0.1:1 is not reachable yet" in output, output)
            assertFalse("DISABLED" in output, output)
        } finally {
            client?.stop()
        }
    }

    @Test
    fun `well-formed arg string parses to the four expected key-value pairs`() {
        val parsed = AgentBootstrap.parseArgs("app=svc,backend=http://h:8080,token=t,corr=uuid")

        assertEquals(4, parsed.size, "all four well-formed pairs must be parsed")
        assertEquals("svc", parsed["app"])
        assertEquals("http://h:8080", parsed["backend"], "values may legitimately contain ':' and '/'")
        assertEquals("t", parsed["token"])
        assertEquals("uuid", parsed["corr"])
    }

    @Test
    fun `malformed input drops junk tokens lacking equals and keeps the valid pairs without throwing`() {
        // Mixes valid pairs with junk: a bare token, an empty token, and a key with no value yet.
        val parsed = AgentBootstrap.parseArgs("app=svc,garbage,,backend=http://h")

        assertEquals(2, parsed.size, "tokens without '=' are dropped, valid pairs survive")
        assertEquals("svc", parsed["app"])
        assertEquals("http://h", parsed["backend"])
        assertFalse(parsed.containsKey("garbage"), "a bare token with no '=' must not become a key")
    }

    @Test
    fun `null input yields an empty map so run falls back to all defaults`() {
        val parsed = AgentBootstrap.parseArgs(null)

        assertTrue(parsed.isEmpty(), "null arg string must yield an empty map (defaults path)")
    }

    @Test
    fun `empty input yields an empty map so run falls back to all defaults`() {
        val parsed = AgentBootstrap.parseArgs("")

        assertTrue(parsed.isEmpty(), "empty arg string must yield an empty map (defaults path)")
    }

    @Test
    fun `poll sets the sampling interval and falls back to the default when absent or unusable`() {
        assertEquals(50L, AgentBootstrap.pollIntervalMillis("50"))
        assertEquals(VizcoreClient.DEFAULT_POLL_INTERVAL_MILLIS, AgentBootstrap.pollIntervalMillis(null))
        assertEquals(VizcoreClient.DEFAULT_POLL_INTERVAL_MILLIS, AgentBootstrap.pollIntervalMillis("fast"))
        assertEquals(VizcoreClient.DEFAULT_POLL_INTERVAL_MILLIS, AgentBootstrap.pollIntervalMillis("1"))
    }
}
