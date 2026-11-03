package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.scenarios.DemoRuns
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.log
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** At most this many demos run at once when `scenarios.maxConcurrent` is not configured. */
const val DEFAULT_MAX_CONCURRENT_DEMOS = 3

/** A demo still running after this many seconds is stopped, when `scenarios.maxRunSeconds` is not configured. */
const val DEFAULT_MAX_DEMO_RUN_SECONDS = 60

/** Seconds a refused client is asked to wait; most demos end within a few seconds. */
private const val DEMO_BUSY_RETRY_AFTER_SECONDS = 5

internal val DemoRunsKey = AttributeKey<DemoRuns>("DemoRuns")

/**
 * How fast demos run, from `scenarios.timeScale`: 1 = as scripted. application.yaml maps it to env
 * `DEMO_TIME_SCALE`; a configuration that does not name it (the tests' own, which the build runs
 * with a small `DEMO_TIME_SCALE`) reads that variable directly. A value that is not a number of
 * zero or more runs them as scripted.
 */
internal fun Application.demoTimeScale(): Double {
    val configured =
        environment.config.propertyOrNull("scenarios.timeScale")?.getString()
            ?: System.getenv("DEMO_TIME_SCALE")
            ?: return 1.0
    val scale = configured.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    if (scale == null) log.warn("scenarios.timeScale (DEMO_TIME_SCALE) must be a number of zero or more, got '{}'; using 1", configured)
    return scale ?: 1.0
}

/**
 * Reads `scenarios.maxConcurrent` (env `MAX_CONCURRENT_DEMOS`), `scenarios.maxRunSeconds`
 * (env `MAX_DEMO_RUN_SECONDS`) and `scenarios.timeScale` (env `DEMO_TIME_SCALE`); call before
 * configureRouting.
 */
fun Application.configureDemoRuns() {
    fun setting(
        key: String,
        default: Int,
    ) = environment.config
        .propertyOrNull(key)
        ?.getString()
        ?.toIntOrNull() ?: default
    val runs =
        DemoRuns(
            maxConcurrent = setting("scenarios.maxConcurrent", DEFAULT_MAX_CONCURRENT_DEMOS),
            maxRunMillis = setting("scenarios.maxRunSeconds", DEFAULT_MAX_DEMO_RUN_SECONDS) * 1_000L,
            awaitSessionRemoved = ::awaitSessionRemoved,
            timeScale = demoTimeScale(),
        )
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
    val runs =
        call.application.attributes.computeIfAbsent(DemoRunsKey) {
            DemoRuns(maxConcurrent = 0, timeScale = call.application.demoTimeScale())
        }
    val run = runs.tryStart()
    if (run == null) {
        // 503, not 429: the server as a whole is busy, this client is not over its own limit,
        // and the SPA would hold back all of its writes after a 429.
        call.response.header(HttpHeaders.RetryAfter, DEMO_BUSY_RETRY_AFTER_SECONDS)
        call.respond(
            HttpStatusCode.ServiceUnavailable,
            ErrorResponse(error = "Server busy: too many demos running, try again in a few seconds", code = "demo-limit"),
        )
        return
    }
    try {
        run.handle { body() }
    } catch (e: CancellationException) {
        // Only the time limit is answered here; a client that went away needs no answer.
        if (!run.stoppedByTimeLimit || !currentCoroutineContext().isActive) throw e
        if (!call.response.isCommitted) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(error = "The demo ran past its time limit and was stopped", code = "demo-time-limit"),
            )
        }
    }
}
