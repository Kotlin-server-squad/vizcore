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
            Demo(
                "nested",
                "Nested: Product Page",
                "A product page loads the product, then three sections at once, each with requests of its own. " +
                    "The page's code ends after 2.5 s, but it completes only with the last request below it: " +
                    "a parent waits for its children (structured concurrency).",
                "~16 seconds",
            ),
            Demo(
                "parallel",
                "Parallel: Dashboard",
                "Five dashboard widgets load at the same time and finish one by one; the dashboard renders once " +
                    "the slowest is in. 31 s of requests take 14 s.",
                "~14 seconds",
            ),
            Demo(
                "cancellation",
                "Cancellation: User Closes the Screen",
                "The profile screen loads the avatar, the friends and the activity feed. The user closes it after " +
                    "6.5 s, which cancels the screen and every request still running under it; settings sync, " +
                    "outside the screen, carries on.",
                "~12 seconds",
            ),
            Demo(
                "deep-nesting",
                "Deep Nesting: Search Indexer",
                "A search indexer walks /home/ana/projects/vizcore/backend/src, one folder deeper at a time, and " +
                    "completes the folders from the deepest up as each adds its subfolder's results " +
                    "(configurable depth).",
                "~16 seconds",
                endpoint = "/api/scenarios/deep-nesting?depth=5",
            ),
            Demo(
                "mixed",
                "Sequential and Parallel: Checkout",
                "Checkout validates the cart; then reserves the stock, charges the card and calculates shipping " +
                    "at once; then creates the order and sends the receipt, one after the other.",
                "~14 seconds",
            ),
            Demo(
                "exception",
                "Exception: One Backend Call Fails",
                "The account page loads five things at once. 12 s in, the loyalty service answers HTTP 500: the " +
                    "failure cancels the requests still loading and fails the page, while what already arrived " +
                    "stays done.",
                "~12 seconds",
            ),
            Demo(
                "dispatcher",
                "Dispatchers: Photo Thumbnails",
                "Three photos arrive 2 s apart; each is downloaded on Dispatchers.IO, resized on " +
                    "Dispatchers.Default and uploaded on IO again. Feeds the Dispatcher Overview: see dispatcher " +
                    "selection and thread assignment.",
                "~13 seconds",
            ),
        ) +
        group(
            "channels",
            "Channels",
            "channel",
            Demo(
                "channel-rendezvous",
                "Rendezvous: Chef and Waiter",
                "The chef hands each plate to the waiter at the pass, a channel with no room: a plate changes " +
                    "hands only when both are there. The waiter waits for the first plate; after that the chef " +
                    "waits for the waiter with each plate.",
                "~17 seconds",
            ),
            Demo(
                "channel-buffered",
                "Buffered Channel: Print Queue",
                "The office sends 8 jobs to a print queue with 5 slots, faster than the printer prints. The " +
                    "queue fills up, the office waits for a free slot, and the printer drains the queue.",
                "~23 seconds",
            ),
            Demo(
                "channel-fan-out",
                "Fan-Out: Support Desk",
                "12 support tickets go into one queue read by 3 agents. Each ticket goes to exactly one free " +
                    "agent; in the morning rush all three are busy and new tickets wait in the queue.",
                "~13 seconds",
            ),
        ) +
        group(
            "flow",
            "Flow",
            "flow",
            Demo(
                "flow/simple",
                "Simple Flow: Stock Ticker",
                "A stock ticker: a cold flow sends six prices to each screen that collects it; " +
                    "a second screen gets its own run from the first price.",
                "~16 seconds",
            ),
            Demo(
                "flow/operators",
                "Flow Operators: Search as You Type",
                "Search as you type: keystrokes are debounced, short queries filtered out, " +
                    "and the rest mapped to search results, in that order.",
                "~14 seconds",
            ),
            Demo(
                "flow/stateflow",
                "StateFlow: Shopping Cart",
                "A shopping cart: the total always has a value, " +
                    "and a checkout page that opens later gets the latest total at once.",
                "~12 seconds",
            ),
            Demo(
                "flow/sharedflow",
                "SharedFlow: Notifications",
                "Notifications: every open screen gets each one at the same moment; " +
                    "a screen opened later gets only what comes after.",
                "~14 seconds",
            ),
        ) +
        group(
            "patterns",
            "Patterns",
            "pattern",
            Demo(
                "patterns/retry",
                "Retry with Backoff: Weather Forecast",
                "The weather server is overloaded and turns down the first three tries. The app retries with " +
                    "exponential backoff, waiting 0.75 s, 1.5 s and 3 s, and the fourth try gets the forecast.",
                "~12 seconds",
            ),
            Demo(
                "patterns/producer-consumer",
                "Producer-Consumer: Coffee Bar",
                "The cashier puts orders on a rail with room for 3 and the barista makes them, each at their own " +
                    "pace. In the morning rush the rail fills and the cashier waits; in the quiet spell the barista " +
                    "waits for orders.",
                "~18 seconds",
            ),
            Demo(
                "patterns/fan-out-fan-in",
                "Fan-Out / Fan-In: Holiday Album",
                "9 photos go to 3 resizers, each photo to exactly one (fan-out). The album collects the finished " +
                    "photos through one channel in the order they are done, not the order they were picked (fan-in).",
                "~12 seconds",
            ),
            Demo(
                "patterns/supervisor",
                "Supervisor: Nightly Backup",
                "Photos, contacts and messages back up side by side under supervisorScope. The message backup " +
                    "fails, and photos and contacts keep going: one child's failure does not cancel its siblings.",
                "~14 seconds",
            ),
            Demo(
                "patterns/circuit-breaker",
                "Circuit Breaker: Payment Provider",
                "The payment provider is down. After 3 failed charges the breaker opens and rejects orders at " +
                    "once for 5.5 s, then lets one trial order through; the provider is back, so the breaker closes.",
                "~22 seconds",
            ),
        ) +
        group(
            "sync-mutex",
            "Sync: Mutex",
            "sync",
            Demo(
                "sync/mutex/counter",
                "Thread-Safe Counter: Festival Gates",
                "Three gates count festival visitors into one shared count. Each update holds a lock while it " +
                    "reads, shows and writes the count, so the other gates wait for it, suspended, and no visitor is " +
                    "lost.",
                "~14 seconds",
            ),
            Demo(
                "sync/mutex/bank-transfer",
                "Bank Account Transfer: Household Bills",
                "Anna and Ben send each other money. Each transfer locks both accounts, always Anna's first, so " +
                    "the transfers queue instead of deadlocking; the one Ben cannot afford is declined.",
                "~12 seconds",
            ),
            Demo(
                "sync/mutex/cache",
                "Cache Read-Through: Product Prices",
                "Eight requests for two prices meet an empty cache. The first miss loads a price under the lock; " +
                    "the others wait, check again and find it cached; later requests read the cache without the " +
                    "lock. Two loads in all.",
                "~12 seconds",
            ),
            Demo(
                "sync/mutex/deadlock-demo",
                "Deadlock Demo: Two Transfers at Month End",
                "Anna pays Ben and Ben pays Anna, each locking the payer's account first. Each holds one account " +
                    "and waits, suspended, for the other's. After 10 s a watchdog cancels both.",
                "~11 seconds",
            ),
        ) +
        group(
            "sync-semaphore",
            "Sync: Semaphore",
            "sync",
            Demo(
                "sync/semaphore/connection-pool",
                "Database Connection Pool: Online Shop at Lunchtime",
                "Nine lunchtime requests share an online shop's 3 database connections. The first three find " +
                    "one free; the other six wait for one, suspended, and get it in the order they asked.",
                "~11 seconds",
            ),
            Demo(
                "sync/semaphore/rate-limiter",
                "API Rate Limiter: Weather for a Trip",
                "A trip planner asks for the weather in eight cities at once, but the weather service takes " +
                    "two calls at a time. Six calls wait for a permit, suspended, and the trip page waits for " +
                    "every forecast.",
                "~12 seconds",
            ),
            Demo(
                "sync/semaphore/file-processor",
                "Parallel File Processor: The Tax Folder",
                "Nine files of a tax folder are compressed two at a time. While the big bank statements hold " +
                    "one permit, the other passes from file to file the moment one is done.",
                "~13 seconds",
            ),
            Demo(
                "sync/semaphore/resource-timeout",
                "Resource Pool with Timeout: Fitting Rooms",
                "Seven shoppers share two fitting rooms and wait at most 3 s for one. Carla, Dan and Gina get " +
                    "a room in time; Eva and Finn give up and leave.",
                "~12 seconds",
            ),
            Demo(
                "sync/semaphore/producer-consumer",
                "Producer-Consumer Buffer: The Bakery Rack",
                "A baker fills a cooling rack with room for 3 loaves faster than the shop assistant sells " +
                    "them. Two semaphores count the free places and the ready loaves: the baker waits when the " +
                    "rack is full, the assistant when it is empty.",
                "~21 seconds",
            ),
        ) +
        group(
            "combined",
            "Combined",
            "sync",
            Demo(
                "sync/combined/ecommerce",
                "E-commerce Orders: A Limited Sneaker Drop",
                "Six orders for the last 3 pairs. Each reserves a pair under a lock, then pays through a " +
                    "provider that takes two payments at a time. A declined card puts its pair back for a later " +
                    "order; the others find it sold out.",
                "~11 seconds",
            ),
        )
