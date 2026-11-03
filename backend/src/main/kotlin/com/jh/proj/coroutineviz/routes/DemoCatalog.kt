package com.jh.proj.coroutineviz.routes

import kotlinx.serialization.Serializable

/**
 * One demo the "New demo session" dialog can start.
 *
 * [id] is the demo's path under `/api/scenarios/`: the SPA starts it with
 * `POST /api/scenarios/{id}?sessionId=...`, so every entry runs into the session the SPA opened.
 * [group] is a stable key and [groupLabel] its heading; entries are listed group by group, in the
 * order the dialog shows them.
 */
@Serializable
data class DemoCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val group: String,
    val groupLabel: String,
    val duration: String,
    val method: String = "POST",
    val endpoint: String = "/api/scenarios/$id",
)

@Serializable
data class DemoCatalogResponse(
    val scenarios: List<DemoCatalogEntry>,
)

private fun group(
    key: String,
    label: String,
    category: String,
    vararg demos: Demo,
) = demos.map { demo ->
    DemoCatalogEntry(
        id = demo.id,
        name = demo.name,
        description = demo.description,
        category = category,
        group = key,
        groupLabel = label,
        duration = demo.duration,
        endpoint = demo.endpoint ?: "/api/scenarios/${demo.id}",
    )
}

private class Demo(
    val id: String,
    val name: String,
    val description: String,
    val duration: String,
    val endpoint: String? = null,
)

/**
 * Every demo the server can run into an existing session, served by `GET /api/scenarios`. A test
 * walks the registered routes and requires each demo route to appear here, so a new demo cannot
 * be left out of the dialog.
 */
val demoCatalog: List<DemoCatalogEntry> =
    group(
        "real-life",
        "Real life",
        "realistic",
        Demo(
            "order-processing",
            "🛒 Order Processing",
            "E-commerce flow: validation, inventory check, payment, database, notifications (parallel). " +
                "Use ?fail=true to simulate payment failure.",
            "~15-20 seconds",
        ),
        Demo(
            "user-registration",
            "👤 User Registration",
            "Complete registration: validation, DB check, create user, parallel setup (profile, settings, avatar), " +
                "notifications. Use ?failEmail=true to simulate email retry.",
            "~18-25 seconds",
        ),
        Demo(
            "report-generation",
            "📊 Report Generation",
            "Data pipeline: parallel API fetches, data aggregation, PDF generation, parallel delivery " +
                "(S3, email, Slack). Use ?timeout=true to simulate API timeout.",
            "~25-35 seconds",
        ),
    ) +
        group(
            "basics",
            "Basics",
            "basic",
            Demo("nested", "Nested Coroutines", "Demonstrates parent-child relationships and structured concurrency", "~5 seconds"),
            Demo("parallel", "Parallel Execution", "Multiple coroutines running in parallel", "~3 seconds"),
            Demo("cancellation", "Cancellation", "Demonstrates coroutine cancellation and cleanup", "~3 seconds"),
            Demo(
                "deep-nesting",
                "Deep Nesting",
                "Deep hierarchy of nested coroutines (configurable depth)",
                "~5 seconds",
                endpoint = "/api/scenarios/deep-nesting?depth=5",
            ),
            Demo("mixed", "Mixed Sequential/Parallel", "Combination of sequential and parallel execution", "~1 second"),
            Demo("exception", "Exception Handling", "Demonstrates exception tracking and failure states", "~1 second"),
            Demo(
                "dispatcher",
                "Dispatcher Switching",
                "Runs work across Dispatchers.Default and Dispatchers.IO via VizDispatchers. " +
                    "Feeds the Dispatcher Overview: see dispatcher selection and thread assignment.",
                "~1 second",
            ),
        ) +
        group(
            "channels",
            "Channels",
            "channel",
            Demo(
                "channel-rendezvous",
                "Channel Rendezvous",
                "Zero-buffer channel where sender and receiver must meet for each transfer. " +
                    "Demonstrates suspension on both sides.",
                "~2-3 seconds",
            ),
            Demo(
                "channel-buffered",
                "Channel Buffered",
                "Buffered channel (capacity 3) showing buffer fill/drain. Producer sends 5 items, consumer starts after delay.",
                "~2-3 seconds",
            ),
            Demo(
                "channel-fan-out",
                "Channel Fan-Out",
                "Fan-out pattern: one producer dispatches 10 tasks, 3 workers compete to receive and process them.",
                "~3-5 seconds",
            ),
        ) +
        group(
            "flow",
            "Flow",
            "flow",
            Demo("flow/simple", "Simple Flow", "Emit and collect 5 values from a cold Flow", "~1 second"),
            Demo("flow/operators", "Flow Operators", "Chain map and filter operators on a flow", "~1 second"),
            Demo("flow/stateflow", "StateFlow", "Mutable state with multiple observers", "~1 second"),
            Demo("flow/sharedflow", "SharedFlow", "Broadcast events to multiple subscribers", "~1 second"),
        ) +
        group(
            "patterns",
            "Patterns",
            "pattern",
            Demo(
                "patterns/retry",
                "Retry with Exponential Backoff",
                "Retry a failing operation 3 times with exponential backoff (100ms, 200ms, 400ms). " +
                    "Shows structured retry pattern for transient failures.",
                "~1 second",
            ),
            Demo(
                "patterns/producer-consumer",
                "Producer-Consumer",
                "Producer sends items to a buffered channel (capacity 5), consumer processes them with delay. " +
                    "Shows channel-based communication.",
                "~1-2 seconds",
            ),
            Demo(
                "patterns/fan-out-fan-in",
                "Fan-Out / Fan-In",
                "Distribute work to multiple workers in parallel, results collected via channel. " +
                    "Shows work distribution pattern.",
                "~1 second",
            ),
            Demo(
                "patterns/supervisor",
                "Supervisor Pattern",
                "Launch children where one fails but siblings continue. Shows error isolation with structured concurrency.",
                "~1 second",
            ),
            Demo(
                "patterns/circuit-breaker",
                "Circuit Breaker",
                "After N consecutive failures, stop trying for a cooldown period. " +
                    "Shows resilience pattern for protecting downstream services.",
                "~1 second",
            ),
        ) +
        group(
            "sync-mutex",
            "Sync: Mutex",
            "sync",
            Demo("sync/mutex/counter", "Thread-Safe Counter", "Using mutex for atomic counter increments", "~3-4 seconds"),
            Demo("sync/mutex/bank-transfer", "Bank Account Transfer", "Safe money transfer with consistent lock ordering", "~1 second"),
            Demo("sync/mutex/cache", "Cache Read-Through", "Thread-safe lazy initialization pattern", "~1 second"),
            Demo("sync/mutex/deadlock-demo", "Deadlock Demo", "Intentional deadlock for detection demonstration", "~2 seconds"),
        ) +
        group(
            "sync-semaphore",
            "Sync: Semaphore",
            "sync",
            Demo("sync/semaphore/connection-pool", "Connection Pool", "Limiting concurrent database connections", "~1 second"),
            Demo("sync/semaphore/rate-limiter", "API Rate Limiter", "Throttling concurrent API calls", "~1 second"),
            Demo("sync/semaphore/file-processor", "File Processor", "I/O-limited parallel file processing", "~1 second"),
            Demo("sync/semaphore/resource-timeout", "Resource Timeout", "Resource acquisition with timeout", "~1 second"),
            Demo("sync/semaphore/producer-consumer", "Producer-Consumer", "Bounded buffer implementation", "~1 second"),
        ) +
        group(
            "combined",
            "Combined",
            "sync",
            Demo("sync/combined/ecommerce", "E-commerce Orders", "Order processing with inventory and payment", "~1 second"),
        )
