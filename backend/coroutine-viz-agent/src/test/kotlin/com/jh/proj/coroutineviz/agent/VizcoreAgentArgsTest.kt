package com.jh.proj.coroutineviz.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit proofs for [VizcoreAgent.parseArgs] (IDE-01 agent-args; T-13-01 tamper mitigation).
 *
 * Tests ONLY the pure `parseArgs` logic directly — NEVER [VizcoreAgent.premain], which
 * would attempt a real network start via `VizcoreClient.start`. Uses
 * `@org.junit.jupiter.api.Test` because `kotlin-test-junit` alone is undiscovered under
 * `useJUnitPlatform()` (STATE.md 11-02 / 09-02 precedent).
 */
class VizcoreAgentArgsTest {
    @Test
    fun `well-formed arg string parses to the four expected key-value pairs`() {
        val parsed = VizcoreAgent.parseArgs("app=svc,backend=http://h:8080,token=t,corr=uuid")

        assertEquals(4, parsed.size, "all four well-formed pairs must be parsed")
        assertEquals("svc", parsed["app"])
        assertEquals("http://h:8080", parsed["backend"], "values may legitimately contain ':' and '/'")
        assertEquals("t", parsed["token"])
        assertEquals("uuid", parsed["corr"])
    }

    @Test
    fun `malformed input drops junk tokens lacking equals and keeps the valid pairs without throwing`() {
        // Mixes valid pairs with junk: a bare token, an empty token, and a key with no value yet.
        val parsed = VizcoreAgent.parseArgs("app=svc,garbage,,backend=http://h")

        assertEquals(2, parsed.size, "tokens without '=' are dropped, valid pairs survive")
        assertEquals("svc", parsed["app"])
        assertEquals("http://h", parsed["backend"])
        assertFalse(parsed.containsKey("garbage"), "a bare token with no '=' must not become a key")
    }

    @Test
    fun `null input yields an empty map so premain falls back to all defaults`() {
        val parsed = VizcoreAgent.parseArgs(null)

        assertTrue(parsed.isEmpty(), "null arg string must yield an empty map (defaults path)")
    }

    @Test
    fun `empty input yields an empty map so premain falls back to all defaults`() {
        val parsed = VizcoreAgent.parseArgs("")

        assertTrue(parsed.isEmpty(), "empty arg string must yield an empty map (defaults path)")
    }
}
