@file:Suppress("DEPRECATION")

package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.auth.ApiKeyPrincipal
import com.jh.proj.coroutineviz.auth.Role
import com.jh.proj.coroutineviz.auth.UserPrincipal
import com.jh.proj.coroutineviz.routes.ErrorResponse
import com.jh.proj.coroutineviz.routes.SessionDemos
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.isHandled
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.application
import io.ktor.util.AttributeKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("PublicDemo")

/** The `code` of every refusal public demo mode makes, so a client can tell it from any other 403. */
const val PUBLIC_DEMO_ERROR_CODE = "public-demo"

/** What public demo mode needs at request time; present only while the mode is on. */
internal data class PublicDemoSettings(
    /** `session.maxCount` (env `SESSION_MAX_COUNT`): a visitor's new session removes the oldest beyond it. */
    val maxSessions: Int,
)

/** Set by [configurePublicDemo] when `publicDemo.enabled` (env `PUBLIC_DEMO_MODE`) is on. */
private val PublicDemoKey = AttributeKey<PublicDemoSettings>("PublicDemo")

/**
 * Whether this server runs in public demo mode: anyone may watch and run the built-in demos, and
 * only an admin may connect an app, post a custom scenario, delete a session or share one. Off
 * unless `publicDemo.enabled` (env `PUBLIC_DEMO_MODE`) is `true`, and off for an application
 * that never called [configurePublicDemo].
 */
val Application.publicDemoMode: Boolean
    get() = attributes.contains(PublicDemoKey)

/**
 * Reads `publicDemo.enabled` (env `PUBLIC_DEMO_MODE`); call before [configureAuth] and
 * [configureRouting], which both read the result. Read once at startup; restart to change.
 *
 * Public demo mode keeps its sessions in memory: there SESSION_MAX_AGE_MS and SESSION_MAX_COUNT
 * bound what visitors' demo runs leave behind. The database keeps sessions for 30 days with no
 * limit on their number, so the two together are refused at startup instead of filling a disk.
 */
fun Application.configurePublicDemo() {
    val config = environment.config
    val enabled =
        config
            .propertyOrNull("publicDemo.enabled")
            ?.getString()
            ?.trim()
            .toBoolean()
    if (!enabled) return
    val storageType = config.propertyOrNull("storage.type")?.getString() ?: "memory"
    require(!storageType.equals("database", ignoreCase = true)) {
        "PUBLIC_DEMO_MODE=true needs STORAGE_TYPE=memory: the database keeps every visitor's demo session for " +
            "RETENTION_MAX_AGE_DAYS with no limit on their number. Unset one of the two."
    }
    val maxSessions =
        config.propertyOrNull("session.maxCount")?.getString()?.toIntOrNull() ?: DEFAULT_SESSION_MAX_COUNT
    attributes.put(PublicDemoKey, PublicDemoSettings(maxSessions = maxSessions))
    logger.info(
        "Public demo mode is ON: visitors may watch and run the built-in demos; connecting an app, custom " +
            "scenarios, deleting and sharing sessions need an admin credential (at most {} sessions are kept)",
        maxSessions,
    )
}

/** Whether the request carries an admin credential: an API key or a user with [Role.ADMIN]. */
private fun ApplicationCall.isAdmin(): Boolean =
    when (val principal = currentPrincipal()) {
        is ApiKeyPrincipal -> principal.role == Role.ADMIN
        is UserPrincipal -> principal.role == Role.ADMIN
        else -> false
    }

private suspend fun ApplicationCall.refuseInPublicDemo(message: String) {
    respond(HttpStatusCode.Forbidden, ErrorResponse(error = message, code = PUBLIC_DEMO_ERROR_CODE))
}

private val NOT_THIS_DEMOS_SESSION =
    publicDemoRefusal("visitors can run a demo only in a session that was started for that demo")

private const val ONE_RUN_PER_SESSION =
    "This is a public demo: a demo runs once, in a session of its own. Use Run again to start a new session."

/** The session a visitor's request claimed for its demo run ([VisitorSessions.claimForRun]). */
private val ClaimedSessionKey = AttributeKey<VizSession>("PublicDemoClaimedSession")

/** Marks a visitor's request that may add a session, so the limit is applied before its answer. */
private val MayAddSessionKey = AttributeKey<Unit>("PublicDemoMayAddSession")

/**
 * Applies the visitors' rules ([classifyVisitorRequest]) to everyone but an admin: a request that
 * is not allowed is refused with 403. Runs after authentication and before the route's handler, so
 * nothing is read, created or upgraded first.
 */
private val PublicDemoWriteGuard =
    createRouteScopedPlugin("PublicDemoWriteGuard") {
        val sessions = VisitorSessions(application.attributes[PublicDemoKey].maxSessions)
        on(AuthenticationChecked) { call ->
            if (call.isHandled || call.isAdmin()) return@on
            val request = call.request
            when (val kind = classifyVisitorRequest(request.httpMethod, request.path(), request.queryParameters)) {
                VisitorRequest.Allowed -> Unit
                VisitorRequest.NewDemoSession -> call.attributes.put(MayAddSessionKey, Unit)
                is VisitorRequest.DemoRun -> {
                    val session = kind.sessionId?.let { SessionManager.getSession(it) }
                    when {
                        // A run with no session, or with one that is gone, opens a session of its own.
                        session == null -> call.attributes.put(MayAddSessionKey, Unit)
                        // A session with no demo is an app's, and one with another demo is someone
                        // else's run: the list of sessions is public, so its id is no secret.
                        SessionDemos.of(session.sessionId) != kind.demoId -> call.refuseInPublicDemo(NOT_THIS_DEMOS_SESSION)
                        sessions.claimForRun(session) -> call.attributes.put(ClaimedSessionKey, session)
                        else -> call.refuseInPublicDemo(ONE_RUN_PER_SESSION)
                    }
                }
                is VisitorRequest.Refused -> call.refuseInPublicDemo(kind.message)
            }
            // A request the client gave up on is never answered: its claim and its session are
            // settled when it ends, however it ends.
            if (call.attributes.contains(ClaimedSessionKey) || call.attributes.contains(MayAddSessionKey)) {
                currentCoroutineContext().job.invokeOnCompletion { sessions.settle(call) }
            }
        }
        onCallRespond { call, _ -> sessions.settle(call) }
    }

/**
 * Gives up the claim [call] holds, once, and applies the limit when it may have added a session.
 * Called before the answer, so the claim is free and the limit holds by the time the client reads
 * it, and again when the request ends.
 */
private fun VisitorSessions.settle(call: ApplicationCall) {
    // Taken, not read: a later call must not give up a claim another request holds by then.
    call.attributes.takeOrNull(ClaimedSessionKey)?.let(::runAnswered)
    if (call.attributes.contains(MayAddSessionKey)) trimToLimit()
}

private class AdminOnlyConfig {
    var message: String = publicDemoRefusal("visitors can watch and run the built-in demos only")
}

/** Refuses, with 403, every request to the routes it is installed on unless an admin makes it. */
private val PublicDemoAdminOnly =
    createRouteScopedPlugin("PublicDemoAdminOnly", ::AdminOnlyConfig) {
        val message = pluginConfig.message
        on(AuthenticationChecked) { call ->
            if (call.isHandled || call.isAdmin()) return@on
            call.refuseInPublicDemo(message)
        }
    }

/** A route level that matches everything and only carries a plugin, like `authenticate { }`. */
private class PublicDemoRouteSelector(
    private val name: String,
) : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(publicDemo $name)"
}

/**
 * The routes in [build], with the visitors' rules of public demo mode in front of them
 * ([classifyVisitorRequest]). With the mode off the routes are registered as they are, with nothing
 * added to their path.
 */
fun Route.publicDemoGuarded(build: Route.() -> Unit) {
    if (!application.publicDemoMode) {
        build()
        return
    }
    val guarded = createChild(PublicDemoRouteSelector("writes"))
    guarded.install(PublicDemoWriteGuard)
    guarded.build()
}

/**
 * The routes in [build], which in public demo mode only an admin may call: a visitor is refused
 * with 403 and a sentence saying that [what] is not for visitors. With the mode off the routes are
 * registered as they are.
 */
fun Route.adminOnlyInPublicDemo(
    what: String,
    build: Route.() -> Unit,
) {
    if (!application.publicDemoMode) {
        build()
        return
    }
    val restricted = createChild(PublicDemoRouteSelector("admin only"))
    restricted.install(PublicDemoAdminOnly) { message = publicDemoRefusal(what) }
    restricted.build()
}
