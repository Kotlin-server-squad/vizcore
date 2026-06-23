@file:Suppress("DEPRECATION")

package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.auth.ApiKeyStore
import com.jh.proj.coroutineviz.auth.JwtConfig
import com.jh.proj.coroutineviz.auth.Role
import com.jh.proj.coroutineviz.auth.UserStore
import com.jh.proj.coroutineviz.routes.LoginRequest
import com.jh.proj.coroutineviz.routes.registerAuthRoutes
import com.password4j.Password
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwtAuthTest {
    private val testSecret = "test-jwt-secret-do-not-use-in-prod"

    private fun seededUserStore(): UserStore {
        val hash = Password.hash("correct-horse").withArgon2().result
        return UserStore(listOf(UserStore.UserEntry("alice", hash, Role.RUNNER)))
    }

    private fun jwtConfig(): JwtConfig {
        val config =
            MapApplicationConfig(
                "auth.jwt.secret" to testSecret,
                "auth.jwt.issuer" to "coroutineviz",
                "auth.jwt.audience" to "coroutineviz-api",
                "auth.jwt.realm" to "coroutineviz",
                "auth.jwt.accessTtlMinutes" to "60",
            )
        return JwtConfig.fromConfig(config)
    }

    private fun ApplicationTestBuilder.jsonClient() =
        createClient {
            install(ContentNegotiation) { json() }
        }

    private fun ApplicationTestBuilder.installTokenApp(
        userStore: UserStore,
        jwtConfig: JwtConfig,
    ) {
        install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
        install(Authentication) {
            jwt("jwt") {
                realm = jwtConfig.realm
                verifier(jwtConfig.verifier()!!)
                validate { cred ->
                    cred.payload.subject?.let { io.ktor.server.auth.jwt.JWTPrincipal(cred.payload) }
                }
            }
        }
        routing {
            registerAuthRoutes(userStore, jwtConfig)
            authenticate("jwt") {
                get("/api/protected") {
                    call.respond(HttpStatusCode.OK, mapOf("ok" to "true"))
                }
            }
        }
    }

    @Test
    fun `token endpoint issues a JWT for seeded credentials`() =
        testApplication {
            installTokenApp(seededUserStore(), jwtConfig())
            val client = jsonClient()

            val response =
                client.post("/api/auth/token") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice", "correct-horse"))
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertNotNull(body["token"]?.jsonPrimitive?.content)
            assertNotNull(body["expiresAt"]?.jsonPrimitive?.content)
        }

    @Test
    fun `token endpoint rejects bad password with uniform Invalid credentials`() =
        testApplication {
            installTokenApp(seededUserStore(), jwtConfig())
            val client = jsonClient()

            val response =
                client.post("/api/auth/token") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice", "wrong-password"))
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("Invalid credentials", body["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `token endpoint rejects unknown user with the SAME uniform body (no enumeration)`() =
        testApplication {
            installTokenApp(seededUserStore(), jwtConfig())
            val client = jsonClient()

            val response =
                client.post("/api/auth/token") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("nobody", "whatever"))
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("Invalid credentials", body["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `a minted JWT authenticates a protected route via Bearer`() =
        testApplication {
            installTokenApp(seededUserStore(), jwtConfig())
            val client = jsonClient()

            val tokenResponse =
                client.post("/api/auth/token") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice", "correct-horse"))
                }
            val token =
                Json.parseToJsonElement(tokenResponse.bodyAsText())
                    .jsonObject["token"]!!.jsonPrimitive.content

            val ok =
                client.get("/api/protected") {
                    header("Authorization", "Bearer $token")
                }
            assertEquals(HttpStatusCode.OK, ok.status)
        }

    @Test
    fun `a garbage Bearer is rejected with 401`() =
        testApplication {
            installTokenApp(seededUserStore(), jwtConfig())
            val client = jsonClient()

            val response =
                client.get("/api/protected") {
                    header("Authorization", "Bearer not-a-real-jwt")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `an absent Bearer is rejected with 401`() =
        testApplication {
            installTokenApp(seededUserStore(), jwtConfig())
            val client = jsonClient()

            val response = client.get("/api/protected")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // -- ApiKeyStore unit assertions (AUTH-02) -------------------------

    @Test
    fun `ApiKeyStore validate returns the entry whose SHA-256 matches`() {
        val rawKey = "frontend-secret-key"
        val hash = ApiKeyStore.sha256Hex(rawKey)
        val store =
            ApiKeyStore(listOf(ApiKeyStore.KeyEntry("frontend", hash, Role.RUNNER)))

        val matched = store.validate(rawKey)
        assertNotNull(matched)
        assertEquals("frontend", matched.name)
        assertEquals(Role.RUNNER, matched.role)
    }

    @Test
    fun `ApiKeyStore validate returns null for a non-matching key`() {
        val hash = ApiKeyStore.sha256Hex("real-key")
        val store = ApiKeyStore(listOf(ApiKeyStore.KeyEntry("k", hash, Role.VIEWER)))

        assertNull(store.validate("wrong-key"))
    }

    @Test
    fun `empty ApiKeyStore validates nothing and reports empty`() {
        val store = ApiKeyStore(emptyList())
        assertTrue(store.isEmpty)
        assertNull(store.validate("anything"))
    }
}
