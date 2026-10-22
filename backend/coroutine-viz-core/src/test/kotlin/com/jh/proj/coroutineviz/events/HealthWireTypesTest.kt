package com.jh.proj.coroutineviz.events

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HealthWireTypesTest {
    private val serializer = PolymorphicSerializer(VizEvent::class)

    private fun roundTrip(event: VizEvent): VizEvent = appJson.decodeFromString(serializer, appJson.encodeToString(serializer, event))

    @Test
    fun `PollWatermark round-trips through the shared Json`() {
        val watermark =
            PollWatermark(
                sessionId = "s",
                seq = 7,
                tsNanos = 1_000,
                pollEpochMs = 1_792_000_000_000,
                lostEvents = 3,
                deliveryUncertain = true,
                final = true,
            )
        assertEquals(watermark, roundTrip(watermark))
    }

    @Test
    fun `origin and provenance round-trip`() {
        val created =
            CoroutineCreated(
                sessionId = "s",
                seq = 1,
                tsNanos = 5,
                coroutineId = "c",
                jobId = "j",
                parentCoroutineId = null,
                scopeId = "sc",
                label = "cio-from-nio-reader",
                origin = CoroutineOrigin.VIZCORE,
            )
        val suspended =
            CoroutineSuspended(
                sessionId = "s",
                seq = 2,
                tsNanos = 6,
                coroutineId = "c",
                jobId = "j",
                parentCoroutineId = null,
                scopeId = "sc",
                label = null,
                reason = "suspended",
                suspensionPoint = SuspensionPoint("main", "App.kt", 3, "creation"),
                provenance = SuspensionProvenance.CREATION_FALLBACK,
            )
        assertEquals(created, roundTrip(created))
        assertEquals(suspended, roundTrip(suspended))
    }

    @Test
    fun `events from an older SDK decode with null origin and provenance`() {
        val created =
            """{"type":"CoroutineCreated","sessionId":"s","seq":1,"tsNanos":5,"coroutineId":"c","jobId":"j",""" +
                """"parentCoroutineId":null,"scopeId":"sc","label":null}"""
        val suspended =
            """{"type":"CoroutineSuspended","sessionId":"s","seq":2,"tsNanos":6,"coroutineId":"c","jobId":"j",""" +
                """"parentCoroutineId":null,"scopeId":"sc","label":null,"reason":"delay"}"""
        assertNull((appJson.decodeFromString(serializer, created) as CoroutineCreated).origin)
        assertNull((appJson.decodeFromString(serializer, suspended) as CoroutineSuspended).provenance)
    }
}
