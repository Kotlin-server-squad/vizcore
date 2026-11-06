package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.health.EventScript
import com.jh.proj.coroutineviz.health.sec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Per-origin peak and throughput of the metrics projection (spec C-3 `/metrics` by origin; plan E-15). */
class MetricsProjectionByOriginTest {
    private val session = VizSession("m")

    @AfterEach
    fun tearDown() = session.close()

    /** An app clock hours away from the backend's: throughput must not depend on it. */
    private val appClock = sec(5 * 3600)

    private fun script() =
        EventScript("m").apply {
            created("app-root", at = appClock)
            created("app-1", at = appClock, parent = "app-root")
            created("app-2", at = appClock, parent = "app-root")
            completed("app-1", at = appClock + 1)
            created("viz", at = appClock, origin = CoroutineOrigin.VIZCORE)
            created("plain", at = appClock, origin = null)
        }

    @Test
    fun `each origin has its own peak and a live throughput on the backend clock`() {
        script().events.forEach(session::send)
        val buckets = runBlocking { awaitApplied { session.metricsProjection.byOrigin().getValue(OriginBucket.UNREPORTED).peak == 1 } }
        assertEquals(3, buckets.getValue(OriginBucket.APP).peak)
        assertEquals(1, buckets.getValue(OriginBucket.VIZCORE).peak)
        assertEquals(1, buckets.getValue(OriginBucket.UNREPORTED).peak)
        assertTrue(buckets.getValue(OriginBucket.APP).throughputPerSec!! > 0.0, "the app's clock is hours off the backend's")
    }

    @Test
    fun `a projection rebuilt from stored events has no per-origin throughput`() {
        session.metricsProjection.rebuildFrom(script().events)
        val buckets = session.metricsProjection.byOrigin()
        assertEquals(3, buckets.getValue(OriginBucket.APP).peak)
        assertNull(buckets.getValue(OriginBucket.APP).throughputPerSec)
    }

    private suspend fun awaitApplied(done: () -> Boolean): Map<OriginBucket, OriginAggregate> {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(10)
        return session.metricsProjection.byOrigin()
    }

    @Test
    fun `a whole lifecycle counts in its origin's throughput, terminal event included`() {
        EventScript("m").apply {
            created("one", at = appClock)
            started("one", at = appClock)
            completed("one", at = appClock + 1)
        }.events.forEach(session::send)
        val buckets = runBlocking { awaitApplied { session.metricsProjection.byOrigin().getValue(OriginBucket.APP).throughputPerSec == 0.3 } }
        assertEquals(0.3, buckets.getValue(OriginBucket.APP).throughputPerSec)
    }
}
