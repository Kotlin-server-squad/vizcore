package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.auth.resolveTenant
import com.jh.proj.coroutineviz.scenarios.SyncScenarios
import com.jh.proj.coroutineviz.scenarios.demoScope
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
        call.runSyncScenario("Database Connection Pool") { SyncScenarios.databaseConnectionPool(it.demoScope()) }
    }

    /**
     * API rate limiter
     */
    demoGet("/api/sync/semaphore/rate-limiter") {
        call.runSyncScenario("API Rate Limiter") { SyncScenarios.apiRateLimiter(it.demoScope()) }
    }

    /**
     * Parallel file processor with I/O throttling
     */
    demoGet("/api/sync/semaphore/file-processor") {
        call.runSyncScenario("Parallel File Processor") { SyncScenarios.parallelFileProcessor(it.demoScope()) }
    }

    /**
     * Resource pool with timeout handling
     */
    demoGet("/api/sync/semaphore/resource-timeout") {
        call.runSyncScenario("Resource Pool with Timeout") { SyncScenarios.resourcePoolWithTimeout(it.demoScope()) }
    }

    /**
     * Producer-Consumer with bounded buffer
     */
    demoGet("/api/sync/semaphore/producer-consumer") {
        call.runSyncScenario("Producer-Consumer Buffer") { SyncScenarios.producerConsumerBuffer(it.demoScope()) }
    }

    // ========================================================================
    // COMBINED SCENARIOS
    // ========================================================================

    /**
     * E-commerce order processing (Mutex + Semaphore)
     */
    demoGet("/api/sync/combined/ecommerce") {
        call.runSyncScenario("E-commerce Order Processing") { SyncScenarios.ecommerceOrderProcessing(it.demoScope()) }
    }

    // ========================================================================
    // LIST ALL SCENARIOS
    // ========================================================================

    get("/api/sync/scenarios") {
        call.respond(
            HttpStatusCode.OK,
            listOf(
                ScenarioInfo("mutex/counter", "Thread-Safe Counter", "Mutex", "Using mutex for atomic counter increments"),
                ScenarioInfo("mutex/bank-transfer", "Bank Account Transfer", "Mutex", "Safe money transfer with consistent lock ordering"),
                ScenarioInfo("mutex/cache", "Cache Read-Through", "Mutex", "Thread-safe lazy initialization pattern"),
                ScenarioInfo("mutex/deadlock-demo", "Deadlock Demo", "Mutex", "Intentional deadlock for detection demonstration"),
                ScenarioInfo("semaphore/connection-pool", "Connection Pool", "Semaphore", "Limiting concurrent database connections"),
                ScenarioInfo("semaphore/rate-limiter", "API Rate Limiter", "Semaphore", "Throttling concurrent API calls"),
                ScenarioInfo("semaphore/file-processor", "File Processor", "Semaphore", "I/O-limited parallel file processing"),
                ScenarioInfo("semaphore/resource-timeout", "Resource Timeout", "Semaphore", "Resource acquisition with timeout"),
                ScenarioInfo("semaphore/producer-consumer", "Producer-Consumer", "Semaphore", "Bounded buffer implementation"),
                ScenarioInfo("combined/ecommerce", "E-commerce Orders", "Combined", "Order processing with inventory and payment"),
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
        call.runSyncScenarioInSession("Connection Pool") { SyncScenarios.databaseConnectionPool(it.demoScope()) }
    }
    demoPost("/api/scenarios/sync/semaphore/rate-limiter") {
        call.runSyncScenarioInSession("API Rate Limiter") { SyncScenarios.apiRateLimiter(it.demoScope()) }
    }
    demoPost("/api/scenarios/sync/semaphore/file-processor") {
        call.runSyncScenarioInSession("File Processor") { SyncScenarios.parallelFileProcessor(it.demoScope()) }
    }
    demoPost("/api/scenarios/sync/semaphore/resource-timeout") {
        call.runSyncScenarioInSession("Resource Timeout") { SyncScenarios.resourcePoolWithTimeout(it.demoScope()) }
    }
    demoPost("/api/scenarios/sync/semaphore/producer-consumer") {
        call.runSyncScenarioInSession("Producer-Consumer") { SyncScenarios.producerConsumerBuffer(it.demoScope()) }
    }
    demoPost("/api/scenarios/sync/combined/ecommerce") {
        call.runSyncScenarioInSession("E-commerce Order Processing") { SyncScenarios.ecommerceOrderProcessing(it.demoScope()) }
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
