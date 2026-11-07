package com.jh.proj.coroutineviz.experiment

import com.jh.proj.coroutineviz.auth.ApiKeyStore
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The experiment as the application wires it: config, storage choice and the sessions list's access rules. */
class LookExperimentWiringTest {
    private val anon = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        SessionManager.clearAll()
    }

    private fun ApplicationTestBuilder.fullModule(vararg overrides: Pair<String, String>) {
        environment { config = MapApplicationConfig("lookExperiment.enabled" to "true", *overrides) }
        application { module() }
    }

    @Test
    fun `memory mode keeps the experiment in memory`() =
        testApplication {
            fullModule()

            assertTrue(client.get("/api/experiments/looks/config").bodyAsText().contains("\"enabled\":true"))
            assertTrue(client.get("/api/experiments/looks/results").bodyAsText().contains("\"storage\":\"memory\""))
        }

    @Test
    fun `database mode keeps it in the database`() =
        testApplication {
            val name = "look_${UUID.randomUUID().toString().replace("-", "")}"
            fullModule(
                "storage.type" to "database",
                "storage.database.url" to "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
                "storage.database.driver" to "org.h2.Driver",
            )

            assertTrue(client.get("/api/experiments/looks/results").bodyAsText().contains("\"storage\":\"database\""))
        }

    @Test
    fun `with auth on, every endpoint needs a credential, like the sessions list`() =
        testApplication {
            val key = "look-test-key"
            fullModule(
                "auth.keys.size" to "1",
                "auth.keys.0.name" to "frontend",
                "auth.keys.0.hash" to ApiKeyStore.sha256Hex(key),
                "auth.keys.0.role" to "viewer",
            )
            val vote = """{"anonId":"$anon","look":"a","rating":4}"""

            suspend fun calls(credential: String?) =
                listOf(
                    client.get("/api/sessions") { credential?.let { header("X-API-Key", it) } }.status,
                    client.get("/api/experiments/looks/config") { credential?.let { header("X-API-Key", it) } }.status,
                    client.get("/api/experiments/looks/results") { credential?.let { header("X-API-Key", it) } }.status,
                    client
                        .post("/api/experiments/looks/votes") {
                            credential?.let { header("X-API-Key", it) }
                            contentType(ContentType.Application.Json)
                            setBody(vote)
                        }.status,
                    client
                        .post("/api/experiments/looks/events") {
                            credential?.let { header("X-API-Key", it) }
                            contentType(ContentType.Application.Json)
                            setBody("""{"anonId":"$anon","events":[]}""")
                        }.status,
                )

            assertEquals(List(5) { HttpStatusCode.Unauthorized }, calls(null))
            assertEquals(
                listOf(HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.NoContent, HttpStatusCode.NoContent),
                calls(key),
            )
        }
}
