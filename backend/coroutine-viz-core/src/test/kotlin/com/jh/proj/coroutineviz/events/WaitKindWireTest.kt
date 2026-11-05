package com.jh.proj.coroutineviz.events

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.EventContext
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.coroutineSuspended
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The S-7 wire contract (spec 2026-11-05): a recorded wait says what it waited for; older events stay valid. */
class WaitKindWireTest {
    private val serializer = PolymorphicSerializer(VizEvent::class)

    @Test
    fun `a wait's kind and resource round-trip through the shared Json`() {
        val suspended =
            CoroutineSuspended(
                sessionId = "s",
                seq = 4,
                tsNanos = 9,
                coroutineId = "c",
                jobId = "j",
                parentCoroutineId = null,
                scopeId = "sc",
                label = "order-7",
                reason = WaitKind.MUTEX.reason,
                waitKind = WaitKind.MUTEX,
                resourceId = "mutex-3",
            )
        val json = appJson.encodeToString(serializer, suspended)

        assertTrue(json.contains("\"waitKind\":\"MUTEX\""), json)
        assertTrue(json.contains("\"resourceId\":\"mutex-3\""), json)
        assertEquals(suspended, appJson.decodeFromString(serializer, json))
    }

    @Test
    fun `an event from an older producer has no wait kind`() {
        val old =
            """{"type":"CoroutineSuspended","sessionId":"s","seq":1,"tsNanos":2,"coroutineId":"c","jobId":"j",""" +
                """"parentCoroutineId":null,"scopeId":"sc","label":null,"reason":"delay"}"""
        val decoded = appJson.decodeFromString(serializer, old) as CoroutineSuspended

        assertNull(decoded.waitKind)
        assertNull(decoded.resourceId)
    }

    @Test
    fun `each wait kind sends the same kind as its reason`() {
        assertEquals(listOf("mutex", "semaphore", "channel-send", "channel-receive"), WaitKind.entries.map { it.reason })
    }

    @Test
    fun `the context helper carries the wait`() {
        val session = VizSession("wait-helper")
        try {
            val ctx = EventContext(session, "c", "j", null, "sc", "order-7")
            val event = ctx.coroutineSuspended("mutex", waitKind = WaitKind.MUTEX, resourceId = "mutex-3")

            assertEquals(WaitKind.MUTEX, event.waitKind)
            assertEquals("mutex-3", event.resourceId)
        } finally {
            session.close()
        }
    }
}
