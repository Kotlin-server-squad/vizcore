@file:Suppress("DEPRECATION")

package com.jh.proj.coroutineviz.auth

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.routes.registerScenarioRunnerRoutes
import com.jh.proj.coroutineviz.routes.registerSessionRoutes
import com.jh.proj.coroutineviz.routes.registerSyncScenarioRoutes
import com.jh.proj.coroutineviz.routes.registerValidationRoutes
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.share.ShareService
import com.jh.proj.coroutineviz.share.registerShareOwnerRoutes
import com.jh.proj.coroutineviz.share.registerSharedPublicRoute
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
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
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

/**
 * AUTH-05 end-to-end tenant-isolation coverage (closes the CR-01/CR-02 gap that
 * the store-level [TenantIsolationTest] could not catch). These tests wire the
 * REAL session + share routes behind a REAL JWT `authenticate("jwt")` block
 * against an H2-backed [ExposedSessionStore], so `call.resolveTenant()` resolves
 * the tenant from the verified JWT `sub` exactly as production does.
 *
 * The central invariant under test: a principal (bob) can NEVER read another
 * tenant's (alice's) session content through any session-bound route, the SSE
 * stream, or the share-owner routes — every cross-tenant access is a 404 with no
 * data leak, while alice's own access keeps working.
 */
@OptIn(ExperimentalTime::class)
class TenantIsolationE2ETest {
    private val testSecret = "test-jwt-secret-do-not-use-in-prod"

    private lateinit var dataSource: HikariDataSource
    private lateinit var db: Database
    private lateinit var store: ExposedSessionStore

    @BeforeEach
    fun setUp() {
        val name = "test_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource = h2DataSource("jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE")
        db = DatabaseFactory.init(dataSource as DataSource)
        store = ExposedSessionStore(db)
        SessionManager.useStore(store)
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        dataSource.close()
    }

    private fun jwtConfig(): JwtConfig =
        JwtConfig.fromConfig(
            MapApplicationConfig(
                "auth.jwt.secret" to testSecret,
                "auth.jwt.issuer" to "coroutineviz",
                "auth.jwt.audience" to "coroutineviz-api",
                "auth.jwt.realm" to "coroutineviz",
                "auth.jwt.accessTtlMinutes" to "60",
            ),
        )

    /** Mint a real signed JWT for [userId] and map it to a [UserPrincipal] in validate. */
    private fun token(
        userId: String,
        role: Role = Role.RUNNER,
    ): String = jwtConfig().sign(userId, role).token

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json() } }

    /**
     * Wire the real routes behind a real jwt provider whose validate produces a
     * [UserPrincipal] (matching production Auth.kt) so `currentPrincipal()` /
     * `resolveTenant()` resolve the tenant from the JWT `sub`.
     */
    private fun ApplicationTestBuilder.installAuthedApp() {
        val jwtConfig = jwtConfig()
        val service = ShareService(db)
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json(appJson)
            }
            install(io.ktor.server.sse.SSE)
            install(Authentication) {
                jwt("jwt") {
                    realm = jwtConfig.realm
                    // Mirror production Auth.kt: read the JWT from the Bearer header
                    // OR the `?token=` query param (browser EventSource cannot set
                    // headers, so the authenticated SSE path supplies the token there).
                    authHeader { call ->
                        call.request.headers["Authorization"]?.let { raw ->
                            return@authHeader runCatching {
                                io.ktor.http.auth.parseAuthorizationHeader(raw)
                            }.getOrNull()
                        }
                        call.request.queryParameters[com.jh.proj.coroutineviz.SSE_TOKEN_QUERY_PARAM]
                            ?.takeIf { it.isNotBlank() }
                            ?.let { io.ktor.http.auth.HttpAuthHeader.Single("Bearer", it) }
                    }
                    verifier(jwtConfig.verifier()!!)
                    validate { cred ->
                        val sub = cred.payload.subject ?: return@validate null
                        val role = Role.fromConfig(cred.payload.getClaim("role").asString())
                        UserPrincipal(userId = sub, role = role)
                    }
                }
            }
            routing {
                // Public read route — the token IS the credential (outside auth).
                registerSharedPublicRoute(service)
                authenticate("jwt") {
                    registerSessionRoutes()
                    registerScenarioRunnerRoutes()
                    registerValidationRoutes()
                    registerSyncScenarioRoutes()
                    registerShareOwnerRoutes(service, publicBaseUrl = "https://app.example.com")
                }
            }
        }
    }

    /** Seed a session owned by [tenant] with a single replayable event; returns (sessionId, coroutineId). */
    private fun seedOwnedSessionWithEvent(tenant: String): Pair<String, String> {
        val session: VizSession = runBlocking { store.createSession("$tenant-work", TenantContext.Scoped(tenant)) }
        session.send(
            CoroutineCreated(
                sessionId = session.sessionId,
                seq = 1,
                tsNanos = 1_000,
                coroutineId = "c1",
                jobId = "j1",
                parentCoroutineId = null,
                scopeId = "s1",
                label = "first",
            ),
        )
        return session.sessionId to "c1"
    }

    // -- CR-01: cross-tenant session sub-resource reads are 404 ----------------

    @Test
    fun `tenant B gets 404 on tenant A's events hierarchy threads and timeline while A still gets 200`() =
        testApplication {
            installAuthedApp()
            val client = jsonClient()
            val (aId, cId) = seedOwnedSessionWithEvent("alice")
            val alice = token("alice")
            val bob = token("bob")

            // Every session-bound sub-resource (incl. timeline) is a cross-tenant 404 for bob.
            val crossTenantPaths =
                listOf(
                    "/api/sessions/$aId/events",
                    "/api/sessions/$aId/hierarchy",
                    "/api/sessions/$aId/threads",
                    "/api/sessions/$aId/coroutines/$cId/timeline",
                )
            for (path in crossTenantPaths) {
                val bobResp = client.get(path) { header("Authorization", "Bearer $bob") }
                assertEquals(
                    HttpStatusCode.NotFound,
                    bobResp.status,
                    "bob (cross-tenant) must get 404 on $path",
                )
            }

            // Owner-positive 200: events/hierarchy/threads resolve for alice (the
            // session is visible to her). Timeline is excluded from the owner-200
            // assertion because a DB-rebuilt session's projection is not replayed
            // from stored events (pre-existing, out of this plan's scope — see
            // deferred-items.md); its cross-tenant 404 above still guards isolation.
            for (path in
                listOf(
                    "/api/sessions/$aId/events",
                    "/api/sessions/$aId/hierarchy",
                    "/api/sessions/$aId/threads",
                )) {
                val aliceResp = client.get(path) { header("Authorization", "Bearer $alice") }
                assertEquals(
                    HttpStatusCode.OK,
                    aliceResp.status,
                    "alice (owner) must still get 200 on $path",
                )
            }

            // Owner sees her event content on /events; bob's 404 body never leaks it.
            val aliceEvents = client.get("/api/sessions/$aId/events") { header("Authorization", "Bearer $alice") }
            assertTrue(aliceEvents.bodyAsText().contains("c1"), "alice must see her own event content on /events")
            val bobEvents = client.get("/api/sessions/$aId/events") { header("Authorization", "Bearer $bob") }
            assertFalse(bobEvents.bodyAsText().contains("\"c1\""), "404 body must not leak alice's coroutine id")
        }

    // -- AUTH-04 write path: cross-tenant scenario-run cannot mutate another's session --

    @Test
    fun `tenant B running a scenario with tenant A's sessionId does NOT write into A's session`() =
        testApplication {
            installAuthedApp()
            val client = jsonClient()
            val (aId, _) = seedOwnedSessionWithEvent("alice")
            val bob = token("bob")

            // bob targets alice's session id on a mutating scenario-run route.
            val resp =
                client.post("/api/scenarios/nested?sessionId=$aId") {
                    header("Authorization", "Bearer $bob")
                }
            assertEquals(HttpStatusCode.OK, resp.status)

            // The scoped get-or-create must NOT resolve alice's id for bob: the run is
            // redirected to a fresh, bob-owned session (never a write into alice's).
            val returnedId =
                Json.parseToJsonElement(resp.bodyAsText()).jsonObject["sessionId"]?.jsonPrimitive?.content
            assertFalse(returnedId == aId, "bob's scenario must NOT target alice's session id; got $returnedId")

            // Drain bob's scenario to FULL completion so all async coroutine writes —
            // including the terminal CoroutineCompleted events — land BEFORE @AfterEach
            // closes the datasource. Otherwise a late background write races into a
            // subsequent test as an uncaught completion exception (flaky contamination).
            var drainAttempts = 0
            while (drainAttempts < 100) {
                val body = client.get("/api/sessions/$returnedId/events") { header("Authorization", "Bearer $bob") }.bodyAsText()
                val completed = Regex("CoroutineCompleted").findAll(body).count()
                if (completed >= 4) break // nested = parent + 3 children, all terminal
                delay(100)
                drainAttempts++
            }
            delay(300) // grace for any completion handlers to flush their final write

            // alice's session is untouched: still exactly the one seeded event (c1), no
            // coroutines/events injected by bob.
            val aliceView = client.get("/api/sessions/$aId/events") { header("Authorization", "Bearer ${token("alice")}") }
            val aliceEvents = Json.parseToJsonElement(aliceView.bodyAsText()).jsonArray
            assertEquals(1, aliceEvents.size, "alice's session must keep only her seeded event; got ${aliceEvents.size}")
        }

    // -- F7: validation route must resolve via the tenant-scoped store ---------

    @Test
    fun `validate session works in DB mode for the owner and stays 404 cross-tenant`() =
        testApplication {
            installAuthedApp()
            val client = jsonClient()
            val (aId, _) = seedOwnedSessionWithEvent("alice")
            val alice = token("alice")
            val bob = token("bob")

            // F7 regression: in DB mode the session lives in the Exposed store, NOT the
            // in-memory SessionManager. The old route looked it up via SessionManager and
            // 404'd for EVERY real session. The owner must now get 200 with validator results.
            val ownerResp = client.post("/api/validate/session/$aId") { header("Authorization", "Bearer $alice") }
            assertEquals(HttpStatusCode.OK, ownerResp.status, "owner validation must succeed in DB mode (F7)")
            val body = Json.parseToJsonElement(ownerResp.bodyAsText()).jsonObject
            assertEquals(aId, body["sessionId"]?.jsonPrimitive?.content, "must echo the validated session id")
            assertTrue(body.containsKey("results"), "validation response must contain results")
            assertTrue(body.containsKey("timing"), "validation response must contain a timing report")

            // Tenant scoping is preserved: bob validating alice's session is a cross-tenant 404.
            val crossResp = client.post("/api/validate/session/$aId") { header("Authorization", "Bearer $bob") }
            assertEquals(HttpStatusCode.NotFound, crossResp.status, "cross-tenant validation must 404 (no leak)")
            assertFalse(crossResp.bodyAsText().contains("\"c1\""), "404 body must not leak alice's coroutine id")
        }

    // -- F9: sync-scenario routes must create the session in the tenant-scoped store ----

    @Test
    fun `sync scenario session is tenant-scoped — owner can read and validate, cross-tenant cannot`() =
        testApplication {
            installAuthedApp()
            val client = jsonClient()
            val alice = token("alice")
            val bob = token("bob")

            // F9 regression: the sync routes used the unscoped SessionManager.createSession, so in
            // DB+auth mode the resulting session was orphaned from the tenant — 404 on /events and
            // /validate, and absent from the session list — despite the run reporting success.
            // bank-transfer is a light mutex scenario (~37 events). Run it as alice.
            val run = client.get("/api/sync/mutex/bank-transfer") { header("Authorization", "Bearer $alice") }
            assertEquals(HttpStatusCode.OK, run.status, "sync scenario should run")
            val sid = Json.parseToJsonElement(run.bodyAsText()).jsonObject["sessionId"]!!.jsonPrimitive.content
            assertTrue(sid.isNotBlank(), "run must return a session id")

            // Owner: the session is now visible (events 200) and validatable (200) — the F9 fix.
            val ownerEvents = client.get("/api/sessions/$sid/events") { header("Authorization", "Bearer $alice") }
            assertEquals(HttpStatusCode.OK, ownerEvents.status, "owner must read the sync session's events (F9)")
            val ownerValidate = client.post("/api/validate/session/$sid") { header("Authorization", "Bearer $alice") }
            assertEquals(HttpStatusCode.OK, ownerValidate.status, "owner must validate the sync session (F9)")

            // The session appears in alice's list (tenant-scoped membership).
            val aliceList = client.get("/api/sessions") { header("Authorization", "Bearer $alice") }
            assertTrue(aliceList.bodyAsText().contains(sid), "sync session must appear in the owner's session list")

            // Cross-tenant: bob can neither read nor validate alice's sync session.
            val bobEvents = client.get("/api/sessions/$sid/events") { header("Authorization", "Bearer $bob") }
            assertEquals(HttpStatusCode.NotFound, bobEvents.status, "cross-tenant read of sync session must 404")
            val bobValidate = client.post("/api/validate/session/$sid") { header("Authorization", "Bearer $bob") }
            assertEquals(HttpStatusCode.NotFound, bobValidate.status, "cross-tenant validate of sync session must 404")
        }

    // -- CR-01: cross-tenant SSE is a pre-stream 404 with NO replay ------------

    @Test
    fun `tenant B SSE stream yields a pre-stream 404 error with no connected comment and no replayed events`() =
        testApplication {
            installAuthedApp()
            val client = jsonClient()
            val (aId, _) = seedOwnedSessionWithEvent("alice")
            val bob = token("bob")

            // The cross-tenant SSE must terminate with the not-found error event and
            // never send the 'connected' comment nor replay alice's stored event.
            val body =
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        client.get("/api/sessions/$aId/stream?token=$bob").bodyAsText()
                    }
                }
            assertTrue(body.contains("event: error"), "cross-tenant SSE must emit an error event; body=$body")
            assertTrue(body.contains("Session not found"), "cross-tenant SSE must report not found; body=$body")
            assertFalse(body.contains("connected"), "cross-tenant SSE must NOT send the 'connected' comment; body=$body")
            assertFalse(body.contains("\"c1\""), "cross-tenant SSE must NOT replay alice's event; body=$body")
            assertFalse(body.contains("CoroutineCreated"), "cross-tenant SSE must NOT replay alice's event; body=$body")
        }

    @Test
    fun `tenant A SSE stream replays its own stored event (proves the guard is not a blanket 404)`() =
        testApplication {
            installAuthedApp()
            val client = jsonClient()
            val (aId, _) = seedOwnedSessionWithEvent("alice")
            val alice = token("alice")

            // alice owns the session: the stream replays her stored event (a data: line).
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    client.prepareGet("/api/sessions/$aId/stream?token=$alice").execute { response ->
                        assertEquals(HttpStatusCode.OK, response.status)
                        val channel = response.bodyAsChannel()
                        var sawConnected = false
                        var sawData = false
                        var line: String? = channel.readUTF8Line()
                        while (line != null && !sawData) {
                            if (line.startsWith(":") && line.contains("connected")) sawConnected = true
                            if (line.startsWith("data:") && line.contains("c1")) sawData = true
                            line = channel.readUTF8Line()
                        }
                        assertTrue(sawConnected, "owner stream should send the 'connected' comment")
                        assertTrue(sawData, "owner stream should replay alice's stored event (c1)")
                    }
                }
            }
        }

    // -- CR-02: share-owner routes enforce ownership --------------------------

    @Test
    fun `tenant B cannot mint a share on tenant A's session 404 while A can 201`() =
        testApplication {
            installAuthedApp()
            val client = jsonClient()
            val (aId, _) = seedOwnedSessionWithEvent("alice")
            val alice = token("alice")
            val bob = token("bob")

            val bobMint =
                client.post("/api/sessions/$aId/share") {
                    header("Authorization", "Bearer $bob")
                    contentType(ContentType.Application.Json)
                    setBody("""{"expiresIn":"7d"}""")
                }
            assertEquals(HttpStatusCode.NotFound, bobMint.status, "bob must not mint a share on alice's session")

            val aliceMint =
                client.post("/api/sessions/$aId/share") {
                    header("Authorization", "Bearer $alice")
                    contentType(ContentType.Application.Json)
                    setBody("""{"expiresIn":"7d"}""")
                }
            assertEquals(HttpStatusCode.Created, aliceMint.status, "alice (owner) must mint a share")
        }

    @Test
    fun `ADMIN cannot mint a tenant-invisible share on another tenant's session (WR-02)`() =
        testApplication {
            installAuthedApp()
            val client = jsonClient()
            val (aId, _) = seedOwnedSessionWithEvent("alice")
            val admin = token("root", Role.ADMIN)

            // An ADMIN can SEE every session (Op.TRUE bypass), but minting must be
            // owner-scoped: an admin-minted share on alice's session would be
            // invisible (list is created_by-scoped) and unrevocable to alice.
            val adminMint =
                client.post("/api/sessions/$aId/share") {
                    header("Authorization", "Bearer $admin")
                    contentType(ContentType.Application.Json)
                    setBody("""{"expiresIn":"7d"}""")
                }
            assertEquals(
                HttpStatusCode.NotFound,
                adminMint.status,
                "an ADMIN must not mint a share on a session it does not own",
            )
        }

    @Test
    fun `tenant B's share list does not contain tenant A's shares while A sees its own`() =
        testApplication {
            installAuthedApp()
            val client = jsonClient()
            val (aId, _) = seedOwnedSessionWithEvent("alice")
            val alice = token("alice")
            val bob = token("bob")

            val aliceToken = mintShare(client, aId, alice)

            // bob lists alice's session shares: must be empty (none owned by bob).
            val bobList = client.get("/api/sessions/$aId/shares") { header("Authorization", "Bearer $bob") }
            val bobShares = Json.parseToJsonElement(bobList.bodyAsText()).jsonArray
            assertTrue(bobShares.isEmpty(), "bob must not see alice's shares; got $bobShares")

            // alice lists: contains her token.
            val aliceList = client.get("/api/sessions/$aId/shares") { header("Authorization", "Bearer $alice") }
            val aliceShares = Json.parseToJsonElement(aliceList.bodyAsText()).jsonArray
            assertTrue(
                aliceShares.any { it.jsonObject["token"]?.jsonPrimitive?.content == aliceToken },
                "alice must see her own share token",
            )
        }

    @Test
    fun `tenant B cannot revoke tenant A's share 404 and it still resolves while A can revoke 204`() =
        testApplication {
            installAuthedApp()
            val client = jsonClient()
            val (aId, _) = seedOwnedSessionWithEvent("alice")
            val alice = token("alice")
            val bob = token("bob")

            val aliceToken = mintShare(client, aId, alice)

            // bob's revoke is a no-op → 404, and the share still resolves publicly.
            val bobDel =
                client.delete("/api/sessions/$aId/shares/$aliceToken") { header("Authorization", "Bearer $bob") }
            assertEquals(HttpStatusCode.NotFound, bobDel.status, "bob must not revoke alice's share")
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/shared/$aliceToken").status,
                "alice's share must still resolve after bob's failed revoke",
            )

            // alice (creator) revokes → 204, then the public read 404s.
            val aliceDel =
                client.delete("/api/sessions/$aId/shares/$aliceToken") { header("Authorization", "Bearer $alice") }
            assertEquals(HttpStatusCode.NoContent, aliceDel.status, "alice (creator) must revoke her share")
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/shared/$aliceToken").status,
                "revoked share must no longer resolve",
            )
        }

    private suspend fun mintShare(
        client: io.ktor.client.HttpClient,
        sessionId: String,
        bearer: String,
    ): String {
        val resp =
            client.post("/api/sessions/$sessionId/share") {
                header("Authorization", "Bearer $bearer")
                contentType(ContentType.Application.Json)
                setBody("""{"expiresIn":"7d"}""")
            }
        assertEquals(HttpStatusCode.Created, resp.status, "mint should succeed for the owner")
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }

    private fun h2DataSource(url: String): HikariDataSource {
        val config =
            HikariConfig().apply {
                jdbcUrl = url
                driverClassName = "org.h2.Driver"
                username = "sa"
                password = ""
                maximumPoolSize = 4
                isAutoCommit = false
            }
        return HikariDataSource(config)
    }
}
