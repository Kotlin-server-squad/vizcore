package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Regression for F10 (2026-06-22): the Producer-Consumer sync scenario constructs a "full slots"
 * counting semaphore that must start with 0 available permits. kotlinx [Semaphore] forbids
 * `permits = 0`, so [VizSemaphore]/[vizSemaphore] gained an `acquiredPermits` parameter to express
 * "0 available out of N" as `permits = N, acquiredPermits = N`.
 */
class VizSemaphoreTest {
    private fun newScope(name: String): VizScope {
        val session = VizSession(name)
        return VizScope(session, context = CoroutineScope(SupervisorJob()).coroutineContext)
    }

    @Test
    fun `acquiredPermits equal to permits starts with zero available and supports release then acquire`() =
        runTest {
            val sem = newScope("sem-fully-acquired").vizSemaphore("full-slots", permits = 5, acquiredPermits = 5)
            assertEquals(0, sem.availablePermits, "a fully-acquired semaphore must start with 0 available")

            sem.release()
            assertEquals(1, sem.availablePermits, "release must make one permit available")

            sem.acquire()
            assertEquals(0, sem.availablePermits, "acquire must take the released permit back")
        }

    @Test
    fun `default acquiredPermits is zero so existing callers are unchanged`() =
        runTest {
            val sem = newScope("sem-default").vizSemaphore("db-pool", permits = 3)
            assertEquals(3, sem.availablePermits, "default (no acquiredPermits) must keep all permits available")
        }
}
