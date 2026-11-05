package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The Patterns demos: common ways to combine coroutines, told as everyday work. Like the Basics
 * ([BasicScenarios]) and the Channels ([ChannelScenarios]) their steps are paced with [pause] so a
 * person can watch them: 0.5-3 s a step, 10-30 s a demo, at the configured `scenarios.timeScale`.
 * Each returns the demo's root coroutine, which keeps running after the call returns.
 *
 * Where a story has work that fails, the failure stays where the story says: a try that fails is
 * caught by its caller (`coroutineScope` rethrows a child's failure instead of cancelling the
 * caller), and independent work runs under `supervisorScope`, where one child's failure does not
 * cancel the others.
 */
object PatternScenarios {
    private val logger = LoggerFactory.getLogger(PatternScenarios::class.java)

    /** How long the barista of the Producer-Consumer demo needs for each drink, by order number (1-based). */
    private val DRINK_MILLIS = listOf(2000L, 2000, 1500, 1500, 1500, 1500, 1000, 1000, 1000, 1000)

    /** How long the cashier of the Producer-Consumer demo takes for each order: the morning rush, then a quiet spell. */
    private val ORDER_TAKING_MILLIS = listOf(700L, 600, 600, 600, 600, 600, 3000, 3000, 3000, 3000)

    /** How long resizing takes for each photo of the Fan-Out / Fan-In demo, by photo number (1-based). */
    private val RESIZE_MILLIS = listOf(2500L, 1500, 2000, 3000, 1000, 2000, 2500, 1500, 2000)

    /** A server's error answer, e.g. HTTP 503. Thrown on purpose by the demos ([intendedByDemo]). */
    private class HttpError(
        val status: Int,
        reason: String,
    ) : RuntimeException("HTTP $status: $reason")

    /**
     * Retry with exponential backoff, as a weather app loading today's forecast. The weather server
     * is overloaded and answers HTTP 503 to the first three tries; each try takes 1.5 s (0.5 s to
     * connect, 1 s to wait for the answer). After each failed try the app waits, twice as long each
     * time: 0.75 s, 1.5 s, 3 s. The fourth try, at 9.75 s, gets the forecast, and the app shows it
     * until about 12.3 s.
     *
     * Each try runs in its own coroutine (`attempt-1` ... `attempt-4`) so it shows in the session;
     * `coroutineScope` hands its failure to the retry loop instead of cancelling `load-forecast`.
     */
    suspend fun runRetry(session: VizSession): Job =
        coroutineScope {
            logger.info("Starting retry with backoff scenario in session: ${session.sessionId}")

            val viz = session.demoScope()
            var answered = 0

            viz.vizLaunch("load-forecast") {
                val forecast =
                    retry(times = RETRY_TIMES, initialDelayMillis = 750, factor = 2) { attempt ->
                        coroutineScope {
                            vizAsync("attempt-$attempt") {
                                pause(500) // connect
                                pause(1000) // wait for the answer
                                answered++
                                if (answered <= OVERLOADED_ANSWERS) {
                                    throw HttpError(503, "the weather server is overloaded").intendedByDemo()
                                }
                                "sunny, 21 °C"
                            }.await()
                        }
                    }
                logger.debug("Forecast: $forecast")
                pause(1000) // show it
            }
        }

    /**
     * Tries [block] up to [times] times. After a try that fails with an [HttpError] it waits
     * [initialDelayMillis], then [factor] times longer after each next failure. Any other failure,
     * and cancellation, ends the retry at once.
     */
    private suspend fun <T> VizScope.retry(
        times: Int,
        initialDelayMillis: Long,
        factor: Int,
        block: suspend (attempt: Int) -> T,
    ): T {
        var backoff = initialDelayMillis
        for (attempt in 1 until times) {
            try {
                return block(attempt)
            } catch (e: HttpError) {
                logger.debug("Try $attempt failed (${e.message}); next try in $backoff ms")
            }
            pause(backoff)
            backoff *= factor
        }
        return block(times)
    }

    /**
     * Producer-consumer, as a coffee bar: the cashier takes orders and puts them on the order rail,
     * a channel with room for 3; the barista takes the next order from the rail and makes the drink.
     * The barista waits for the first order (0-0.7 s). In the morning rush six orders come in within
     * 4 s, faster than the barista makes drinks: the rail holds 3 orders at 2.5 s, and with order 6
     * the cashier waits for a free place (3.7-4.7 s). In the quiet spell an order comes every 3 s,
     * the barista catches up and waits for the next one (12.7-13.7 s and 14.7-16.7 s). The cashier
     * closes the rail after order 10, and the barista is done at about 17.7 s.
     */
    suspend fun runProducerConsumer(session: VizSession): Job =
        coroutineScope {
            logger.info("Starting producer-consumer scenario in session: ${session.sessionId}")

            val viz = session.demoScope()
            val orders = viz.vizChannel<String>(ORDER_RAIL_PLACES, "orders")

            viz.vizLaunch("coffee-bar") {
                vizLaunch("cashier") {
                    ORDER_TAKING_MILLIS.forEachIndexed { i, millis ->
                        pause(millis) // take the order
                        orders.send("order-${i + 1}") // waits only while the rail is full
                    }
                    orders.close()
                    logger.debug("Cashier: all orders taken")
                }
                vizLaunch("barista") {
                    while (true) {
                        val order = orders.receiveCatching().getOrNull() ?: break // waits while the rail is empty
                        pause(DRINK_MILLIS[order.removePrefix("order-").toInt() - 1]) // make the drink
                    }
                    logger.debug("Barista: the rail is closed and empty")
                }
            }
        }

    /**
     * Fan-out / fan-in, as a phone making a holiday album: `pick-photos` sends 9 photos, one every
     * 0.6 s, into a channel that three resizers read (fan-out). Each photo goes to exactly one free
     * resizer, who needs 1-3 s for it and sends the result into a second channel. The album reads
     * that one (fan-in) and adds each photo in 1 s, in the order the photos are finished (2, 1, 3,
     * 5, 4, …), not the order they were picked. The resizers are done at about 7.8 s; `make-album`
     * then closes the results, so the album knows it has every photo, and is done at about 11.7 s.
     */
    suspend fun runFanOutFanIn(session: VizSession): Job =
        coroutineScope {
            logger.info("Starting fan-out/fan-in scenario in session: ${session.sessionId}")

            val viz = session.demoScope()
            val photos = viz.vizChannel<String>(Channel.BUFFERED, "photos")
            val resized = viz.vizChannel<String>(Channel.BUFFERED, "resized")

            viz.vizLaunch("make-album") {
                vizLaunch("pick-photos") {
                    for (photo in 1..RESIZE_MILLIS.size) {
                        pause(600) // pick the next photo
                        photos.send("photo-$photo")
                    }
                    photos.close()
                    logger.debug("All photos picked")
                }
                val resizers =
                    (1..RESIZERS).map { resizer ->
                        vizLaunch("resizer-$resizer") {
                            while (true) {
                                val photo = photos.receiveCatching().getOrNull() ?: break // waits for a photo
                                pause(RESIZE_MILLIS[photo.removePrefix("photo-").toInt() - 1]) // resize it
                                resized.send(photo)
                            }
                        }
                    }
                vizLaunch("album") {
                    while (true) {
                        val photo = resized.receiveCatching().getOrNull() ?: break // waits for a finished photo
                        logger.debug("Album: adding $photo")
                        pause(1000) // add it to the album
                    }
                    logger.debug("Album: complete")
                }
                resizers.joinAll()
                resized.close()
            }
        }

    /**
     * The supervisor pattern, as a phone's nightly backup: photos (5 x 2.5 s), contacts (4 x 2 s)
     * and messages back up side by side under `supervisorScope`. The message backup fails at 4 s:
     * the server is out of space (HTTP 507). Under a supervisor that failure does not cancel the
     * others: contacts finish at 8 s and photos at 12.5 s. Then the phone sends a report (1 s),
     * "2 of 3 backed up", and `nightly-backup` completes, at about 13.5 s.
     */
    suspend fun runSupervisor(session: VizSession): Job =
        coroutineScope {
            logger.info("Starting supervisor scenario in session: ${session.sessionId}")

            val viz = session.demoScope()

            viz.vizLaunch("nightly-backup") {
                val backups =
                    supervisorScope {
                        listOf(
                            vizLaunch("backup-photos") { repeat(5) { pause(2500) } },
                            vizLaunch("backup-contacts") { repeat(4) { pause(2000) } },
                            vizLaunch("backup-messages") {
                                pause(2000)
                                pause(2000)
                                throw HttpError(507, "the backup server is out of space").intendedByDemo()
                            },
                        )
                    }
                // supervisorScope returns once all three have ended; a failed one is cancelled.
                val done = backups.count { !it.isCancelled }
                vizLaunch("send-report") {
                    pause(1000)
                    logger.debug("Backup report: $done of ${backups.size} backed up")
                }
            }
        }

    /**
     * A circuit breaker, as a shop charging orders through a payment provider that is down until
     * 12 s. A charge to the down provider takes 1.5 s and fails (HTTP 503); to the working one it
     * takes 1 s. Orders come one at a time, the next 2 s after the last is done.
     *
     * Orders 1-3 fail; after the third failure in a row (8.5 s) the breaker opens for 5.5 s. Orders
     * 4 (10.5 s) and 5 (12.5 s) are rejected at once, without calling the provider. From 14 s the
     * breaker is half-open: it lets the next order, 6 at 14.5 s, through as a trial. The provider
     * is back, the trial succeeds and the breaker closes; orders 7 and 8 are paid too, the last at
     * about 21.5 s. Each order runs under `supervisorScope`, so a failed order does not stop the
     * checkout.
     *
     * The provider's recovery and the breaker's cooldown read [clockNanos]. A test passes a virtual
     * clock with its test dispatcher in [context], so steps, recovery and cooldown share one clock
     * and the story is exact; on real time a very slow scheduler could still bring order 5 after
     * the cooldown.
     */
    suspend fun runCircuitBreaker(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
        clockNanos: () -> Long = System::nanoTime,
    ): Job =
        coroutineScope {
            logger.info("Starting circuit breaker scenario in session: ${session.sessionId}")

            val viz = session.demoScope(context)
            val start = clockNanos()
            val providerBackAt = start + viz.scaled(12_000) * NANOS_PER_MILLI
            val breaker =
                CircuitBreaker(failureThreshold = 3, openNanos = viz.scaled(BREAKER_OPEN_MILLIS) * NANOS_PER_MILLI, clock = clockNanos)

            viz.vizLaunch("checkout") {
                supervisorScope {
                    for (order in 1..ORDERS) {
                        vizLaunch("order-$order") {
                            breaker.call {
                                coroutineScope {
                                    vizLaunch("charge-$order") {
                                        if (clockNanos() < providerBackAt) {
                                            pause(1500) // no answer for a while
                                            throw HttpError(503, "the payment provider is down").intendedByDemo()
                                        }
                                        pause(1000)
                                    }
                                }
                            }
                        }.join()
                        if (order < ORDERS) pause(ORDER_GAP_MILLIS) // the next customer pays
                    }
                }
            }
        }

    private const val RETRY_TIMES = 4
    private const val OVERLOADED_ANSWERS = 3
    private const val ORDER_RAIL_PLACES = 3
    private const val RESIZERS = 3
    private const val ORDERS = 8
    private const val NANOS_PER_MILLI = 1_000_000L

    /** How long after one order of the Circuit Breaker demo is done the next customer pays, in scripted milliseconds. */
    private const val ORDER_GAP_MILLIS = 2_000L

    /**
     * How long the Circuit Breaker demo's breaker stays open, in scripted milliseconds: order 5 comes
     * 4 s after it opens and order 6 comes 6 s after, so a live run has room either side.
     */
    private const val BREAKER_OPEN_MILLIS = 5_500L
}

/** A circuit breaker's answer while it is open: the call is not made at all. */
internal class CircuitOpen(
    remainingMillis: Long,
) : RuntimeException("Circuit open: calls paused for another $remainingMillis ms")

/**
 * The Circuit Breaker demo's breaker. Closed: calls go through. After [failureThreshold] failures
 * in a row it opens: for [openNanos] every call fails at once with [CircuitOpen], without running.
 * Then it is half-open: the next call is a trial; if it succeeds the breaker closes, if it fails the
 * breaker opens again. Cancellation is not a failure. Meant for one call at a time, as the demo's
 * orders come; [clock] (nanoseconds) is replaceable for tests.
 */
internal class CircuitBreaker(
    private val failureThreshold: Int,
    private val openNanos: Long,
    private val clock: () -> Long = System::nanoTime,
) {
    @Volatile private var failures = 0

    @Volatile private var openedAt: Long? = null

    // A breaker counts every failure of the call it guards, whatever its type; cancellation is not one.
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> call(block: suspend () -> T): T {
        val opened = openedAt
        if (opened != null) rejectWhileOpen(opened)
        try {
            return block().also {
                failures = 0
                openedAt = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures++
            // A failed trial (half-open) opens it again at once.
            if (opened != null || failures >= failureThreshold) openedAt = clock()
            throw e
        }
    }

    private fun rejectWhileOpen(openedAt: Long) {
        val open = clock() - openedAt
        if (open < openNanos) throw CircuitOpen((openNanos - open) / 1_000_000).intendedByDemo()
    }
}
