@file:Suppress("DEPRECATION")

package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.auth.ApiKeyPrincipal
import com.jh.proj.coroutineviz.auth.ApiKeyStore
import com.jh.proj.coroutineviz.auth.JwtConfig
import com.jh.proj.coroutineviz.auth.Role
import com.jh.proj.coroutineviz.auth.UserPrincipal
import com.jh.proj.coroutineviz.auth.UserStore
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.Principal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTCredential
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Auth")

/**
 * Param name the jwt provider reads the SSE token from (Pitfall 2: browser EventSource
 * cannot set an Authorization header, so the JWT travels as `?token=<jwt>`).
 *
 * THIS EXACT NAME (`token`) IS A LOCKED CROSS-PLAN CONTRACT — Plan 05's frontend binds
 * the EventSource URL to it. Do not rename without updating the frontend stream code.
 */
const val SSE_TOKEN_QUERY_PARAM = "token"

/** Set true at startup when NEITHER api-key NOR user/jwt auth is configured (D-04a fail-open). */
private val AuthDisabledKey = AttributeKey<Boolean>("AuthDisabled")

/**
 * Install the dual-provider authentication layer (AUTH-01/02/03, D-04/D-08).
 *
 * - Always `install(Authentication)` with an `api-key` provider (X-API-Key → ApiKeyPrincipal)
 *   and a `jwt` provider (Bearer OR `?token=` query param → UserPrincipal).
 * - `authDisabled` is computed ONCE: true when there are no keys AND no users/jwt configured.
 *   When disabled, [authenticatedApi] is a pure pass-through (no challenge, no 401) — preserving
 *   the `git clone → run` default-public invariant (D-04a, Pitfall 1).
 * - Each provider challenges 401 with a generic JSON error (no scheme leak).
 */
fun Application.configureAuth() {
    val apiKeyStore = ApiKeyStore.fromConfig(environment.config)
    val userStore = UserStore.fromConfig(environment.config)
    val jwtConfig = JwtConfig.fromConfig(environment.config)

    val jwtUsable = !userStore.isEmpty && jwtConfig.isConfigured
    val authDisabled = apiKeyStore.isEmpty && !jwtUsable
    attributes.put(AuthDisabledKey, authDisabled)

    // Expose the configured stores so route registration (token endpoint) can reuse them.
    attributes.put(ApiKeyStoreKey, apiKeyStore)
    attributes.put(UserStoreKey, userStore)
    attributes.put(JwtConfigKey, jwtConfig)

    if (authDisabled) {
        logger.info("Authentication disabled (no API keys and no users/JWT configured) — all routes public (D-04a)")
    } else {
        logger.info(
            "Authentication enabled (apiKeys={}, jwt={}) — non-public routes require X-API-Key OR Bearer/?token=",
            !apiKeyStore.isEmpty,
            jwtUsable,
        )
    }

    install(Authentication) {
        provider("api-key") {
            authenticate { context ->
                val requestKey = context.call.request.headers["X-API-Key"]
                val entry = requestKey?.let { apiKeyStore.validate(it) }
                if (entry != null) {
                    context.principal(ApiKeyPrincipal(entry.name, entry.role))
                } else {
                    context.challengeUnauthorized("ApiKey")
                }
            }
        }

        jwt("jwt") {
            realm = jwtConfig.realm
            // Read the token from the Authorization: Bearer header OR the `?token=` query param.
            // EventSource cannot set headers, so the authenticated SSE path supplies the JWT as a
            // query parameter (Pitfall 2). This is the locked cross-plan SSE contract (Plan 05).
            authHeader { call -> resolveJwtAuthHeader(call) }
            jwtConfig.verifier()?.let { verifier(it) }
            validate { credential -> credential.toUserPrincipalOrNull() }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
            }
        }
    }
}

/** Build an [HttpAuthHeader] from the Bearer header if present, else from the `?token=` query param. */
private fun resolveJwtAuthHeader(call: ApplicationCall): HttpAuthHeader? {
    call.request.headers["Authorization"]?.let { raw ->
        return runCatching { io.ktor.http.auth.parseAuthorizationHeader(raw) }.getOrNull()
    }
    val queryToken = call.request.queryParameters[SSE_TOKEN_QUERY_PARAM]?.takeIf { it.isNotBlank() }
    return queryToken?.let { HttpAuthHeader.Single("Bearer", it) }
}

/** Map a verified JWT to a [UserPrincipal]; null subject rejects the request. */
private fun JWTCredential.toUserPrincipalOrNull(): UserPrincipal? {
    val userId = payload.subject ?: return null
    val role = Role.fromConfig(payload.getClaim("role").asString())
    return UserPrincipal(userId = userId, role = role)
}

private fun io.ktor.server.auth.AuthenticationContext.challengeUnauthorized(scheme: String) {
    error(scheme, AuthenticationFailedCause.InvalidCredentials)
    challenge(scheme, AuthenticationFailedCause.InvalidCredentials) { ch, call ->
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
        ch.complete()
    }
}

/**
 * Wrap non-public routes so EITHER a valid X-API-Key OR a valid JWT satisfies the request (D-08).
 * When auth is disabled it is a pure pass-through — no auth plugin in the path (Pitfall 1, D-04a).
 */
fun Route.authenticatedApi(build: Route.() -> Unit) {
    val authDisabled = application.attributes.getOrNull(AuthDisabledKey) ?: true
    if (authDisabled) {
        build()
        return
    }
    authenticate("api-key", "jwt", optional = false) {
        build()
    }
}

/**
 * Resolve the current request's principal for downstream tenancy (Plan 03 consumes this).
 * Returns an [ApiKeyPrincipal] or [UserPrincipal] when authenticated, else null (auth-off path).
 */
fun ApplicationCall.currentPrincipal(): Principal? =
    authentication.principal<ApiKeyPrincipal>() ?: authentication.principal<UserPrincipal>()

// -- Attribute keys so Routing.kt can read the stores built in configureAuth() ----
val ApiKeyStoreKey = AttributeKey<ApiKeyStore>("ApiKeyStore")
val UserStoreKey = AttributeKey<UserStore>("UserStore")
val JwtConfigKey = AttributeKey<JwtConfig>("JwtConfig")
