package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** A wrapper coroutine records whose it is (plan P-3), and its CoroutineCreated says it is the app's. */
class VizScopeOwnershipTest {
    private class Seen(
        val element: VizCoroutineElement,
        val job: Job,
    )

    @Test
    fun `vizLaunch records its session and its own job, an inheriting child has another job`() =
        runBlocking {
            val session = VizSession("own")
            val scope = VizScope(session)
            val wrapperSeen = CompletableDeferred<Seen>()
            val childSeen = CompletableDeferred<Seen>()
            val job =
                scope.vizLaunch(label = "w") {
                    wrapperSeen.complete(Seen(currentCoroutineContext()[VizCoroutineElement]!!, currentCoroutineContext()[Job]!!))
                    coroutineScope {
                        launch {
                            childSeen.complete(Seen(currentCoroutineContext()[VizCoroutineElement]!!, currentCoroutineContext()[Job]!!))
                            awaitCancellation()
                        }
                    }
                }
            val wrapper = withTimeout(5_000) { wrapperSeen.await() }
            val child = withTimeout(5_000) { childSeen.await() }
            assertSame(session, wrapper.element.owner)
            assertSame(wrapper.job, wrapper.element.ownerJob)
            assertSame(job, wrapper.job)
            assertSame(wrapper.element, child.element, "the child inherits the wrapper's element")
            assertNotSame(child.element.ownerJob, child.job, "but is another coroutine")
            scope.cancel()
            session.close()
        }

    @Test
    fun `vizAsync records its own job`() =
        runBlocking {
            val session = VizSession("own-async")
            val scope = VizScope(session)
            val seen = CompletableDeferred<Seen>()
            scope.vizAsync(label = "a") {
                seen.complete(Seen(currentCoroutineContext()[VizCoroutineElement]!!, currentCoroutineContext()[Job]!!))
            }.await()
            val wrapper = withTimeout(5_000) { seen.await() }
            assertSame(session, wrapper.element.owner)
            assertSame(wrapper.job, wrapper.element.ownerJob)
            scope.cancel()
            session.close()
        }

    @Test
    fun `vizLaunch and vizAsync coroutines are the app's`() =
        runBlocking {
            val session = VizSession("origin")
            val scope = VizScope(session)
            scope.vizLaunch(label = "l") { }.join()
            scope.vizAsync(label = "a") { 1 }.await()
            val created = session.store.all().filterIsInstance<CoroutineCreated>()
            assertEquals(listOf("l", "a"), created.map { it.label })
            assertEquals(listOf(CoroutineOrigin.APP, CoroutineOrigin.APP), created.map { it.origin })
            scope.cancel()
            session.close()
        }
}
