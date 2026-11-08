package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Triage "Lifetimes" (Part 7, P-9): finished, so far up to the watermark, at least without one. */
class CoroutineLifetimeTest {
    private fun summary(script: EventScript) = checkNotNull(script.table().get("c"))

    private fun running() =
        EventScript().apply {
            created("c", at = sec(100))
            started("c", at = sec(100))
            suspended("c", at = sec(103))
        }

    @Test
    fun `a finished coroutine lives from its first observation to its terminal event`() {
        val script = running().apply { completed("c", at = sec(107)) }
        assertEquals(CoroutineLifetime(sec(7), LifetimeKind.FINISHED), CoroutineLifetime.of(summary(script), watermarkNanos = sec(200)))
    }

    @Test
    fun `an unfinished coroutine lives so far, up to the live watermark`() {
        assertEquals(CoroutineLifetime(sec(20), LifetimeKind.SO_FAR), CoroutineLifetime.of(summary(running()), watermarkNanos = sec(120)))
    }

    @Test
    fun `a watermark older than its own last event counts to the event`() {
        assertEquals(CoroutineLifetime(sec(3), LifetimeKind.SO_FAR), CoroutineLifetime.of(summary(running()), watermarkNanos = sec(101)))
    }

    @Test
    fun `without a watermark an unfinished coroutine lives at least to its last event`() {
        assertEquals(CoroutineLifetime(sec(3), LifetimeKind.AT_LEAST), CoroutineLifetime.of(summary(running()), watermarkNanos = null))
    }
}
