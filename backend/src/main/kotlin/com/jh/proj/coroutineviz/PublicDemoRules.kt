package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.routes.SessionDemos
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.decodeURLPart

/** What a refused visitor is told to do instead; every refusal ends with it. */
private const val RUN_YOUR_OWN = "To do this, run vizcore on your own machine."

internal fun publicDemoRefusal(what: String) = "This is a public demo: $what. $RUN_YOUR_OWN"

private val SESSION_CREATE_SEGMENTS = listOf("api", "sessions")
private val SCENARIO_PREFIX = listOf("api", "scenarios")
private val VALIDATE_PREFIX = listOf("api", "validate", "session")
private val LOOK_EXPERIMENT_WRITES =
    setOf(
        listOf("api", "experiments", "looks", "events"),
        listOf("api", "experiments", "looks", "votes"),
    )

/** What a visitor's request is, as far as public demo mode cares. */
internal sealed interface VisitorRequest {
    /** Goes ahead as it is. */
    data object Allowed : VisitorRequest

    /** Opens a session for a built-in demo. */
    data object NewDemoSession : VisitorRequest

    /** Runs the built-in demo [demoId], into the session named [sessionId] when there is one. */
    data class DemoRun(
        val demoId: String,
        val sessionId: String?,
    ) : VisitorRequest

    /** Refused with 403 and this [message]. */
    data class Refused(
        val message: String,
    ) : VisitorRequest
}

/**
 * What public demo mode makes of a request with [method], [path] and [query] from a caller who is
 * not an admin.
 *
 * Reads (GET, HEAD, OPTIONS) all go ahead here: the few a visitor may not make are refused where
 * their routes are registered ([adminOnlyInPublicDemo]), which cannot be sidestepped by spelling a
 * path differently. Every other request is refused unless it is one of the few named below, so a
 * write route added later is closed to visitors until someone opens it here.
 */
internal fun classifyVisitorRequest(
    method: HttpMethod,
    path: String,
    query: Parameters,
): VisitorRequest =
    when (method) {
        HttpMethod.Get, HttpMethod.Head, HttpMethod.Options -> VisitorRequest.Allowed
        HttpMethod.Post -> classifyVisitorPost(path, query)
        else -> VisitorRequest.Refused(publicDemoRefusal("visitors cannot delete, reset or change sessions"))
    }

private fun classifyVisitorPost(
    path: String,
    query: Parameters,
): VisitorRequest {
    // Compared segment by segment, decoded, as the router matches them: a path spelled another way
    // can only fail to match an allowed route, never slip past a refused one. One that cannot be
    // decoded matches nothing.
    val segments =
        runCatching { path.split('/').filter { it.isNotEmpty() }.map { it.decodeURLPart() } }.getOrDefault(emptyList())
    return when {
        segments == SESSION_CREATE_SEGMENTS -> classifySessionCreate(query)
        // The built-in demos by name, each bounded by MAX_CONCURRENT_DEMOS and MAX_DEMO_RUN_SECONDS.
        // "custom" is not one of them, and neither is a demo route nobody put in the catalog.
        segments.startsWith(SCENARIO_PREFIX) && segments.size > SCENARIO_PREFIX.size -> {
            val demoId = segments.drop(SCENARIO_PREFIX.size).joinToString("/")
            if (SessionDemos.isKnown(demoId)) {
                VisitorRequest.DemoRun(demoId, query["sessionId"]?.takeIf { it.isNotEmpty() })
            } else {
                VisitorRequest.Refused(publicDemoRefusal("visitors can run the built-in demos, not their own scenarios"))
            }
        }
        // Checks: reads the session's events and keeps one small result per session.
        segments.startsWith(VALIDATE_PREFIX) && segments.size == VALIDATE_PREFIX.size + 1 -> VisitorRequest.Allowed
        // The look experiment's anonymous events and votes; nothing is taken while it is off.
        segments in LOOK_EXPERIMENT_WRITES -> VisitorRequest.Allowed
        else -> VisitorRequest.Refused(publicDemoRefusal("visitors can watch and run the built-in demos only"))
    }
}

private fun List<String>.startsWith(prefix: List<String>): Boolean = size >= prefix.size && subList(0, prefix.size) == prefix

/** A visitor's new session must be for a built-in demo, and not one the connect wizard waits on. */
private fun classifySessionCreate(query: Parameters): VisitorRequest {
    val demoId = query["demoId"]?.takeIf { it.isNotEmpty() }
    return when {
        !query["correlation"].isNullOrBlank() -> VisitorRequest.Refused(publicDemoRefusal("visitors cannot connect an app"))
        demoId == null ->
            VisitorRequest.Refused(publicDemoRefusal("visitors can start a session for a built-in demo only, not for an app"))
        // An unknown demo is left to the route, which answers 400 "Unknown demo" and creates nothing.
        !SessionDemos.isKnown(demoId) -> VisitorRequest.Allowed
        else -> VisitorRequest.NewDemoSession
    }
}
