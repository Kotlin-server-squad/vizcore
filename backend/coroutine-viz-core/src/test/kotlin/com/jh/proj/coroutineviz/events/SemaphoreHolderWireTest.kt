package com.jh.proj.coroutineviz.events

import com.jh.proj.coroutineviz.appJson
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The semaphore part of the S-7 contract: permits per holder, and a release whose holder is unknown. */
class SemaphoreHolderWireTest {
    private val serializer = PolymorphicSerializer(VizEvent::class)

    @Test
    fun `the new fields round-trip`() {
        val released =
            SemaphorePermitReleased(
                sessionId = "s",
                seq = 1,
                tsNanos = 2,
                semaphoreId = "semaphore-1",
                semaphoreLabel = "pool",
                releaserId = "c-2",
                releaserLabel = "stranger",
                newAvailablePermits = 2,
                holdDurationNanos = 0,
                holderUnknown = true,
            )
        val state =
            SemaphoreStateChanged(
                sessionId = "s",
                seq = 3,
                tsNanos = 4,
                semaphoreId = "semaphore-1",
                semaphoreLabel = "pool",
                availablePermits = 2,
                totalPermits = 3,
                activeHolders = listOf("c-1"),
                activeHolderLabels = listOf("owner"),
                waitingCoroutines = emptyList(),
                waitingLabels = emptyList(),
                activeHolderPermits = listOf(2),
                unknownHolderPermits = 1,
            )

        assertEquals(released, appJson.decodeFromString(serializer, appJson.encodeToString(serializer, released)))
        assertEquals(state, appJson.decodeFromString(serializer, appJson.encodeToString(serializer, state)))
    }

    @Test
    fun `older events read as attributed, with no counts`() {
        val oldRelease =
            """{"type":"SemaphorePermitReleased","sessionId":"s","seq":1,"tsNanos":2,"semaphoreId":"x",""" +
                """"semaphoreLabel":null,"releaserId":"c","releaserLabel":null,"newAvailablePermits":1,"holdDurationNanos":5}"""
        val oldState =
            """{"type":"SemaphoreStateChanged","sessionId":"s","seq":3,"tsNanos":4,"semaphoreId":"x","semaphoreLabel":null,""" +
                """"availablePermits":1,"totalPermits":2,"activeHolders":["c"],"activeHolderLabels":[null],""" +
                """"waitingCoroutines":[],"waitingLabels":[]}"""

        assertFalse((appJson.decodeFromString(serializer, oldRelease) as SemaphorePermitReleased).holderUnknown)
        val state = appJson.decodeFromString(serializer, oldState) as SemaphoreStateChanged
        assertEquals(emptyList(), state.activeHolderPermits)
        assertEquals(0, state.unknownHolderPermits)
    }
}
