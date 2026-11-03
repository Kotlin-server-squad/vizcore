package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.scenarios.DemoRuns
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.AttributeKey
import kotlinx.coroutines.withContext

/** At most this many demos run at once when `scenarios.maxConcurrent` is not configured. */
const val DEFAULT_MAX_CONCURRENT_DEMOS = 3

/** Seconds a refused client is asked to wait; most demos end within a few seconds. */
private const val DEMO_BUSY_RETRY_AFTER_SECONDS = 5

internal val DemoRunsKey = AttributeKey<DemoRuns>("DemoRuns")

/** Reads `scenarios.maxConcurrent` (env `MAX_CONCURRENT_DEMOS`); call before configureRouting. */
fun Application.configureDemoRuns() {
    val maxConcurrent =
        environment.config
            .propertyOrNull("scenarios.maxConcurrent")
            ?.getString()
            ?.toIntOrNull() ?: DEFAULT_MAX_CONCURRENT_DEMOS
    val runs = DemoRuns(maxConcurrent)
    attributes.put(DemoRunsKey, runs)
    monitor.subscribe(ApplicationStopped) { runs.close() }
}

/** A POST endpoint that runs a demo; it counts against MAX_CONCURRENT_DEMOS while the demo runs. */
fun Route.demoPost(
    path: String,
    body: suspend RoutingContext.() -> Unit,
): Route = post(path) { runDemo(body) }

/** A GET endpoint that runs a demo; it counts against MAX_CONCURRENT_DEMOS while the demo runs. */
fun Route.demoGet(
    path: String,
    body: suspend RoutingContext.() -> Unit,
): Route = get(path) { runDemo(body) }

private suspend fun RoutingContext.runDemo(body: suspend RoutingContext.() -> Unit) {
    // Routes registered without the module (some tests) run demos without a limit.
    val runs = call.application.attributes.computeIfAbsent(DemoRunsKey) { DemoRuns(maxConcurrent = 0) }
    val run = runs.tryStart()
    if (run == null) {
        // 503, not 429: the server as a whole is busy, this client is not over its own limit,
        // and the SPA would hold back all of its writes after a 429.
        call.response.header(HttpHeaders.RetryAfter, DEMO_BUSY_RETRY_AFTER_SECONDS)
        call.respond(
            HttpStatusCode.ServiceUnavailable,
            ErrorResponse(error = "Server busy: too many demos running, try again in a few seconds"),
        )
        return
    }
    try {
        withContext(run) { body() }
    } finally {
        run.handlerEnded()
    }
}
