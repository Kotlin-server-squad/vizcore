package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.auth.resolveTenant
import com.jh.proj.coroutineviz.scenarios.SyncScenarios
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SyncScenarioRoutes")

@Serializable
data class SyncScenarioResponse(
    val success: Boolean,
    val sessionId: String,
    val scenario: String,
    val message: String,
    val eventCount: Int = 0,
)

/**
 * Routes for synchronization primitive demonstration scenarios.
 *
 * These routes demonstrate real-world uses of Mutex and Semaphore
 * with full event visualization.
 */
fun Route.registerSyncScenarioRoutes() {
    // ========================================================================
    // MUTEX SCENARIOS
    // ========================================================================

    /**
     * Thread-safe counter using Mutex
     */
    demoGet("/api/sync/mutex/counter") {
        call.runSyncScenario("Thread-Safe Counter") { SyncScenarios.runCounter(it).join() }
    }

    /**
     * Bank account transfer with consistent lock ordering
     */
    demoGet("/api/sync/mutex/bank-transfer") {
        call.runSyncScenario("Bank Account Transfer") { SyncScenarios.runBankTransfer(it).join() }
    }

    /**
     * Cache with thread-safe read-through pattern
     */
    demoGet("/api/sync/mutex/cache") {
        call.runSyncScenario("Cache Read-Through") { SyncScenarios.runCache(it).join() }
    }

    /**
     * Deadlock demonstration (intentional)
     */
    demoGet("/api/sync/mutex/deadlock-demo") {
        call.runSyncScenario("Deadlock Demonstration") { SyncScenarios.runDeadlock(it).join() }
    }

    // ========================================================================
    // SEMAPHORE SCENARIOS
    // ========================================================================

    /**
     * Database connection pool limiting
     */
    demoGet("/api/sync/semaphore/connection-pool") {
        call.runSyncScenario("Database Connection Pool") { SyncScenarios.runConnectionPool(it).join() }
    }

    /**
     * API rate limiter
     */
    demoGet("/api/sync/semaphore/rate-limiter") {
        call.runSyncScenario("API Rate Limiter") { SyncScenarios.runRateLimiter(it).join() }
    }

    /**
     * Parallel file processor with I/O throttling
     */
    demoGet("/api/sync/semaphore/file-processor") {
        call.runSyncScenario("Parallel File Processor") { SyncScenarios.runFileProcessor(it).join() }
    }

    /**
     * Resource pool with timeout handling
     */
    demoGet("/api/sync/semaphore/resource-timeout") {
        call.runSyncScenario("Resource Pool with Timeout") { SyncScenarios.runResourceTimeout(it).join() }
    }

    /**
     * Producer-Consumer with bounded buffer
     */
    demoGet("/api/sync/semaphore/producer-consumer") {
        call.runSyncScenario("Producer-Consumer Buffer") { SyncScenarios.runProducerConsumer(it).join() }
    }

    // ========================================================================
    // COMBINED SCENARIOS
    // ========================================================================

    /**
     * E-commerce order processing (Mutex + Semaphore)
     */
    demoGet("/api/sync/combined/ecommerce") {
        call.runSyncScenario("E-commerce Order Processing") { SyncScenarios.runOrderProcessing(it).join() }
    }

    // ========================================================================
    // LIST ALL SCENARIOS
    // ========================================================================

    get("/api/sync/scenarios") {
        call.respond(
            HttpStatusCode.OK,
            listOf(
                ScenarioInfo(
                    "mutex/counter",
                    "Thread-Safe Counter: Festival Gates",
                    "Mutex",
                    "Three gates count festival visitors into one shared count. Each update holds a lock while it " +
                        "reads, shows and writes the count, so the other gates wait for it, suspended, and no visitor is " +
                        "lost.",
                ),
                ScenarioInfo(
                    "mutex/bank-transfer",
                    "Bank Account Transfer: Household Bills",
                    "Mutex",
                    "Anna and Ben send each other money. Each transfer locks both accounts, always Anna's first, so " +
                        "the transfers queue instead of deadlocking; the one Ben cannot afford is declined.",
                ),
                ScenarioInfo(
                    "mutex/cache",
                    "Cache Read-Through: Product Prices",
                    "Mutex",
                    "Eight requests for two prices meet an empty cache. The first miss loads a price under the lock; " +
                        "the others wait, check again and find it cached; later requests read the cache without the " +
                        "lock. Two loads in all.",
                ),
                ScenarioInfo(
                    "mutex/deadlock-demo",
                    "Deadlock Demo: Two Transfers at Month End",
                    "Mutex",
                    "Anna pays Ben and Ben pays Anna, each locking the payer's account first. Each holds one account " +
                        "and waits, suspended, for the other's. After 10 s a watchdog cancels both.",
                ),
                ScenarioInfo(
                    "semaphore/connection-pool",
                    "Database Connection Pool: Online Shop at Lunchtime",
                    "Semaphore",
                    "Nine lunchtime requests share an online shop's 3 database connections. The first three find " +
                        "one free; the other six wait for one, suspended, and get it in the order they asked.",
                ),
                ScenarioInfo(
                    "semaphore/rate-limiter",
                    "API Rate Limiter: Weather for a Trip",
                    "Semaphore",
                    "A trip planner asks for the weather in eight cities at once, but the weather service takes " +
                        "two calls at a time. Six calls wait for a permit, suspended, and the trip page waits for " +
                        "every forecast.",
                ),
                ScenarioInfo(
                    "semaphore/file-processor",
                    "Parallel File Processor: The Tax Folder",
                    "Semaphore",
                    "Nine files of a tax folder are compressed two at a time. While the big bank statements hold " +
                        "one permit, the other passes from file to file the moment one is done.",
                ),
                ScenarioInfo(
                    "semaphore/resource-timeout",
                    "Resource Pool with Timeout: Fitting Rooms",
                    "Semaphore",
                    "Seven shoppers share two fitting rooms and wait at most 3 s for one. Carla, Dan and Gina get " +
                        "a room in time; Eva and Finn give up and leave.",
                ),
                ScenarioInfo(
                    "semaphore/producer-consumer",
                    "Producer-Consumer Buffer: The Bakery Rack",
                    "Semaphore",
                    "A baker fills a cooling rack with room for 3 loaves faster than the shop assistant sells " +
                        "them. Two semaphores count the free places and the ready loaves: the baker waits when the " +
                        "rack is full, the assistant when it is empty.",
                ),
                ScenarioInfo(
                    "combined/ecommerce",
                    "E-commerce Orders: A Limited Sneaker Drop",
                    "Combined",
                    "Six orders for the last 3 pairs. Each reserves a pair under a lock, then pays through a " +
                        "provider that takes two payments at a time. A declined card puts its pair back for a later " +
                        "order; the others find it sold out.",
                ),
            ),
        )
    }

    // ========================================================================
    // SESSION-AWARE POST VARIANTS (Gallery / catalog run contract)
    //
    // The GET /api/sync/* routes above self-create a session and return a result.
    // The Gallery (and the unified scenario-run contract used by patterns/flow)
    // expects POST /api/scenarios/<id>?sessionId=<id> to run a scenario INTO an
    // existing session. These POST aliases provide that for sync scenarios so the
    // Gallery's Sync cards work like every other card.
    // ========================================================================
    demoPost("/api/scenarios/sync/mutex/counter") {
        call.runSyncScenarioInSession("Thread-Safe Counter") { SyncScenarios.runCounter(it).join() }
    }
    demoPost("/api/scenarios/sync/mutex/bank-transfer") {
        call.runSyncScenarioInSession("Bank Account Transfer") { SyncScenarios.runBankTransfer(it).join() }
    }
    demoPost("/api/scenarios/sync/mutex/cache") {
        call.runSyncScenarioInSession("Cache Read-Through") { SyncScenarios.runCache(it).join() }
    }
    demoPost("/api/scenarios/sync/mutex/deadlock-demo") {
        call.runSyncScenarioInSession("Deadlock Demo") { SyncScenarios.runDeadlock(it).join() }
    }
    demoPost("/api/scenarios/sync/semaphore/connection-pool") {
        call.runSyncScenarioInSession("Database Connection Pool") { SyncScenarios.runConnectionPool(it).join() }
    }
    demoPost("/api/scenarios/sync/semaphore/rate-limiter") {
        call.runSyncScenarioInSession("API Rate Limiter") { SyncScenarios.runRateLimiter(it).join() }
    }
    demoPost("/api/scenarios/sync/semaphore/file-processor") {
        call.runSyncScenarioInSession("Parallel File Processor") { SyncScenarios.runFileProcessor(it).join() }
    }
    demoPost("/api/scenarios/sync/semaphore/resource-timeout") {
        call.runSyncScenarioInSession("Resource Pool with Timeout") { SyncScenarios.runResourceTimeout(it).join() }
    }
    demoPost("/api/scenarios/sync/semaphore/producer-consumer") {
        call.runSyncScenarioInSession("Producer-Consumer Buffer") { SyncScenarios.runProducerConsumer(it).join() }
    }
    demoPost("/api/scenarios/sync/combined/ecommerce") {
        call.runSyncScenarioInSession("E-commerce Order Processing") { SyncScenarios.runOrderProcessing(it).join() }
    }
}

@Serializable
data class ScenarioInfo(
    val path: String,
    val name: String,
    val type: String,
    val description: String,
)

/**
 * Helper function to run a sync scenario with proper setup and response
 */
private suspend fun ApplicationCall.runSyncScenario(
    scenarioName: String,
    scenario: suspend (VizSession) -> Unit,
) {
    logger.info("┌─────────────────────────────────────────┐")
    logger.info("│  Running Sync Scenario                  │")
    logger.info("│  Scenario: $scenarioName".padEnd(42) + "│")
    logger.info("└─────────────────────────────────────────┘")

    try {
        // F9: create the session through the SAME tenant-scoped store the read/validate paths use
        // (mirrors the F5 fix on the scenario-runner/flow/pattern routes, which this file was missed
        // from). In DB+auth mode the unscoped SessionManager.createSession produced a session that
        // was orphaned from the authenticated tenant — invisible to /events, /validate and the
        // session list (all 404 / absent) despite the run reporting success.
        val name = "sync-${scenarioName.lowercase().replace(" ", "-")}"
        val store = tenantScopedStore()
        val session =
            if (store != null) {
                store.createSession(name, resolveTenant())
            } else {
                SessionManager.createSession(name)
            }

        // Run the scenario; a Mutex demo is joined, so this answers once it has ended
        scenario(session)

        // Wait for any pending events
        kotlinx.coroutines.delay(100)

        val eventCount = session.store.all().size

        logger.info("✅ Sync scenario '$scenarioName' completed | Events: $eventCount")

        respond(
            HttpStatusCode.OK,
            SyncScenarioResponse(
                success = true,
                sessionId = session.sessionId,
                scenario = scenarioName,
                message = "Scenario completed successfully",
                eventCount = eventCount,
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("❌ Sync scenario '$scenarioName' failed", e)
        respond(
            HttpStatusCode.InternalServerError,
            SyncScenarioResponse(
                success = false,
                sessionId = "",
                scenario = scenarioName,
                message = "Scenario failed: ${e.message}",
            ),
        )
    }
}

/**
 * Run a sync scenario INTO the session named by `?sessionId` (creating one if
 * absent), via the same tenant-scoped [getOrCreateSession] the patterns/flow/basic
 * scenario-runner routes use. This is the session-aware counterpart to
 * [runSyncScenario] (which always creates a fresh session) and backs the
 * `POST /api/scenarios/sync/...` Gallery routes.
 */
private suspend fun ApplicationCall.runSyncScenarioInSession(
    scenarioName: String,
    scenario: suspend (VizSession) -> Unit,
) {
    try {
        val sessionId = request.queryParameters["sessionId"]
        val session = getOrCreateSession(sessionId)
        scenario(session)
        kotlinx.coroutines.delay(100)
        val eventCount = session.store.all().size
        logger.info("✅ Sync scenario '$scenarioName' completed in session ${session.sessionId} | Events: $eventCount")
        respond(
            HttpStatusCode.OK,
            SyncScenarioResponse(
                success = true,
                sessionId = session.sessionId,
                scenario = scenarioName,
                message = "Scenario completed successfully",
                eventCount = eventCount,
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("❌ Sync scenario '$scenarioName' failed", e)
        respond(
            HttpStatusCode.InternalServerError,
            SyncScenarioResponse(
                success = false,
                sessionId = "",
                scenario = scenarioName,
                message = "Scenario failed: ${e.message}",
            ),
        )
    }
}
