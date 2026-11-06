package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.MutexCreated
import com.jh.proj.coroutineviz.events.MutexLockAcquired
import com.jh.proj.coroutineviz.events.MutexLockRequested
import com.jh.proj.coroutineviz.events.MutexQueueChanged
import com.jh.proj.coroutineviz.events.MutexUnlocked
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Sync: Mutex demos' stories, on virtual time. Who finds a lock free and who waits, and in
 * which order, depends on when each coroutine asks; on real time a slow scheduler could change
 * that. Here every step reads one virtual clock, so each story is exact on every run, down to its
 * total scripted time. [MutexDemoPacingTest] checks the demos' pacing on real time.
 *
 * Since #292 a coroutine waiting for a `VizMutex` is recorded as suspended (`waitKind = MUTEX`,
 * `resourceId` = the mutex) and resumed when it gets the lock, unless its own job is cancelled.
 */
class MutexStoriesTest {
    /** One run of a demo on virtual time: its events in order, and how long it took in scripted ms. */
    private class Story(
        val events: List<VizEvent>,
        val scriptedMillis: Long,
    ) {
        private val created = events.filterIsInstance<CoroutineCreated>()
        private val labelOf = created.associate { it.coroutineId to it.label }

        val labels: Set<String> = created.mapNotNull { it.label }.toSet()

        fun lockId(label: String) = events.filterIsInstance<MutexCreated>().single { it.mutexLabel == label }.mutexId

        fun childrenOf(label: String) = created.filter { labelOf[it.parentCoroutineId] == label }.mapNotNull { it.label }

        fun parentOf(label: String) = labelOf[created.single { it.label == label }.parentCoroutineId]

        fun completed(label: String) = events.any { it is CoroutineCompleted && it.label == label }

        fun cancelled(label: String) = events.any { it is CoroutineCancelled && it.label == label }

        /** Who got [lock], in order. */
        fun holders(lock: String): List<String?> =
            events.filterIsInstance<MutexLockAcquired>().filter { it.mutexId == lockId(lock) }.map { it.acquirerLabel }

        /** Who asked for [lock], in order, and whether it was held at that moment. */
        fun requests(lock: String): List<Pair<String?, Boolean>> =
            events.filterIsInstance<MutexLockRequested>().filter { it.mutexId == lockId(lock) }.map { it.requesterLabel to it.isLocked }

        /** The suspensions recorded as a wait for [lock], in order. */
        fun waitsFor(lock: String): List<CoroutineSuspended> =
            events.filterIsInstance<CoroutineSuspended>().filter { it.waitKind == WaitKind.MUTEX && it.resourceId == lockId(lock) }

        /** The first event after [wait] for the same coroutine. */
        fun nextAfter(wait: CoroutineSuspended): VizEvent? =
            events.firstOrNull { it.seq > wait.seq && it is CoroutineEvent && it.coroutineId == wait.coroutineId }

        inline fun <reified T : CoroutineEvent> seqOf(label: String): Long = events.filterIsInstance<T>().single { it.label == label }.seq

        /** Who held [lock] just before the event numbered [seq]. */
        fun holderAt(
            seq: Long,
            lock: String,
        ): String? {
            val id = lockId(lock)
            var holder: String? = null
            for (event in events.takeWhile { it.seq < seq }) {
                if (event is MutexLockAcquired && event.mutexId == id) holder = event.acquirerLabel
                if (event is MutexUnlocked && event.mutexId == id) holder = null
            }
            return holder
        }

        /** At most one holder at any moment: between a coroutine's Acquired and its Unlocked nobody else gets [lock]. */
        fun neverTwoHolders(lock: String): Boolean {
            val id = lockId(lock)
            var holder: String? = null
            for (event in events) {
                when {
                    event is MutexLockAcquired && event.mutexId == id -> {
                        if (holder != null) return false
                        holder = event.acquirerId
                    }
                    event is MutexUnlocked && event.mutexId == id -> holder = null
                }
            }
            return true
        }
    }

    @Test
    fun `three gates count nine visitors, taking turns at the lock, and eight of them wait for it`() =
        runTest {
            val counted = CompletableDeferred<Int>()
            val story = runStory { session, context -> SyncScenarios.runCounter(session, context) { counted.complete(it) } }

            assertEquals(setOf("count-visitors", "gate-1", "gate-2", "gate-3"), story.labels)
            assertEquals((1..3).flatMap { listOf("gate-1", "gate-2", "gate-3") }, story.holders("visitor-count"), "the gates take turns")
            assertEquals(listOf("gate-1" to false), story.requests("visitor-count").take(1), "only the first update finds the lock free")
            assertEquals(8, story.requests("visitor-count").count { it.second }, "every other update waits for the lock")
            val waits = story.waitsFor("visitor-count")
            assertEquals(8, waits.size, "each wait for the lock is a suspension")
            for (wait in waits) assertTrue(story.nextAfter(wait) is CoroutineResumed, "${wait.label} resumes when it gets the lock")
            assertEquals(
                2,
                story.events.filterIsInstance<MutexQueueChanged>().maxOf { it.waitingCoroutineIds.size },
                "two gates wait at once",
            )
            assertTrue(story.neverTwoHolders("visitor-count"), "one gate at a time updates the count")
            // The guide's picture: gate-1 is done, gate-2 holds the lock, and gate-3 waits for it.
            val lastWait = story.waitsFor("visitor-count").last()
            assertEquals("gate-3", lastWait.label)
            assertTrue(story.seqOf<CoroutineCompleted>("gate-1") < lastWait.seq, "gate-1 is done before gate-3's last wait")
            assertEquals("gate-2", story.holderAt(lastWait.seq, "visitor-count"), "gate-2 holds the lock while gate-3 waits")
            assertEquals(9, counted.await(), "no visitor is lost")
            assertTrue(story.labels.all { story.completed(it) })
            assertEquals(14_000, story.scriptedMillis)
        }

    @Test
    fun `the transfers queue at Anna's account, which each locks first, and the holiday is declined`() =
        runTest {
            val balances = CompletableDeferred<Pair<Int, Int>>()
            val story =
                runStory {
                    session,
                    context,
                    ->
                    SyncScenarios.runBankTransfer(session, context) { anna, ben -> balances.complete(anna to ben) }
                }
            val transfers = listOf("pay-rent", "pay-back-dinner", "split-groceries", "book-holiday")

            assertEquals(setOf("transfers") + transfers, story.labels)
            assertEquals(transfers, story.holders("account-anna"), "the transfers go one by one, in the order they came")
            assertEquals(transfers, story.holders("account-ben"), "each takes Ben's account second, with Anna's held")
            assertEquals(listOf("pay-back-dinner", "split-groceries", "book-holiday"), story.waitsFor("account-anna").map { it.label })
            for (wait in story.waitsFor("account-anna")) assertTrue(story.nextAfter(wait) is CoroutineResumed, "${wait.label} resumes")
            assertEquals(
                emptyList(),
                story.requests("account-ben").filter { it.second },
                "Ben's account is always free: it is locked second",
            )
            assertTrue(story.neverTwoHolders("account-anna") && story.neverTwoHolders("account-ben"))
            assertEquals(530 to 970, balances.await(), "rent, dinner and groceries move; the holiday is declined; the total stays 1500")
            assertTrue(story.labels.all { story.completed(it) })
            assertEquals(12_000, story.scriptedMillis)
        }

    @Test
    fun `each price is loaded once, requests that miss wait for the lock, and cached prices need no lock`() =
        runTest {
            val story = runStory { session, context -> SyncScenarios.runCache(session, context) }
            val requests = (1..8).map { "request-$it" }

            assertEquals(setOf("shop-server", "load-espresso", "load-grinder") + requests, story.labels)
            assertEquals("request-1", story.parentOf("load-espresso"), "the first espresso request loads it")
            assertEquals("request-5", story.parentOf("load-grinder"), "the first grinder request loads it")
            assertEquals(listOf("request-1", "request-2", "request-3", "request-5", "request-7"), story.holders("price-cache"))
            assertEquals(listOf("request-2", "request-3", "request-7"), story.waitsFor("price-cache").map { it.label })
            for (wait in story.waitsFor("price-cache")) {
                assertTrue(story.nextAfter(wait) is CoroutineResumed, "${wait.label} resumes")
                assertEquals(emptyList(), story.childrenOf(wait.label!!), "${wait.label} finds the price cached after the wait")
            }
            // Request 6 finds the espresso in the cache while request 5 holds the lock for the grinder.
            val held =
                story.events
                    .filterIsInstance<MutexLockAcquired>()
                    .single { it.acquirerLabel == "request-5" }
                    .seq
            val released =
                story.events
                    .filterIsInstance<MutexUnlocked>()
                    .single { it.releaserLabel == "request-5" }
                    .seq
            val served = story.events.single { it is CoroutineCompleted && it.label == "request-6" }.seq
            assertTrue(served in held..released, "request-6 is served while the lock is held")
            assertTrue(story.labels.all { story.completed(it) })
            assertEquals(11_500, story.scriptedMillis)
        }

    @Test
    fun `two transfers each hold one account and wait for the other's, until the watchdog cancels both`() =
        runTest {
            val story = runStory { session, context -> SyncScenarios.runDeadlock(session, context) }
            val anna = story.lockId("account-anna")
            val ben = story.lockId("account-ben")

            assertEquals(setOf("month-end", "anna-pays-ben", "ben-pays-anna", "watchdog"), story.labels)
            assertEquals(listOf("anna-pays-ben"), story.holders("account-anna"))
            val firstLocks = story.events.filterIsInstance<MutexLockAcquired>().maxOf { it.seq }
            val firstSecondRequest =
                story.events
                    .filterIsInstance<MutexLockRequested>()
                    .filter { it.isLocked }
                    .minOf { it.seq }
            assertTrue(firstLocks < firstSecondRequest, "both hold their payer's account before either asks for the other")
            assertEquals(listOf("ben-pays-anna"), story.holders("account-ben"))
            val waits = story.events.filterIsInstance<CoroutineSuspended>().filter { it.waitKind == WaitKind.MUTEX }
            assertEquals(listOf("ben-pays-anna" to anna, "anna-pays-ben" to ben), waits.map { it.label to it.resourceId })
            for (wait in waits) {
                val next = story.nextAfter(wait)
                assertTrue(next !is CoroutineResumed, "${wait.label} never gets the lock it waits for, so it never resumes: $next")
                assertNull(story.events.firstOrNull { it is CoroutineResumed && it.seq > wait.seq && it.coroutineId == wait.coroutineId })
                assertTrue(story.cancelled(wait.label!!), "${wait.label} is cancelled while it waits")
            }
            val unlocked =
                story.events
                    .filterIsInstance<MutexUnlocked>()
                    .map { it.mutexId }
                    .toSet()
            assertEquals(setOf(anna, ben), unlocked, "cancelling the transfers releases both accounts")
            assertTrue(story.completed("watchdog") && story.completed("month-end"), "the watchdog ends the deadlock")
            assertEquals(11_000, story.scriptedMillis)
        }

    /** Runs a demo on this test's virtual time and returns its story once every coroutine has ended. */
    private suspend fun TestScope.runStory(start: suspend (VizSession, CoroutineContext) -> Job): Story =
        runOnVirtualTime("mutex-story", start).let { Story(it.events, it.scriptedMillis) }
}
