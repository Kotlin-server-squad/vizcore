package com.jh.proj.coroutineviz.routes

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit proofs for the in-memory [CorrelationRegistry] (CORR-01/CORR-02, D-09/D-10/D-12).
 * Pure map behavior — bind/resolve/evict/last-write-wins — independent of the HTTP layer
 * (the tenant gate is deferred to the route's `resolveScopedSession`, see [CorrelationResolveTest]).
 */
class CorrelationRegistryTest {
    @BeforeEach
    fun setUp() {
        CorrelationRegistry.clear()
    }

    @AfterEach
    fun tearDown() {
        CorrelationRegistry.clear()
    }

    @Test
    fun `bind then resolve returns the bound session id`() {
        CorrelationRegistry.bind("tok-1", "session-abc")
        assertEquals("session-abc", CorrelationRegistry.resolve("tok-1"))
    }

    @Test
    fun `resolve of an unknown token returns null`() {
        assertNull(CorrelationRegistry.resolve("never-bound"))
    }

    @Test
    fun `last-write-wins - rebinding a token re-points it to the newest session`() {
        CorrelationRegistry.bind("tok-2", "session-first")
        CorrelationRegistry.bind("tok-2", "session-second")
        assertEquals("session-second", CorrelationRegistry.resolve("tok-2"))
    }

    @Test
    fun `evict removes every token bound to a session id`() {
        CorrelationRegistry.bind("tok-3", "session-x")
        CorrelationRegistry.evict("session-x")
        assertNull(CorrelationRegistry.resolve("tok-3"))
    }

    @Test
    fun `evict of a session id with no binding is a no-op`() {
        // Must not throw; unrelated bindings stay intact.
        CorrelationRegistry.bind("tok-4", "session-y")
        CorrelationRegistry.evict("session-with-no-binding")
        assertEquals("session-y", CorrelationRegistry.resolve("tok-4"))
    }
}
