package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.MutexCreated
import com.jh.proj.coroutineviz.events.MutexLockAcquired
import com.jh.proj.coroutineviz.events.MutexUnlocked
import com.jh.proj.coroutineviz.events.SemaphoreCreated
import com.jh.proj.coroutineviz.events.SemaphorePermitAcquired
import com.jh.proj.coroutineviz.events.SemaphorePermitReleased
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
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
import kotlin.test.assertTrue

/**
 * The Sync: Semaphore and Combined demos' stories, on virtual time. Who finds a permit free, who waits and who
 * gives up depends on when each coroutine asks; on real time a slow scheduler could change that. Here every step
 * reads one virtual clock, so each story is exact on every run, down to the scripted millisecond each permit is
 * taken and the total scripted time. [SemaphoreDemoPacingTest] checks the demos' pacing on real time.
 *
 * Since #292 a coroutine waiting for a `VizSemaphore` permit is recorded as suspended (`waitKind = SEMAPHORE`,
 * `resourceId` = the semaphore) and resumed when it gets the permit, or when a timeout it survives ends the wait.
 */
class SemaphoreStoriesTest {
    /** One run of a demo on virtual time. */
    private class Story(
        private val run: VirtualDemoRun,
    ) {
        val events: List<VizEvent> = run.events
        val scriptedMillis = run.scriptedMillis
        private val created = events.filterIsInstance<CoroutineCreated>()
        private val labelOf = created.associate { it.coroutineId to it.label }

        val labels: Set<String> = created.mapNotNull { it.label }.toSet()

        /** Scripted ms from the demo's first event, so several stories can share one test's virtual clock. */
        private val start = run.at(events.first())

        fun at(event: VizEvent) = run.at(event) - start

        fun semaphoreId(label: String) = events.filterIsInstance<SemaphoreCreated>().single { it.semaphoreLabel == label }.semaphoreId

        fun mutexId(label: String) = events.filterIsInstance<MutexCreated>().single { it.mutexLabel == label }.mutexId

        fun parentOf(label: String) = labelOf[created.single { it.label == label }.parentCoroutineId]

        fun completed(label: String) = events.any { it is CoroutineCompleted && it.label == label }

        /** Who got a permit of [semaphore], in order, with the scripted millisecond. */
        fun permits(semaphore: String): List<Pair<String?, Long>> =
            events
                .filterIsInstance<SemaphorePermitAcquired>()
                .filter { it.semaphoreId == semaphoreId(semaphore) }
                .map { it.acquirerLabel to at(it) }

        /** The suspensions recorded as a wait for [semaphore], in order. */
        fun waitsFor(semaphore: String): List<CoroutineSuspended> =
            events.filterIsInstance<CoroutineSuspended>().filter {
                it.waitKind == WaitKind.SEMAPHORE && it.resourceId == semaphoreId(semaphore)
            }

        /** The suspensions recorded as a wait for the mutex [lock], in order. */
        fun lockWaitsFor(lock: String): List<CoroutineSuspended> =
            events.filterIsInstance<CoroutineSuspended>().filter { it.waitKind == WaitKind.MUTEX && it.resourceId == mutexId(lock) }

        /** The first event after [wait] for the same coroutine. */
        fun nextAfter(wait: CoroutineSuspended): VizEvent? =
            events.firstOrNull { it.seq > wait.seq && it is CoroutineEvent && it.coroutineId == wait.coroutineId }

        /** Each wait for [semaphore] as (who, from, until): until is when the coroutine resumed. */
        fun waitSpans(semaphore: String): List<Triple<String?, Long, Long?>> =
            waitsFor(semaphore).map { wait ->
                val next = nextAfter(wait)
                Triple(wait.label, at(wait), (next as? CoroutineResumed)?.let { at(it) })
            }

        /** The most permits of [semaphore] held at once, replaying acquires and releases in order. */
        fun mostHeldAtOnce(semaphore: String): Int {
            val id = semaphoreId(semaphore)
            var held = 0
            var most = 0
            for (event in events) {
                if (event is SemaphorePermitAcquired && event.semaphoreId == id) most = maxOf(most, ++held)
                if (event is SemaphorePermitReleased && event.semaphoreId == id) held--
            }
            return most
        }

        /** What [label] is suspended in at scripted [millis] ("delay", "semaphore", "mutex", "await"), or null if it is not. */
        fun suspendedIn(
            label: String,
            millis: Long,
        ): String? {
            val own = events.filterIsInstance<CoroutineEvent>().filter { it.label == label && at(it) <= millis }
            return (own.lastOrNull { it is CoroutineSuspended || it is CoroutineResumed } as? CoroutineSuspended)?.reason
        }

        /** Who holds a permit of [semaphore] at scripted [millis], replaying acquires and named releases. */
        fun permitHoldersAt(
            semaphore: String,
            millis: Long,
        ): Set<String?> {
            val id = semaphoreId(semaphore)
            val holders = mutableSetOf<String?>()
            for (event in events.filter { at(it) <= millis }) {
                if (event is SemaphorePermitAcquired && event.semaphoreId == id) holders += event.acquirerLabel
                if (event is SemaphorePermitReleased && event.semaphoreId == id) holders -= event.releaserLabel
            }
            return holders
        }

        /** Who holds the mutex [lock] at scripted [millis]. */
        fun lockHolderAt(
            lock: String,
            millis: Long,
        ): String? {
            var holder: String? = null
            for (event in events.filter { at(it) <= millis }) {
                if (event is MutexLockAcquired && event.mutexId == mutexId(lock)) holder = event.acquirerLabel
                if (event is MutexUnlocked && event.mutexId == mutexId(lock)) holder = null
            }
            return holder
        }

        fun noneCancelledOrFailed() = events.none { it is CoroutineCancelled || it is CoroutineFailed }
    }

    @Test
    fun `three connections serve nine lunchtime requests, and six of them wait for one`() =
        runTest {
            val story = runStory { session, context -> SyncScenarios.runConnectionPool(session, context) }
            val requests =
                listOf(
                    "show-basket",
                    "search-shoes",
                    "order-history",
                    "track-parcel",
                    "write-review",
                    "checkout",
                    "wish-list",
                    "store-hours",
                    "loyalty-points",
                )

            assertEquals(setOf("shop-backend") + requests, story.labels)
            assertEquals(
                listOf(500L, 1000, 1500, 3000, 4000, 5000, 5500, 6000, 7500),
                story.permits("db-connections").map { it.second },
            )
            assertEquals(requests, story.permits("db-connections").map { it.first }, "in the order they asked")
            assertEquals(
                listOf(
                    Triple("track-parcel", 2000L, 3000L),
                    Triple("write-review", 2500L, 4000L),
                    Triple("checkout", 3000L, 5000L),
                    Triple("wish-list", 3500L, 5500L),
                    Triple("store-hours", 4000L, 6000L),
                    Triple("loyalty-points", 4500L, 7500L),
                ),
                story.waitSpans("db-connections"),
                "each wait is a suspension, resumed when the request gets a connection",
            )
            assertEquals(3, story.mostHeldAtOnce("db-connections"), "never more than three connections")
            assertTrue(story.labels.all { story.completed(it) } && story.noneCancelledOrFailed())
            assertEquals(11_000, story.scriptedMillis)
        }

    @Test
    fun `the weather service answers two calls at a time, and the trip page waits for every forecast`() =
        runTest {
            val planned = CompletableDeferred<List<String>>()
            val story = runStory { session, context -> SyncScenarios.runRateLimiter(session, context) { planned.complete(it) } }
            val cities = listOf("rome", "paris", "vienna", "prague", "lisbon", "oslo", "split", "krakow")
            val calls = cities.map { "weather-$it" }

            assertEquals(setOf("trip-planner", "trip-page") + calls, story.labels)
            assertTrue((calls + "trip-page").all { story.parentOf(it) == "trip-planner" })
            assertEquals(
                calls.zip(listOf(0L, 0, 2500, 3000, 4500, 6000, 7000, 8000)),
                story.permits("weather-api"),
                "two calls at a time, the next as soon as one answers",
            )
            assertEquals(
                calls.drop(2).map { Triple(it, 0L, story.permits("weather-api").toMap().getValue(it)) },
                story.waitSpans("weather-api"),
            )
            assertEquals(2, story.mostHeldAtOnce("weather-api"))
            val awaits = story.events.filterIsInstance<CoroutineSuspended>().filter { it.label == "trip-page" && it.reason == "await" }
            assertTrue(awaits.isNotEmpty(), "the trip page waits for the forecasts")
            assertEquals(cities, planned.await().map { it.substringBefore(':') }, "a forecast for every city, in order")
            assertTrue(story.labels.all { story.completed(it) } && story.noneCancelledOrFailed())
            assertEquals(12_000, story.scriptedMillis)
        }

    @Test
    fun `the disk takes two files at a time, and a small file's permit goes straight to the next file`() =
        runTest {
            val story = runStory { session, context -> SyncScenarios.runFileProcessor(session, context) }
            val files =
                listOf(
                    "payslips.pdf",
                    "bank-statements.pdf",
                    "receipts.zip",
                    "insurance.pdf",
                    "donations.pdf",
                    "pension.pdf",
                    "childcare.pdf",
                    "invoices.zip",
                    "doctor-bills.pdf",
                )

            assertEquals(setOf("tax-folder") + files, story.labels)
            assertEquals(
                files.zip(listOf(0L, 0, 1500, 4000, 5000, 7000, 7500, 8500, 10000)),
                story.permits("disk"),
            )
            assertEquals(files.drop(2), story.waitsFor("disk").map { it.label })
            for (wait in story.waitsFor("disk")) assertTrue(story.nextAfter(wait) is CoroutineResumed, "${wait.label} resumes")
            assertEquals(2, story.mostHeldAtOnce("disk"))
            assertTrue(story.labels.all { story.completed(it) } && story.noneCancelledOrFailed())
            assertEquals(13_000, story.scriptedMillis)
        }

    @Test
    fun `shoppers wait at most three seconds for a fitting room, and two of them give up`() =
        runTest {
            val closed = CompletableDeferred<Pair<List<String>, List<String>>>()
            val story =
                runStory { session, context ->
                    SyncScenarios.runResourceTimeout(session, context) { triedOn, left -> closed.complete(triedOn to left) }
                }
            val shoppers = listOf("anna", "ben", "carla", "dan", "eva", "finn", "gina")

            assertEquals(setOf("clothes-shop") + shoppers, story.labels)
            assertEquals(
                listOf("anna" to 500L, "ben" to 1000L, "carla" to 3500L, "dan" to 4500L, "gina" to 7000L),
                story.permits("fitting-rooms"),
            )
            assertEquals(
                listOf(
                    Triple("carla", 1500L, 3500L),
                    Triple("dan", 2000L, 4500L),
                    Triple("eva", 2500L, 5500L),
                    Triple("finn", 3000L, 6000L),
                    Triple("gina", 6000L, 7000L),
                ),
                story.waitSpans("fitting-rooms"),
                "eva and finn's waits end at their 3 s timeout, which they survive: a resume, not a cancel",
            )
            assertEquals(
                listOf("anna", "ben", "carla", "dan", "gina") to listOf("eva", "finn"),
                closed.await(),
            )
            assertEquals(2, story.mostHeldAtOnce("fitting-rooms"))
            assertTrue(story.labels.all { story.completed(it) } && story.noneCancelledOrFailed())
            assertEquals(11_500, story.scriptedMillis)
        }

    @Test
    fun `the baker waits when the rack is full, the assistant when it is empty, and every loaf is sold in order`() =
        runTest {
            val sold = CompletableDeferred<Pair<List<Int>, Int>>()
            val story =
                runStory { session, context ->
                    SyncScenarios.runProducerConsumer(session, context) { loaves, most -> sold.complete(loaves to most) }
                }

            assertEquals(setOf("bakery", "baker", "shop-assistant"), story.labels)
            assertEquals(
                listOf(Triple("baker", 7500L, 8500L), Triple("baker", 9500L, 11000L)),
                story.waitSpans("free-places"),
                "the baker waits twice for a free place on the full rack",
            )
            assertEquals(
                listOf(Triple("shop-assistant", 0L, 1000L)),
                story.waitSpans("loaves-ready"),
                "the assistant waits once, for the first loaf",
            )
            assertEquals(
                listOf(1000L, 3500, 6000, 8500, 11000, 13500, 16000, 18500),
                story.permits("loaves-ready").map { it.second },
                "the assistant takes a loaf every 2.5 s once there is one",
            )
            assertEquals((1..8).toList() to 3, sold.await(), "sold in the order baked; never more than 3 on the rack")
            assertTrue(story.labels.all { story.completed(it) } && story.noneCancelledOrFailed())
            assertEquals(21_000, story.scriptedMillis)
        }

    @Test
    fun `three pairs sell, a declined card puts its pair back, and the payment provider takes two at a time`() =
        runTest {
            val settled = CompletableDeferred<Pair<List<String>, Int>>()
            val story =
                runStory { session, context ->
                    SyncScenarios.runOrderProcessing(session, context) { bought, left -> settled.complete(bought to left) }
                }
            val orders = listOf("order-anna", "order-ben", "order-carla", "order-dan", "order-eva", "order-finn")

            assertEquals(setOf("sneaker-drop") + orders, story.labels)
            assertEquals(
                listOf("order-ben", "order-carla", "order-dan"),
                story.lockWaitsFor("stock").map { it.label },
                "each waits for the stock while the order before it checks",
            )
            for (wait in story.lockWaitsFor("stock")) assertTrue(story.nextAfter(wait) is CoroutineResumed, "${wait.label} resumes")
            val stockHolders =
                story.events
                    .filterIsInstance<MutexLockAcquired>()
                    .filter { it.mutexId == story.mutexId("stock") }
                    .map { it.acquirerLabel to story.at(it) }
            assertEquals(
                listOf(
                    "order-anna" to 500L,
                    "order-ben" to 1500L,
                    "order-carla" to 2500L,
                    "order-dan" to 3500L,
                    "order-ben" to 5000L,
                    "order-eva" to 6000L,
                    "order-finn" to 9000L,
                ),
                stockHolders,
                "ben takes the stock lock again to put his pair back",
            )
            assertEquals(
                listOf("order-anna" to 1500L, "order-ben" to 2500L, "order-carla" to 4000L, "order-eva" to 7000L),
                story.permits("payment-gateway"),
                "dan and finn pay nothing: no pair was left when they checked",
            )
            assertEquals(listOf(Triple("order-carla", 3500L, 4000L)), story.waitSpans("payment-gateway"))
            assertEquals(2, story.mostHeldAtOnce("payment-gateway"))
            assertEquals(listOf("order-anna", "order-carla", "order-eva") to 0, settled.await())
            assertTrue(story.labels.all { story.completed(it) } && story.noneCancelledOrFailed())
            assertEquals(11_000, story.scriptedMillis)
        }

    /**
     * Each guide's picture shows its demo's tree at one moment, and the tree shows holders and waiters alike as
     * suspended. Its caption says who holds a permit, who waits for one and who is busy without one: true here, at
     * the picture's moment, from the events.
     */
    @Test
    fun `each picture's caption is true at its moment`() =
        runTest {
            val pool = runStory { session, context -> SyncScenarios.runConnectionPool(session, context) }
            assertEquals(setOf("checkout", "wish-list", "store-hours"), pool.permitHoldersAt("db-connections", 6250))
            assertEquals("semaphore", pool.suspendedIn("loyalty-points", 6250))
            assertEquals(listOf("delay", "delay"), listOf("track-parcel", "write-review").map { pool.suspendedIn(it, 6250) })

            val trip = runStory { session, context -> SyncScenarios.runRateLimiter(session, context) }
            assertEquals(setOf("weather-vienna", "weather-prague"), trip.permitHoldersAt("weather-api", 3500))
            for (city in listOf("lisbon", "oslo", "split", "krakow")) assertEquals("semaphore", trip.suspendedIn("weather-$city", 3500))
            assertEquals("await", trip.suspendedIn("trip-page", 3500))

            val folder = runStory { session, context -> SyncScenarios.runFileProcessor(session, context) }
            assertEquals(setOf("bank-statements.pdf", "donations.pdf"), folder.permitHoldersAt("disk", 6500))
            for (file in listOf("pension.pdf", "childcare.pdf", "invoices.zip", "doctor-bills.pdf")) {
                assertEquals("semaphore", folder.suspendedIn(file, 6500), file)
            }

            val shop = runStory { session, context -> SyncScenarios.runResourceTimeout(session, context) }
            assertEquals(setOf("dan", "gina"), shop.permitHoldersAt("fitting-rooms", 7250))
            assertEquals("delay", shop.suspendedIn("carla", 7250), "carla pays at the till")

            val bakery = runStory { session, context -> SyncScenarios.runProducerConsumer(session, context) }
            assertEquals("semaphore", bakery.suspendedIn("baker", 8000), "the baker waits for a free place")
            assertEquals(bakery.semaphoreId("free-places"), bakery.waitsFor("free-places").first().resourceId)
            assertEquals("delay", bakery.suspendedIn("shop-assistant", 8000), "the assistant sells a loaf")

            val drop = runStory { session, context -> SyncScenarios.runOrderProcessing(session, context) }
            assertEquals(setOf("order-eva"), drop.permitHoldersAt("payment-gateway", 9250), "eva pays")
            assertEquals("order-finn", drop.lockHolderAt("stock", 9250), "finn checks the stock")
        }

    /** Runs a demo on this test's virtual time and returns its story once every coroutine has ended. */
    private suspend fun TestScope.runStory(start: suspend (VizSession, CoroutineContext) -> Job): Story =
        Story(runOnVirtualTime("semaphore-story", start))
}
