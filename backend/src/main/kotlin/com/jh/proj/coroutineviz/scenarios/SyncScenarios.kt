package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Real-world synchronization scenario examples.
 *
 * The Sync: Mutex demos tell everyday stories at a pace a person can follow, like the Patterns
 * ([PatternScenarios]): 0.5-3 s a step, 10-30 s a demo, at the configured `scenarios.timeScale`.
 * Each returns the demo's root coroutine, which keeps running after the call returns. A coroutine
 * waiting for a `VizMutex` is recorded as suspended, and resumed when it gets the lock unless its
 * own job is cancelled. Each takes a [CoroutineContext] so a test can run it on virtual time.
 *
 * The Semaphore and Combined demos below still take a scope and end within a second or two.
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

    /**
     * Scenario: Database Connection Pool
     *
     * Real-world use case: Limiting concurrent database connections.
     * Too many connections can overwhelm the database server.
     */
    suspend fun databaseConnectionPool(scope: VizScope) {
        val connectionPool = scope.vizSemaphore("db-connections", permits = 3)

        // 10 concurrent queries, but only 3 connections available
        val jobs =
            (1..10).map { queryId ->
                scope.vizLaunch("query-$queryId") {
                    connectionPool.withPermit {
                        println("Query $queryId: Acquired connection")
                        // Simulate query execution
                        vizDelay((50..200).random().toLong())
                        println("Query $queryId: Released connection")
                    }
                }
            }

        jobs.forEach { it.join() }
    }

    /**
     * Scenario: API Rate Limiter
     *
     * Real-world use case: Limiting concurrent API calls to external service.
     * Prevents overwhelming the API and getting rate-limited.
     */
    suspend fun apiRateLimiter(scope: VizScope) {
        val rateLimiter = scope.vizSemaphore("api-rate-limit", permits = 5)

        // Batch of 20 API calls
        val results =
            (1..20).map { requestId ->
                scope.vizAsync("api-call-$requestId") {
                    rateLimiter.withPermit {
                        println("Request $requestId: Calling API...")
                        // Simulate API call latency
                        vizDelay((100..300).random().toLong())
                        "Response for request $requestId"
                    }
                }
            }

        results.forEach { deferred ->
            println(deferred.await())
        }
    }

    /**
     * Scenario: Parallel File Processor
     *
     * Real-world use case: Processing files with limited I/O parallelism.
     * Too many concurrent file operations can saturate disk I/O.
     */
    suspend fun parallelFileProcessor(scope: VizScope) {
        val ioSemaphore = scope.vizSemaphore("io-parallelism", permits = 4)

        data class FileTask(
            val name: String,
            val sizeKb: Int,
        )

        val files =
            listOf(
                FileTask("report.pdf", 500),
                FileTask("data.csv", 1200),
                FileTask("image1.jpg", 800),
                FileTask("image2.jpg", 750),
                FileTask("log.txt", 200),
                FileTask("backup.zip", 2000),
                FileTask("config.json", 10),
                FileTask("video.mp4", 5000),
            )

        val jobs =
            files.map { file ->
                scope.vizLaunch("process-${file.name}") {
                    ioSemaphore.withPermit {
                        println("Processing ${file.name}...")
                        // Simulate I/O time proportional to file size
                        vizDelay((file.sizeKb / 10).toLong())
                        println("Completed ${file.name}")
                    }
                }
            }

        jobs.forEach { it.join() }
    }

    /**
     * Scenario: Resource Pool with Timeout
     *
     * Real-world use case: Acquiring resources with timeout to prevent
     * indefinite waiting in overloaded systems.
     */
    suspend fun resourcePoolWithTimeout(scope: VizScope) {
        val resourcePool = scope.vizSemaphore("limited-resources", permits = 2)

        // Rapid requests that will cause some timeouts
        val results =
            (1..8).map { userId ->
                scope.vizAsync("user-$userId") {
                    val acquired =
                        withTimeoutOrNull(500) {
                            resourcePool.acquire()
                            true
                        } ?: false

                    if (acquired) {
                        try {
                            println("User $userId: Using resource")
                            // Use resource
                            vizDelay(300)
                            true
                        } finally {
                            resourcePool.release()
                            println("User $userId: Released resource")
                        }
                    } else {
                        println("User $userId: Timeout waiting for resource")
                        false
                    }
                }
            }

        val successCount = results.count { it.await() }
        println("Successful: $successCount / ${results.size}")
    }

    /**
     * Scenario: Producer-Consumer with Bounded Buffer
     *
     * Real-world use case: Message queue with limited buffer.
     * Producers wait when buffer is full, consumers wait when empty.
     */
    suspend fun producerConsumerBuffer(scope: VizScope) {
        val bufferSize = 5
        val emptySlots = scope.vizSemaphore("buffer-empty-slots", permits = bufferSize)
        // F10: "full slots" starts empty (0 available) with capacity = bufferSize. kotlinx Semaphore
        // forbids permits = 0, so model it as fully-acquired: permits = bufferSize, acquiredPermits = bufferSize.
        val fullSlots = scope.vizSemaphore("buffer-full-slots", permits = bufferSize, acquiredPermits = bufferSize)
        val bufferLock = scope.vizMutex("buffer-access")

        val buffer = ArrayDeque<Int>()

        suspend fun produce(item: Int) {
            // Wait for empty slot
            emptySlots.acquire()
            bufferLock.withLock {
                buffer.addLast(item)
                println("Produced: $item, buffer size: ${buffer.size}")
            }
            // Signal item available
            fullSlots.release()
        }

        suspend fun consume(): Int {
            // Wait for item
            fullSlots.acquire()
            val item =
                bufferLock.withLock {
                    buffer.removeFirst().also {
                        println("Consumed: $it, buffer size: ${buffer.size}")
                    }
                }
            // Signal slot available
            emptySlots.release()
            return item
        }

        // Start producers
        val producers =
            (1..3).map { producerId ->
                scope.vizLaunch("producer-$producerId") {
                    repeat(5) { item ->
                        produce(producerId * 100 + item)
                        vizDelay((10..50).random().toLong())
                    }
                }
            }

        // Start consumers
        val consumers =
            (1..2).map { consumerId ->
                scope.vizLaunch("consumer-$consumerId") {
                    repeat(7) {
                        consume()
                        vizDelay((20..80).random().toLong())
                    }
                }
            }

        producers.forEach { it.join() }
        consumers.forEach { it.join() }
    }

    // ========================================================================
    // COMBINED SCENARIOS
    // ========================================================================

    /**
     * Scenario: E-commerce Order Processing
     *
     * Real-world use case: Processing orders with inventory management.
     * Combines mutex for inventory updates and semaphore for payment processing.
     */
    suspend fun ecommerceOrderProcessing(scope: VizScope) {
        val inventory =
            mutableMapOf(
                "laptop" to 5,
                "phone" to 10,
                "tablet" to 3,
            )
        val inventoryLock = scope.vizMutex("inventory-lock")
        val paymentGateway = scope.vizSemaphore("payment-gateway", permits = 2)

        data class Order(
            val id: Int,
            val product: String,
            val quantity: Int,
        )

        suspend fun processOrder(order: Order): Boolean {
            // Check and reserve inventory (mutex)
            val reserved =
                inventoryLock.withLock {
                    val available = inventory[order.product] ?: 0
                    if (available >= order.quantity) {
                        inventory[order.product] = available - order.quantity
                        println("Order ${order.id}: Reserved ${order.quantity} ${order.product}")
                        true
                    } else {
                        println("Order ${order.id}: Insufficient stock for ${order.product}")
                        false
                    }
                }

            if (!reserved) return false

            // Process payment (semaphore-limited)
            val paid =
                try {
                    paymentGateway.withPermit {
                        println("Order ${order.id}: Processing payment...")
                        delay((100..300).random().toLong())
                        // Simulate 90% success rate
                        kotlin.random.Random.nextDouble() < 0.9
                    }
                } catch (e: Exception) {
                    false
                }

            if (!paid) {
                // Rollback inventory
                inventoryLock.withLock {
                    inventory[order.product] = (inventory[order.product] ?: 0) + order.quantity
                    println("Order ${order.id}: Payment failed, inventory restored")
                }
                return false
            }

            println("Order ${order.id}: Completed successfully!")
            return true
        }

        val orders =
            listOf(
                Order(1, "laptop", 2),
                Order(2, "phone", 3),
                Order(3, "tablet", 1),
                Order(4, "laptop", 2),
                Order(5, "phone", 5),
                Order(6, "tablet", 3),
                // This will fail - not enough stock
                Order(7, "laptop", 3),
            )

        val results =
            orders.map { order ->
                scope.vizAsync("order-${order.id}") {
                    processOrder(order)
                }
            }

        val successCount = results.count { it.await() }
        println("Successfully processed: $successCount / ${orders.size} orders")
        println("Final inventory: $inventory")
    }
}
