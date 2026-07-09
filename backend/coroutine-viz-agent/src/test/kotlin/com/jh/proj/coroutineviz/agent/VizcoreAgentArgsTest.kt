package com.jh.proj.coroutineviz.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit proofs for [AgentBootstrap.parseArgs] (IDE-01 agent-args; T-13-01 tamper mitigation)
 * plus the bootstrap fail-soft guarantee.
 *
 * `parseArgs` is tested as pure logic. [AgentBootstrap.run] is exercised ONLY for the
 * fail-soft path against a guaranteed-closed port (no live backend involved) — a propagated
 * bootstrap exception would surface (via the premain shim) as a host-JVM abort, so "returns
 * normally on failure" is the contract under test. Uses `@org.junit.jupiter.api.Test` because
 * `kotlin-test-junit` alone is undiscovered under `useJUnitPlatform()` (STATE.md 11-02 / 09-02
 * precedent).
 */
class VizcoreAgentArgsTest {
    @Test
    fun `run fails soft - an unreachable backend must never abort the host JVM`() {
        // Port 1 -> connection refused on every bootstrap attempt. run() must swallow the
        // failure (log + run uninstrumented), never propagate: a propagated bootstrap error
        // would reach the host as 'processing of -javaagent failed' (Phase 15 UAT blocker).
        AgentBootstrap.run("app=t,backend=http://127.0.0.1:1,token=")
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
}
