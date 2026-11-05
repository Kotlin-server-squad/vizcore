package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Code that cannot suspend (`unlock`, `release`, `trySend`) finds the instrumented coroutine it runs in through
 * [VizCoroutineElement.current]; `runBlocking { currentCoroutineContext() }` could not (it starts a new context).
 */
@Timeout(30)
class VizCoroutineElementCurrentTest {
    private val session = VizSession("current-element")
    private val viz = VizScope(session)

    @AfterEach
    fun close() = session.close()

    private fun currentLabel(): String? = VizCoroutineElement.current()?.label

    @Test
    fun `non-suspending code in a viz coroutine sees its element, also after a dispatcher switch`() =
        runBlocking {
            val before = CompletableDeferred<String?>()
            val after = CompletableDeferred<String?>()
            viz.vizLaunch("reader") {
                before.complete(currentLabel())
                withContext(Dispatchers.IO) { after.complete(currentLabel()) }
            }.join()

            assertEquals("reader", before.await())
            assertEquals("reader", after.await())
            assertNull(currentLabel(), "runBlocking is not an instrumented coroutine")
        }

    @Test
    fun `a child sees its own element`() =
        runBlocking {
            val seen = CompletableDeferred<String?>()
            viz.vizLaunch("parent") { vizLaunch("child") { seen.complete(currentLabel()) } }.join()

            assertEquals("child", seen.await())
        }

    @Test
    fun `a thread that ran a viz coroutine does not keep its element`() =
        runBlocking {
            val pool = Executors.newSingleThreadExecutor()
            try {
                viz.vizLaunch("visitor", pool.asCoroutineDispatcher()) { currentLabel() }.join()

                assertNull(pool.submit<String?> { currentLabel() }.get())
            } finally {
                pool.shutdown()
            }
        }
}
