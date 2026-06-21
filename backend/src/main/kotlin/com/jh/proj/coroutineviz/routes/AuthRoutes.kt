package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.auth.JwtConfig
import com.jh.proj.coroutineviz.auth.UserStore
import com.password4j.Password
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AuthRoutes")

@Serializable
data class LoginRequest(val username: String = "", val password: String = "")

@Serializable
data class TokenResponse(val token: String, val expiresAt: String)

@Serializable
data class AuthErrorResponse(val error: String)

/**
 * `POST /api/auth/token` — ALWAYS public login endpoint (AUTH-03, ADR-016).
 *
 * Looks up the username in the config-seeded [UserStore] and verifies the submitted
 * password against the stored Argon2id hash with password4j (constant-time, D-02).
 * On success returns `{token, expiresAt}`; on unknown-user OR bad-password returns a
 * UNIFORM 401 `{"error":"Invalid credentials"}` so the endpoint does not leak whether a
 * username exists (T-03-07 user-enumeration mitigation). Credentials/tokens are never logged.
 */
fun Route.registerAuthRoutes(userStore: UserStore, jwtConfig: JwtConfig) {
    post("/api/auth/token") {
        val creds =
            runCatching { call.receive<LoginRequest>() }.getOrNull()
        if (creds == null || creds.username.isBlank() || creds.password.isBlank()) {
            call.respond(HttpStatusCode.Unauthorized, AuthErrorResponse("Invalid credentials"))
            return@post
        }

        val user = userStore.find(creds.username)
        // Constant-time verify. When the user is unknown we STILL respond uniformly; password4j
        // verify against a real hash is constant-time, and the unknown-user branch short-circuits
        // to the same 401 body (no enumeration, T-03-07).
        val passwordOk =
            user != null &&
                runCatching {
                    Password.check(creds.password, user.passwordHash).withArgon2()
                }.getOrDefault(false)

        if (user == null || !passwordOk) {
            logger.info("Token request rejected (invalid credentials)")
            call.respond(HttpStatusCode.Unauthorized, AuthErrorResponse("Invalid credentials"))
            return@post
        }

        if (!jwtConfig.isConfigured) {
            // Users configured but no JWT signing material — operator misconfiguration.
            logger.warn("Token request for a configured user but JWT signing is not configured")
            call.respond(HttpStatusCode.InternalServerError, AuthErrorResponse("Token issuance unavailable"))
            return@post
        }

        val signed = jwtConfig.sign(userId = user.username, role = user.role)
        logger.info("Issued access token for a user (role={})", user.role)
        call.respond(TokenResponse(token = signed.token, expiresAt = signed.expiresAt.toString()))
    }
}
