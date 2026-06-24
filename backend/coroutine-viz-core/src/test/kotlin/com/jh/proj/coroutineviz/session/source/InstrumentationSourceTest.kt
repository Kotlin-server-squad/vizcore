package com.jh.proj.coroutineviz.session.source

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.VizSession
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the [InstrumentationSource] lifecycle contract (Phase 6 RCO-01):
 * idempotent start/stop, isRunning transitions, multiple sources running
 * concurrently on ONE session and toggling independently, and that events a
 * running source produces land in the unchanged downstream `session.store`.
 */
class InstrumentationSourceTest {
    /**
     * Tiny in-test second source: on the first [start] it sends ONE event into
     * the bound session (proving downstream is reached), and tracks how many
     * times it enabled so idempotence is observable.
     */
    private class FakeEventSource(
        private val session: VizSession,
        override val sourceId: String = "fake",
    ) : InstrumentationSource {
        val startCount = AtomicInteger(0)
        val stopCount = AtomicInteger(0)
        private val running = AtomicBoolean(false)

        override val isRunning: Boolean get() = running.get()

        override fun start() {
            if (running.compareAndSet(false, true)) {
                startCount.incrementAndGet()
                session.send(
                    CoroutineCreated(
                        sessionId = session.sessionId,
                        seq = session.nextSeq(),
                        tsNanos = System.nanoTime(),
                        coroutineId = "fake-coro",
                        jobId = "fake-job",
                        parentCoroutineId = null,
                        scopeId = sourceId,
                        label = "fake-source-event",
                    ),
                )
            }
        }

        override fun stop() {
            if (running.compareAndSet(true, false)) {
                stopCount.incrementAndGet()
            }
        }
    }

    @Test
    fun `WrapperSource start is idempotent`() {
        val session = VizSession("idem-start")
        val source = WrapperSource(session)

        source.start()
        source.start()
        source.start()

        assertTrue(source.isRunning, "source should be running after start")
    }

    @Test
    fun `WrapperSource stop is idempotent`() {
        val session = VizSession("idem-stop")
        val source = WrapperSource(session)

        source.start()
        source.stop()
        source.stop()

        assertFalse(source.isRunning, "source should not be running after stop")
    }

    @Test
    fun `isRunning transitions false to true to false across start and stop`() {
        val session = VizSession("transitions")
        val source = WrapperSource(session)

        assertFalse(source.isRunning, "fresh source is not running")
        source.start()
        assertTrue(source.isRunning, "running after start")
        source.stop()
        assertFalse(source.isRunning, "not running after stop")
    }

    @Test
    fun `fake source start is idempotent - sends exactly one event despite repeated start`() {
        val session = VizSession("fake-idem")
        val source = FakeEventSource(session)

        source.start()
        source.start()

        assertEquals(1, source.startCount.get(), "start enable should run once")
        assertEquals(1, session.store.count(), "exactly one event reaches the store")
    }

    @Test
    fun `two sources on the same session both run and toggle independently`() {
        val session = VizSession("two-sources")
        val wrapper = WrapperSource(session)
        val fake = FakeEventSource(session)

        wrapper.start()
        fake.start()

        assertTrue(wrapper.isRunning, "wrapper running")
        assertTrue(fake.isRunning, "fake running")

        // Stop one; the other stays running (independent toggle, RCO-01).
        wrapper.stop()
        assertFalse(wrapper.isRunning, "wrapper stopped")
        assertTrue(fake.isRunning, "fake still running after wrapper stop")

        fake.stop()
        assertFalse(fake.isRunning, "fake stopped")
    }

    @Test
    fun `events sent by a running source land in session store (downstream unchanged)`() {
        val session = VizSession("downstream")
        val fake = FakeEventSource(session)

        assertEquals(0, session.store.count(), "store empty before start")
        fake.start()

        assertEquals(1, session.store.count(), "running source's event reaches the store")
        val stored = session.store.all().single()
        assertTrue(stored is CoroutineCreated, "stored event is the synthesized CoroutineCreated")
        assertEquals("fake-source-event", stored.label)
    }
}
