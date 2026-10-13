package com.jh.proj.coroutineviz.routes

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `sse.heartbeatSeconds` must stay below the SPA watchdog's 45 s silence limit, otherwise the
 * SPA closes healthy, quiet streams between two heartbeats.
 */
class SseHeartbeatConfigTest {
    @Test
    fun `a missing or unparseable value falls back to the default`() {
        assertEquals(DEFAULT_SSE_HEARTBEAT_SECONDS, resolveSseHeartbeatSeconds(null))
        assertEquals(DEFAULT_SSE_HEARTBEAT_SECONDS, resolveSseHeartbeatSeconds("soon"))
    }

    @Test
    fun `an in-range value is used as is`() {
        assertEquals(1, resolveSseHeartbeatSeconds("1"))
        assertEquals(20, resolveSseHeartbeatSeconds(" 20 "))
        assertEquals(MAX_SSE_HEARTBEAT_SECONDS, resolveSseHeartbeatSeconds("30"))
    }

    @Test
    fun `a value the SPA watchdog would outlast is clamped`() {
        assertEquals(MAX_SSE_HEARTBEAT_SECONDS, resolveSseHeartbeatSeconds("50"))
        assertEquals(MAX_SSE_HEARTBEAT_SECONDS, resolveSseHeartbeatSeconds("3600"))
    }

    @Test
    fun `zero or a negative value is raised to one second`() {
        assertEquals(1, resolveSseHeartbeatSeconds("0"))
        assertEquals(1, resolveSseHeartbeatSeconds("-5"))
    }
}
