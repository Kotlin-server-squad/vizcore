package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.PollWatermark
import com.jh.proj.coroutineviz.health.TransportStatus
import com.jh.proj.coroutineviz.health.WatermarkObservation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IngestStatesTest {
    @BeforeEach
    fun setUp() = IngestStates.clear()

    @AfterEach
    fun tearDown() = IngestStates.clear()

    private fun watermark(
        appNanos: Long,
        lost: Long = 0,
        uncertain: Boolean = false,
        final: Boolean = false,
    ) = PollWatermark("s", 0, appNanos, pollEpochMs = 1_792_000_000_000 + appNanos / 1_000_000, lost, uncertain, final)

    @Test
    fun `a session nobody ingested into has no state`() {
        assertEquals(IngestSnapshot(), IngestStates.snapshot("s"))
    }

    @Test
    fun `the latest watermark and the latest event receipt are kept`() {
        val connection = IngestStates.connectionOpened("s", firstSeq = 1)
        IngestStates.eventReceived(connection, receivedEpochMs = 100)
        IngestStates.eventReceived(connection, receivedEpochMs = 90)
        IngestStates.watermarkReceived(connection, watermark(appNanos = 5_000_000_000), receivedEpochMs = 120)

        val snapshot = IngestStates.snapshot("s")
        assertEquals(100L, snapshot.lastIngestEpochMs, "receipt time never moves back")
        assertEquals(WatermarkObservation(5_000_000_000, 1_792_000_005_000, 120), snapshot.watermark)
        assertEquals(TransportStatus(), snapshot.transport)
        assertEquals(1L, snapshot.firstConnectionSeq)
    }

    @Test
    fun `a delayed watermark from an overlapping socket does not move the watermark back, but its losses count`() {
        val newer = IngestStates.connectionOpened("s", firstSeq = 1)
        val older = IngestStates.connectionOpened("s", firstSeq = 5)
        IngestStates.watermarkReceived(newer, watermark(appNanos = 20_000_000_000), receivedEpochMs = 1)
        IngestStates.watermarkReceived(older, watermark(appNanos = 5_000_000_000, lost = 1), receivedEpochMs = 2)

        val snapshot = IngestStates.snapshot("s")
        assertEquals(20_000_000_000, snapshot.watermark?.appNanos)
        assertEquals(TransportStatus(lostEvents = 1, sinceSeq = 5, revision = 1), snapshot.transport)
        assertEquals(1L, snapshot.firstConnectionSeq, "the first connection this process saw")
    }

    @Test
    fun `a final watermark makes the close clean`() {
        val connection = IngestStates.connectionOpened("s", firstSeq = 1)
        IngestStates.watermarkReceived(connection, watermark(appNanos = 1, final = true), receivedEpochMs = 1)
        IngestStates.connectionClosed(connection)

        assertFalse(IngestStates.snapshot("s").transport.deliveryUncertain)
    }

    @Test
    fun `a close without a final watermark makes delivery uncertain from that connection on, for good`() {
        val first = IngestStates.connectionOpened("s", firstSeq = 1)
        IngestStates.connectionClosed(first)
        val second = IngestStates.connectionOpened("s", firstSeq = 40)
        IngestStates.watermarkReceived(second, watermark(appNanos = 1, final = true), receivedEpochMs = 1)
        IngestStates.connectionClosed(second)

        assertEquals(TransportStatus(deliveryUncertain = true, sinceSeq = 1, revision = 1), IngestStates.snapshot("s").transport)
    }

    @Test
    fun `lost events keep the largest count and the earliest affected seq`() {
        val first = IngestStates.connectionOpened("s", firstSeq = 10)
        IngestStates.watermarkReceived(first, watermark(appNanos = 1, lost = 3, final = true), receivedEpochMs = 1)
        IngestStates.connectionClosed(first)
        val second = IngestStates.connectionOpened("s", firstSeq = 20)
        IngestStates.watermarkReceived(second, watermark(appNanos = 2, lost = 2), receivedEpochMs = 2)

        assertEquals(TransportStatus(lostEvents = 3, sinceSeq = 10, revision = 1), IngestStates.snapshot("s").transport)
    }

    @Test
    fun `a skipped frame makes delivery uncertain`() {
        val connection = IngestStates.connectionOpened("s", firstSeq = 7)
        IngestStates.frameSkipped(connection)

        assertEquals(TransportStatus(deliveryUncertain = true, sinceSeq = 7, revision = 1), IngestStates.snapshot("s").transport)
    }

    @Test
    fun `a forgotten session gets no state back from late frames or a late close`() {
        val connection = IngestStates.connectionOpened("s", firstSeq = 1)
        IngestStates.forget("s")
        IngestStates.eventReceived(connection, receivedEpochMs = 1)
        IngestStates.watermarkReceived(connection, watermark(appNanos = 1), receivedEpochMs = 1)
        IngestStates.frameSkipped(connection)
        IngestStates.connectionClosed(connection)

        assertEquals(IngestSnapshot(), IngestStates.snapshot("s"))
    }

    @Test
    fun `watermarks are published to subscribers`() =
        runBlocking {
            val connection = IngestStates.connectionOpened("s", firstSeq = 1)
            var received: WatermarkObservation? = null
            val subscriber = launch { received = IngestStates.watermarks("s").first() }
            // Let the subscriber reach the shared flow before the watermark is published.
            repeat(10) { yield() }
            IngestStates.watermarkReceived(connection, watermark(appNanos = 9), receivedEpochMs = 3)
            subscriber.join()

            assertEquals(9L, received?.appNanos)
            assertTrue(subscriber.isCompleted)
        }

    @Test
    fun `every transport change bumps the revision, a frame that changes nothing does not`() {
        val c = IngestStates.connectionOpened("s", firstSeq = 1)
        assertEquals(0, IngestStates.snapshot("s").transport.revision)
        IngestStates.watermarkReceived(c, watermark(appNanos = 1), receivedEpochMs = 1)
        assertEquals(0, IngestStates.snapshot("s").transport.revision, "no loss: no change")
        IngestStates.watermarkReceived(c, watermark(appNanos = 2, lost = 3), receivedEpochMs = 2)
        assertEquals(1, IngestStates.snapshot("s").transport.revision)
        IngestStates.watermarkReceived(c, watermark(appNanos = 3, lost = 3), receivedEpochMs = 3)
        assertEquals(1, IngestStates.snapshot("s").transport.revision, "the same facts again")
        IngestStates.frameSkipped(c)
        assertEquals(2, IngestStates.snapshot("s").transport.revision)
        IngestStates.connectionClosed(c)
        assertEquals(2, IngestStates.snapshot("s").transport.revision, "already uncertain since seq 1")
    }
}
