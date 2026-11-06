package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Real-world synchronization scenario examples.
 *
 * The Sync: Mutex, Sync: Semaphore and Combined demos tell everyday stories at a pace a person can
 * follow, like the Patterns ([PatternScenarios]): 0.5-3 s a step, 10-30 s a demo, at the configured
 * `scenarios.timeScale`. Each returns the demo's root coroutine, which keeps running after the call
 * returns; the root's body only starts its children. A coroutine waiting for a `VizMutex` or a
 * `VizSemaphore` permit is recorded as suspended, and resumed when it gets the lock or the permit
 * (or when a timeout it survives ends the wait) unless its own job is cancelled. Each takes a
 * [CoroutineContext] so a test can run it on virtual time.
 */
object SyncScenarios {
    private val logger = LoggerFactory.getLogger(SyncScenarios::class.java)

    // ========================================================================
    // MUTEX SCENARIOS
    // ========================================================================

    /** How long each gate of the Counter demo takes to scan its visitors' tickets, in scripted ms. */
    private val TICKET_SCAN_MILLIS =
        listOf(
            listOf(500L, 1000, 1000),
            listOf(1000L, 2000, 2500),
            listOf(1500L, 2000, 2000),
        )

    /**
     * A thread-safe counter, as three festival gates counting visitors into one shared count. Each
     * gate scans a ticket (0.5-2.5 s), then updates the count under the lock `visitor-count`: it reads
     * the count, takes 1.5 s to show the new number on the board, and writes it. Without the lock two
     * gates could read the same count, and one visitor would be lost. Only gate-1's first visitor
     * finds the lock free; the other eight updates wait for it, at times two gates at once (1.5-2 s,
     * 3-3.5 s, 7.5-8 s). The lock goes gate-1, 2, 3, 1, 2, 3, 1, 2, 3: gate-1 is done at 11 s, gate-2
     * holds the lock from 11 s to 12.5 s while gate-3 waits for it from 11.5 s, and the count is 9 at
     * 14 s. [onCounted] gets it.
     */
    suspend fun runCounter(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
        onCounted: (visitors: Int) -> Unit = {},
    ): Job =
        coroutineScope {
            logger.info("Starting thread-safe counter scenario in session: ${session.sessionId}")

            val viz = session.demoScope(context)
            val lock = viz.vizMutex("visitor-count")
            var visitors = 0

            viz
                .vizLaunch("count-visitors") {
                    TICKET_SCAN_MILLIS.forEachIndexed { i, scans ->
                        vizLaunch("gate-${i + 1}") {
                            for (scan in scans) {
                                pause(scan) // scan the ticket
                                lock.withLock {
                                    val seen = visitors
                                    pause(1500) // show seen + 1 on the board
                                    visitors = seen + 1
                                }
                            }
                        }
                    }
                }.also { root ->
                    root.invokeOnCompletion { if (it == null) onCounted(visitors) }
                }
        }

    /** One account of the Bank Transfer and Deadlock demos; its balance is only read or changed under [lock]. */
    private class Account(
        val owner: String,
        var balance: Int,
        val lock: VizMutex,
    )

    /** One transfer of the Bank Transfer demo: who pays whom, how much, and when it comes after the one before. */
    private data class Transfer(
        val label: String,
        val fromAnna: Boolean,
        val amount: Int,
        val gapMillis: Long,
    )

    private val HOUSEHOLD_TRANSFERS =
        listOf(
            Transfer("pay-rent", fromAnna = true, amount = 400, gapMillis = 1000),
            Transfer("pay-back-dinner", fromAnna = false, amount = 50, gapMillis = 1000),
            Transfer("split-groceries", fromAnna = true, amount = 120, gapMillis = 1000),
            Transfer("book-holiday", fromAnna = false, amount = 1200, gapMillis = 2000),
        )

    /**
     * Bank transfers, as a household's bills: Anna (1000) and Ben (500) send each other money. A
     * transfer locks both accounts, always in the same order, Anna's first, then Ben's. With both
     * locked it checks the payer's balance (1 s), takes the money (1 s) and adds it to the other
     * account (1 s); then it unlocks and sends a receipt (1 s). The transfers come at 1, 2, 3 and
     * 5 s, so each waits for Anna's account while the one before holds it, Ben paying Anna too: had
     * that transfer locked Ben's account first, it could deadlock (see [runDeadlock]). Ben's account
     * is always free by the time a transfer asks for it. The holiday (Ben pays 1200) is declined:
     * Ben has 970. Anna ends with 530, Ben with 970, at 12 s; [onSettled] gets the balances.
     */
    suspend fun runBankTransfer(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
        onSettled: (anna: Int, ben: Int) -> Unit = { _, _ -> },
    ): Job =
        coroutineScope {
            logger.info("Starting bank transfer scenario in session: ${session.sessionId}")

            val viz = session.demoScope(context)
            val anna = Account("Anna", 1000, viz.vizMutex("account-anna"))
            val ben = Account("Ben", 500, viz.vizMutex("account-ben"))

            suspend fun VizScope.transfer(
                from: Account,
                to: Account,
                amount: Int,
            ) {
                // The same order for every transfer, whoever pays: by owner.
                val (first, second) = listOf(from, to).sortedBy { it.owner }
                val done =
                    first.lock.withLock {
                        second.lock.withLock {
                            pause(1000) // check the payer's balance
                            if (from.balance < amount) {
                                false
                            } else {
                                from.balance -= amount
                                pause(1000) // take the money
                                to.balance += amount
                                pause(1000) // and add it to the other account
                                true
                            }
                        }
                    }
                logger.debug("${from.owner} pays ${to.owner} $amount: ${if (done) "done" else "declined, not enough money"}")
                pause(1000) // send the receipt, or tell the payer it was declined
            }

            viz
                .vizLaunch("transfers") {
                    for (t in HOUSEHOLD_TRANSFERS) {
                        pause(t.gapMillis)
                        vizLaunch(t.label) { if (t.fromAnna) transfer(anna, ben, t.amount) else transfer(ben, anna, t.amount) }
                    }
                }.also { root -> root.invokeOnCompletion { if (it == null) onSettled(anna.balance, ben.balance) } }
        }

    /** One request of the Cache demo: which product it asks for, and how long after the one before it comes. */
    private val PRICE_REQUESTS =
        listOf(
            "espresso" to 500L,
            "espresso" to 1000L,
            "espresso" to 1000L,
            "espresso" to 2500L,
            "grinder" to 1000L,
            "espresso" to 1000L,
            "grinder" to 1000L,
            "grinder" to 2500L,
        )

    /**
     * A read-through cache with a lock, as a shop server answering price requests from a cache that
     * starts empty. A request reads the cache first, without the lock. On a miss it takes the lock
     * `price-cache`, checks again, and only if the price is still missing loads it from the database
     * (3 s, a child `load-espresso` or `load-grinder`); then it renders the page (1 s).
     *
     * Request 1 loads the espresso (0.5-3.5 s); requests 2 and 3 miss too and wait for the lock,
     * then find the price cached. Request 4 reads it from the cache without the lock. Request 5
     * loads the grinder (6-9 s); request 6 meanwhile gets the espresso from the cache although the
     * lock is held, and request 7 waits for the lock and finds the grinder cached. Request 8 reads
     * it from the cache. Two loads in all; the server is done at 11.5 s.
     *
     * The cache is a [ConcurrentHashMap], so reading it without the lock is safe while a load
     * writes it.
     */
    suspend fun runCache(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
    ): Job =
        coroutineScope {
            logger.info("Starting cache read-through scenario in session: ${session.sessionId}")

            val viz = session.demoScope(context)
            val lock = viz.vizMutex("price-cache")
            val prices = ConcurrentHashMap<String, String>()

            suspend fun VizScope.price(product: String): String =
                prices[product] // 1. cached? No lock needed to read.
                    ?: lock.withLock {
                        prices[product] // 2. loaded meanwhile, while this request waited for the lock?
                            ?: vizAsync("load-$product") {
                                pause(3000) // 3. ask the database
                                DATABASE_PRICES.getValue(product)
                            }.await().also { prices[product] = it }
                    }

            viz.vizLaunch("shop-server") {
                PRICE_REQUESTS.forEachIndexed { i, (product, gapMillis) ->
                    pause(gapMillis) // the next request comes in
                    vizLaunch("request-${i + 1}") {
                        val price = price(product)
                        logger.debug("request-${i + 1}: $product costs $price")
                        pause(1000) // render the page
                    }
                }
            }
        }

    /**
     * A deadlock, as two transfers at month end: Anna pays Ben and Ben pays Anna at the same moment,
     * but each transfer locks its payer's account first. Both lock their payer's account at once;
     * `ben-pays-anna` checks Ben's balance (1 s) and asks for Anna's account at 1 s, `anna-pays-ben`
     * checks Anna's (1.5 s) and asks for Ben's at 1.5 s. Each holds the account the other needs, so
     * both wait, suspended, for a lock that is never released.
     *
     * A `watchdog` checks every 2 s whether a transfer has finished. At 10 s neither has: it cancels
     * both, which ends their waits without a resume and releases their accounts, and writes an
     * incident report (1 s). The demo ends at 11 s.
     *
     * By default the demo's coroutines run one at a time ([context] is a dispatcher with parallelism
     * 1), which makes the story certain. Both transfers take their first account before any step can
     * end, so the deadlock forms however slow the threads. And the watchdog cancels both transfers
     * before either can release its account: run in parallel, the first one cancelled could hand its
     * account to the other, which would resume and finish its transfer. A test passes its own
     * single-threaded test dispatcher.
     */
    suspend fun runDeadlock(
        session: VizSession,
        context: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
    ): Job =
        coroutineScope {
            logger.info("Starting deadlock scenario in session: ${session.sessionId}")

            val viz = session.demoScope(context)
            val anna = Account("Anna", 1000, viz.vizMutex("account-anna"))
            val ben = Account("Ben", 500, viz.vizMutex("account-ben"))

            // The mistake: each transfer locks its payer's account first.
            suspend fun VizScope.payerFirst(
                from: Account,
                to: Account,
                amount: Int,
                checkMillis: Long,
            ) {
                from.lock.withLock {
                    pause(checkMillis) // check the payer's balance
                    to.lock.withLock {
                        from.balance -= amount // never reached: the other transfer holds this account
                        to.balance += amount
                    }
                }
            }

            viz.vizLaunch("month-end") {
                val transfers =
                    listOf(
                        vizLaunch("anna-pays-ben") { payerFirst(anna, ben, 800, checkMillis = 1500) },
                        vizLaunch("ben-pays-anna") { payerFirst(ben, anna, 300, checkMillis = 1000) },
                    )
                vizLaunch("watchdog") {
                    repeat(WATCHDOG_CHECKS) {
                        pause(2000)
                        if (transfers.any { it.isCompleted }) return@vizLaunch
                    }
                    logger.debug("Watchdog: no transfer finished in 10 s, cancelling both")
                    transfers.forEach { it.cancel() } // one at a time: both, before either releases its account
                    pause(1000) // write the incident report
                }
            }
        }

    private const val WATCHDOG_CHECKS = 5

    /** What the Cache demo's database answers: the espresso machine's and the coffee grinder's price. */
    private val DATABASE_PRICES = mapOf("espresso" to "349 EUR", "grinder" to "89 EUR")

    // ========================================================================
    // SEMAPHORE SCENARIOS
    // ========================================================================

    /** One lunchtime request of the Connection Pool demo: its name and the steps of its query, in scripted ms. */
    private val LUNCHTIME_REQUESTS =
        listOf(
            "show-basket" to listOf(2500L),
            "search-shoes" to listOf(3000L),
            "order-history" to listOf(2000L, 1500), // read the orders, then the invoices
            "track-parcel" to listOf(2500L),
            "write-review" to listOf(2000L),
            "checkout" to listOf(1500L, 2000), // check the stock, then save the order
            "wish-list" to listOf(2500L),
            "store-hours" to listOf(1500L),
            "loyalty-points" to listOf(2500L),
        )

    /**
     * A database connection pool, as an online shop's backend at lunchtime. The pool `db-connections` has 3
     * connections. A request comes in every 0.5 s, nine in all. Each takes a connection, runs its query (one or two
     * steps, 1.5-3 s) and gives the connection back, then renders its page (1 s) without it. The first three find a
     * connection free; the other six wait for one, suspended, and get it in the order they asked: track-parcel at
     * 3 s, write-review at 4 s, checkout at 5 s, wish-list at 5.5 s, store-hours at 6 s and loyalty-points at 7.5 s.
     * Never more than three queries run at once. The shop is done at 11 s.
     */
    suspend fun runConnectionPool(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
    ): Job =
        coroutineScope {
            logger.info("Starting connection pool scenario in session: ${session.sessionId}")

            val viz = session.demoScope(context)
            val pool = viz.vizSemaphore("db-connections", permits = 3)

            viz.vizLaunch("shop-backend") {
                for ((request, query) in LUNCHTIME_REQUESTS) {
                    pause(500) // the next request comes in
                    vizLaunch(request) {
                        pool.withPermit {
                            for (step in query) pause(step) // run the query on the connection
                        }
                        pause(1000) // render the page, without the connection
                    }
                }
            }
        }

    /** What the Rate Limiter demo's weather service says for each city, and how long it takes to answer, in scripted ms. */
    private val CITY_WEATHER =
        listOf(
            Triple("rome", "24°C, sunny", 2500L),
            Triple("paris", "17°C, showers", 3000L),
            Triple("vienna", "19°C, cloudy", 2000L),
            Triple("prague", "16°C, windy", 3000L),
            Triple("lisbon", "26°C, sunny", 2500L),
            Triple("oslo", "11°C, rain", 2000L),
            Triple("split", "27°C, sunny", 3000L),
            Triple("krakow", "15°C, cloudy", 2500L),
        )

    /**
     * An API rate limiter, as a trip planner showing the weather for eight cities. The weather service takes at most
     * two calls at a time, so every call goes through the semaphore `weather-api` (2 permits). The planner asks for
     * all eight forecasts at once (`weather-rome` … `weather-krakow`, each an async call of 2-3 s), and `trip-page`
     * awaits them in order, then draws the page (1.5 s). Rome and Paris call at once; the other six wait for a
     * permit, suspended, and call as soon as one is free: Vienna at 2.5 s, Prague at 3 s, Lisbon at 4.5 s, Oslo at
     * 6 s, Split at 7 s, Krakow at 8 s. The last forecast is in at 10.5 s and the page is drawn at 12 s; [onPlanned]
     * gets the forecasts in city order.
     */
    suspend fun runRateLimiter(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
        onPlanned: (forecasts: List<String>) -> Unit = {},
    ): Job =
        coroutineScope {
            logger.info("Starting API rate limiter scenario in session: ${session.sessionId}")

            val viz = session.demoScope(context)
            val weatherApi = viz.vizSemaphore("weather-api", permits = 2)

            viz.vizLaunch("trip-planner") {
                val forecasts =
                    CITY_WEATHER.map { (city, weather, millis) ->
                        vizAsync("weather-$city") {
                            weatherApi.withPermit {
                                pause(millis) // ask the weather service
                            }
                            "$city: $weather"
                        }
                    }
                vizLaunch("trip-page") {
                    val all = forecasts.map { it.await() }
                    pause(1500) // draw the page
                    onPlanned(all)
                }
            }
        }

    /** One file of the File Processor demo's tax folder and the steps it takes to compress, in scripted ms. */
    private val TAX_FOLDER =
        listOf(
            "payslips.pdf" to listOf(1500L),
            "bank-statements.pdf" to listOf(3000L, 3000, 1500), // a big file, in three parts
            "receipts.zip" to listOf(2500L),
            "insurance.pdf" to listOf(1000L),
            "donations.pdf" to listOf(2000L),
            "pension.pdf" to listOf(1500L),
            "childcare.pdf" to listOf(2500L),
            "invoices.zip" to listOf(3000L),
            "doctor-bills.pdf" to listOf(2000L),
        )

    /**
     * A parallel file processor, as an app that compresses the nine files of a tax folder before they go to the
     * accountant. The disk is fastest with two files at a time, so each file takes a permit of `disk` (2 permits)
     * while it is compressed (one to three steps, 1-3 s), then uploads (1 s) without it. All nine start at once. The
     * bank statements hold one permit from 0 to 7.5 s; meanwhile the other permit goes from file to file the moment
     * one is done: payslips, receipts (1.5 s), insurance (4 s), donations (5 s). Seven files wait for the disk,
     * suspended. The last file is uploaded at 13 s.
     */
    suspend fun runFileProcessor(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
    ): Job =
        coroutineScope {
            logger.info("Starting parallel file processor scenario in session: ${session.sessionId}")

            val viz = session.demoScope(context)
            val disk = viz.vizSemaphore("disk", permits = 2)

            viz.vizLaunch("tax-folder") {
                for ((file, parts) in TAX_FOLDER) {
                    vizLaunch(file) {
                        disk.withPermit {
                            for (part in parts) pause(part) // compress the file, or the next part of it
                        }
                        pause(1000) // upload it to the accountant, without the disk
                    }
                }
            }
        }

    /** One shopper of the Resource Timeout demo: when they come after the one before, and how long they try on, in scripted ms. */
    private data class Shopper(
        val name: String,
        val gapMillis: Long,
        val tryOnMillis: Long,
    )

    private val SHOPPERS =
        listOf(
            Shopper("anna", gapMillis = 500, tryOnMillis = 2000),
            Shopper("ben", gapMillis = 500, tryOnMillis = 2500),
            Shopper("carla", gapMillis = 500, tryOnMillis = 2500),
            Shopper("dan", gapMillis = 500, tryOnMillis = 2000),
            Shopper("eva", gapMillis = 500, tryOnMillis = 2000),
            Shopper("finn", gapMillis = 500, tryOnMillis = 2000),
            Shopper("gina", gapMillis = 3000, tryOnMillis = 2500),
        )

    /** How long a shopper of the Resource Timeout demo waits for a fitting room before giving up, in scripted ms. */
    private const val FITTING_ROOM_PATIENCE_MILLIS = 3000L

    /**
     * A resource pool with a timeout, as the two fitting rooms of a clothes shop (`fitting-rooms`, 2 permits). A
     * shopper waits at most 3 s for a room (`withTimeoutOrNull`). With a room, they try the clothes on (2-2.5 s),
     * change back (1 s), give the room back and pay at the till (1 s); without one, they hang the clothes back and
     * leave (1 s).
     *
     * Anna (0.5 s) and Ben (1 s) find a room free. Carla (1.5 s) and Dan (2 s) wait, suspended, and get one at 3.5 s
     * and 4.5 s. Eva (2.5 s) and Finn (3 s) wait too, but no room comes free within 3 s: their waits end at 5.5 s and
     * 6 s, they go on and leave. Gina comes at 6 s, waits 1 s and gets Carla's room. The shop is done at 11.5 s;
     * [onClosed] gets who tried on, in the order they got a room, and who left without, in the order they gave up.
     *
     * Every timeout fires at least 0.5 s away from any room coming free, so no shopper gives up in the same moment a
     * room would have been theirs.
     */
    suspend fun runResourceTimeout(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
        onClosed: (triedOn: List<String>, leftWithout: List<String>) -> Unit = { _, _ -> },
    ): Job =
        coroutineScope {
            logger.info("Starting resource pool with timeout scenario in session: ${session.sessionId}")

            val viz = session.demoScope(context)
            val rooms = viz.vizSemaphore("fitting-rooms", permits = 2)
            val triedOn = ConcurrentLinkedQueue<String>()
            val leftWithout = ConcurrentLinkedQueue<String>()

            viz
                .vizLaunch("clothes-shop") {
                    for (shopper in SHOPPERS) {
                        pause(shopper.gapMillis) // the next shopper comes in
                        vizLaunch(shopper.name) {
                            val gotRoom = withTimeoutOrNull(scaled(FITTING_ROOM_PATIENCE_MILLIS)) { rooms.acquire() } != null
                            if (gotRoom) {
                                triedOn += shopper.name
                                try {
                                    pause(shopper.tryOnMillis) // try the clothes on
                                    pause(1000) // change back
                                } finally {
                                    rooms.release()
                                }
                                pause(1000) // pay at the till
                            } else {
                                leftWithout += shopper.name
                                pause(1000) // hang the clothes back and leave
                            }
                        }
                    }
                }.also { root -> root.invokeOnCompletion { if (it == null) onClosed(triedOn.toList(), leftWithout.toList()) } }
        }

    /** How long the Bounded Buffer demo's baker takes for each loaf, in scripted ms: the sixth is a big rye. */
    private val LOAF_BAKE_MILLIS = listOf(1000L, 1000, 1000, 1000, 1000, 1500, 1000, 1000)

    /** How many loaves fit on the Bounded Buffer demo's cooling rack. */
    private const val RACK_PLACES = 3

    /**
     * A producer and a consumer sharing a bounded buffer, as a bakery's cooling rack with room for 3 loaves. The
     * `baker` bakes a loaf (1 s, the sixth 1.5 s) and puts it on the rack; the `shop-assistant` takes loaves off, one
     * at a time, and sells each (2.5 s). Two semaphores count the rack: `free-places` (3 permits, all free) and
     * `loaves-ready` (3 permits, all taken at the start, so none free). The baker takes a free place and gives a
     * permit to `loaves-ready`; the assistant takes a ready loaf and gives a permit back to `free-places`. A small
     * lock `rack` guards the list of loaves itself.
     *
     * The assistant waits for the first loaf (0-1 s). The baker is faster, so the rack fills: at 7.5 s and again at
     * 9.5 s the baker waits for a free place, suspended, until the assistant takes a loaf (8.5 s, 11 s). The baker
     * is done at 11 s; the assistant sells the last loaf at 21 s. [onSold] gets the loaves in the order sold and the
     * most that were on the rack at once.
     */
    suspend fun runProducerConsumer(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
        onSold: (loaves: List<Int>, mostOnRack: Int) -> Unit = { _, _ -> },
    ): Job =
        coroutineScope {
            logger.info("Starting bounded buffer scenario in session: ${session.sessionId}")

            val viz = session.demoScope(context)
            val freePlaces = viz.vizSemaphore("free-places", permits = RACK_PLACES)
            // kotlinx forbids a semaphore with 0 permits: "none ready" is all 3 permits taken at the start.
            val loavesReady = viz.vizSemaphore("loaves-ready", permits = RACK_PLACES, acquiredPermits = RACK_PLACES)
            val rack = viz.vizMutex("rack")
            val onRack = ArrayDeque<Int>() // only read or changed under `rack`
            var mostOnRack = 0
            val sold = ConcurrentLinkedQueue<Int>()

            viz
                .vizLaunch("bakery") {
                    vizLaunch("baker") {
                        LOAF_BAKE_MILLIS.forEachIndexed { i, millis ->
                            pause(millis) // bake the next loaf
                            freePlaces.acquire() // a free place on the rack; waits while the rack is full
                            rack.withLock {
                                onRack.addLast(i + 1)
                                mostOnRack = maxOf(mostOnRack, onRack.size)
                            }
                            loavesReady.release() // one more loaf ready
                        }
                    }
                    vizLaunch("shop-assistant") {
                        repeat(LOAF_BAKE_MILLIS.size) {
                            loavesReady.acquire() // a loaf; waits while the rack is empty
                            val loaf = rack.withLock { onRack.removeFirst() }
                            freePlaces.release() // one more free place
                            pause(2500) // sell the loaf
                            sold += loaf
                        }
                    }
                }.also { root -> root.invokeOnCompletion { if (it == null) onSold(sold.toList(), mostOnRack) } }
        }

    // ========================================================================
    // COMBINED SCENARIOS
    // ========================================================================

    /** One order of the Combined demo: whose, when it comes after the one before, and whether the card is accepted. */
    private data class SneakerOrder(
        val customer: String,
        val gapMillis: Long,
        val cardAccepted: Boolean = true,
    )

    private val SNEAKER_ORDERS =
        listOf(
            SneakerOrder("anna", gapMillis = 500),
            SneakerOrder("ben", gapMillis = 500, cardAccepted = false),
            SneakerOrder("carla", gapMillis = 1000),
            SneakerOrder("dan", gapMillis = 1000),
            SneakerOrder("eva", gapMillis = 3500),
        )

    /**
     * Order processing with a mutex and a semaphore, as a limited sneaker drop: a web shop sells its last 3 pairs. An
     * order checks and reserves a pair under the lock `stock` (1 s), pays through a payment provider that takes two
     * payments at a time (`payment-gateway`, 2 permits; 2.5 s), and sends a confirmation (1 s). An order that finds
     * no pair tells the customer it is sold out (1 s). A declined card puts its pair back under `stock` (0.5 s).
     *
     * Anna (0.5 s), Ben (1 s), Carla (2 s) and Dan (3 s) each wait for the stock lock while the order before checks.
     * Anna, Ben and Carla reserve the three pairs; Carla waits for a payment permit (3.5-4 s) while Anna and Ben pay.
     * Dan finds none left at 4.5 s: Ben's pair is still reserved. Ben's card is declined at 5 s, and his pair goes
     * back. Eva comes at 6.5 s and buys it. Three pairs sold (Anna, Carla, Eva), none left, at 11 s; [onSettled] gets
     * the buyers in the order they paid and the pairs left.
     */
    suspend fun runOrderProcessing(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
        onSettled: (bought: List<String>, pairsLeft: Int) -> Unit = { _, _ -> },
    ): Job =
        coroutineScope {
            logger.info("Starting e-commerce order processing scenario in session: ${session.sessionId}")

            val viz = session.demoScope(context)
            val stock = viz.vizMutex("stock")
            val paymentGateway = viz.vizSemaphore("payment-gateway", permits = 2)
            var pairsLeft = 3 // only read or changed under `stock`
            val bought = ConcurrentLinkedQueue<String>()

            suspend fun VizScope.order(o: SneakerOrder) {
                val reserved =
                    stock.withLock {
                        pause(1000) // check the stock
                        (pairsLeft > 0).also { if (it) pairsLeft-- } // and reserve a pair, under the same lock
                    }
                if (!reserved) {
                    pause(1000) // tell the customer the drop is sold out
                    return
                }
                val paid = paymentGateway.withPermit {
                    pause(2500) // charge the card
                    o.cardAccepted
                }
                if (paid) {
                    bought += "order-${o.customer}"
                } else {
                    stock.withLock {
                        pause(500) // put the pair back
                        pairsLeft++
                    }
                }
                pause(1000) // send the confirmation, or say the card was declined
            }

            viz
                .vizLaunch("sneaker-drop") {
                    for (o in SNEAKER_ORDERS) {
                        pause(o.gapMillis) // the next order comes in
                        vizLaunch("order-${o.customer}") { order(o) }
                    }
                }.also { root -> root.invokeOnCompletion { if (it == null) onSettled(bought.toList(), pairsLeft) } }
        }
}
