package com.jh.proj.coroutineviz.experiment

import com.jh.proj.coroutineviz.appJson
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LookExperimentRoutesTest {
    private val anon = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private var clock = 1_000L
    private val on = LookExperimentConfig(true, LookExperimentConfig.parseWeights("classic:25,a:25,b:25,c:25"), "https://x.org/s")

    private fun experiment(
        config: LookExperimentConfig = on,
        store: LookExperimentStore = InMemoryLookExperimentStore(100),
        eventsPerMinute: Int = 30,
        votesPerMinute: Int = 10,
    ) = LookExperiment(
        config = config,
        store = store,
        storage = "memory",
        now = { clock },
        eventLimiter = AnonRateLimiter(eventsPerMinute) { clock },
        voteLimiter = AnonRateLimiter(votesPerMinute) { clock },
    )

    private fun ApplicationTestBuilder.app(experiment: LookExperiment) {
        application {
            install(ContentNegotiation) { json(appJson) }
            routing { registerLookExperimentRoutes(experiment) }
        }
    }

    private suspend fun ApplicationTestBuilder.postEvents(
        body: String,
        type: ContentType = ContentType.Application.Json,
    ) = client.post("/api/experiments/looks/events") {
        contentType(type)
        setBody(body)
    }

    private suspend fun ApplicationTestBuilder.postVote(body: String) =
        client.post("/api/experiments/looks/votes") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `the config says off and nothing more when the experiment is off`() =
        testApplication {
            app(experiment(config = on.copy(enabled = false)))

            assertEquals("""{"enabled":false}""", client.get("/api/experiments/looks/config").bodyAsText())
        }

    @Test
    fun `the config gives the weights and the survey link when on`() =
        testApplication {
            app(experiment())

            val body = Json.parseToJsonElement(client.get("/api/experiments/looks/config").bodyAsText()).jsonObject
            assertEquals("true", body["enabled"]!!.jsonPrimitive.content)
            assertEquals("25", body["weights"]!!.jsonObject["b"]!!.jsonPrimitive.content)
            assertEquals("https://x.org/s", body["surveyUrl"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a batch of events is stored, also when sent as text by a beacon`() =
        testApplication {
            val store = InMemoryLookExperimentStore(100)
            app(experiment(store = store))

            val first =
                postEvents(
                    """{"anonId":"$anon","events":[{"type":"entry","look":"b","kind":"assigned"},
                    {"type":"seen","look":"b"},{"type":"time","look":"b","ms":1500}]}""",
                )
            val second =
                postEvents("""{"anonId":"$anon","events":[{"type":"switch","from":"b","to":"c"}],"extra":1}""", ContentType.Text.Plain)

            assertEquals(HttpStatusCode.NoContent, first.status)
            assertEquals(HttpStatusCode.NoContent, second.status)
            val data = store.snapshot()
            assertEquals(listOf(VisitorRow(anon, "b", EntryKind.ASSIGNED, 1_000)), data.visitors)
            assertEquals(listOf(ExposureRow(anon, "b", 1, 1_500)), data.exposures)
            assertEquals(listOf(SwitchRow(anon, "b", "c", 1)), data.switches)
        }

    @Test
    fun `an invalid batch is refused whole`() =
        testApplication {
            val store = InMemoryLookExperimentStore(100)
            app(experiment(store = store))
            val many = (1..51).joinToString(",") { """{"type":"seen","look":"a"}""" }

            listOf(
                "not json",
                """{"anonId":"not-a-uuid","events":[]}""",
                """{"anonId":"$anon","events":[{"type":"seen","look":"z"}]}""",
                """{"anonId":"$anon","events":[{"type":"seen","look":"a"},{"type":"click","look":"a"}]}""",
                """{"anonId":"$anon","events":[{"type":"time","look":"a","ms":0}]}""",
                """{"anonId":"$anon","events":[{"type":"switch","from":"a","to":"a"}]}""",
                """{"anonId":"$anon","events":[{"type":"entry","look":"a","kind":"forced"}]}""",
                """{"anonId":"$anon","events":[$many]}""",
            ).forEach { body ->
                assertEquals(HttpStatusCode.BadRequest, postEvents(body).status, body)
            }
            assertEquals(ExperimentData(), store.snapshot())
        }

    @Test
    fun `a long visible time is clamped to 12 hours`() =
        testApplication {
            val store = InMemoryLookExperimentStore(100)
            app(experiment(store = store))

            postEvents("""{"anonId":"$anon","events":[{"type":"time","look":"a","ms":999999999999}]}""")

            assertEquals(
                43_200_000L,
                store
                    .snapshot()
                    .exposures
                    .single()
                    .visibleMs,
            )
        }

    @Test
    fun `an oversized body is refused before it is read whole`() =
        testApplication {
            app(experiment())
            val huge = """{"anonId":"$anon","events":[],"pad":"${"x".repeat(40_000)}"}"""

            assertEquals(HttpStatusCode.PayloadTooLarge, postEvents(huge).status)
            val chunked =
                client.post("/api/experiments/looks/votes") {
                    contentType(ContentType.Application.Json)
                    setBody(ByteReadChannel(huge.toByteArray()))
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
        }

    @Test
    fun `when off nothing is recorded`() =
        testApplication {
            val store = InMemoryLookExperimentStore(100)
            app(experiment(config = on.copy(enabled = false), store = store))

            val events = postEvents("""{"anonId":"$anon","events":[{"type":"seen","look":"a"}]}""")
            val vote = postVote("""{"anonId":"$anon","look":"a","rating":4}""")

            assertEquals(HttpStatusCode.NotFound, events.status)
            assertEquals(HttpStatusCode.NotFound, vote.status)
            assertEquals(ExperimentData(), store.snapshot())
        }

    @Test
    fun `a vote is stored and a new one replaces it`() =
        testApplication {
            val store = InMemoryLookExperimentStore(100)
            app(experiment(store = store))

            assertEquals(HttpStatusCode.NoContent, postVote("""{"anonId":"$anon","look":"c","rating":2,"comment":" meh "}""").status)
            clock = 2_000
            assertEquals(HttpStatusCode.NoContent, postVote("""{"anonId":"$anon","look":"c","rating":5,"comment":"  "}""").status)

            assertEquals(listOf(VoteRow(anon, "c", 5, null, 2_000)), store.snapshot().votes)
        }

    @Test
    fun `a vote outside 1 to 5, an unknown look or a long comment is refused`() =
        testApplication {
            app(experiment())

            listOf(
                """{"anonId":"$anon","look":"c","rating":0}""",
                """{"anonId":"$anon","look":"c","rating":6}""",
                """{"anonId":"$anon","look":"q","rating":3}""",
                """{"anonId":"$anon","look":"c","rating":3,"comment":"${"x".repeat(501)}"}""",
            ).forEach { assertEquals(HttpStatusCode.BadRequest, postVote(it).status, it) }
            assertEquals(
                HttpStatusCode.NoContent,
                postVote("""{"anonId":"$anon","look":"c","rating":3,"comment":"${"x".repeat(500)}"}""").status,
            )
        }

    @Test
    fun `writes are limited per anonymous id`() =
        testApplication {
            app(experiment(eventsPerMinute = 2, votesPerMinute = 1))
            val batch = """{"anonId":"$anon","events":[{"type":"seen","look":"a"}]}"""
            val other = batch.replace(anon, "1f8fad5b-d9cb-469f-a165-70867728950e")

            repeat(2) { assertEquals(HttpStatusCode.NoContent, postEvents(batch).status) }
            val limited = postEvents(batch)
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertNotNull(limited.headers[HttpHeaders.RetryAfter])
            assertEquals(HttpStatusCode.NoContent, postEvents(other).status, "another id has its own budget")
            assertEquals(HttpStatusCode.NoContent, postVote("""{"anonId":"$anon","look":"a","rating":3}""").status)
            assertEquals(HttpStatusCode.TooManyRequests, postVote("""{"anonId":"$anon","look":"a","rating":4}""").status)

            clock += 60_000
            assertEquals(HttpStatusCode.NoContent, postEvents(batch).status, "a new minute, a new budget")
        }

    @Test
    fun `the results have one row per look and the decision`() =
        testApplication {
            app(experiment())
            postEvents("""{"anonId":"$anon","events":[{"type":"entry","look":"b","kind":"chose"},{"type":"seen","look":"b"}]}""")
            postVote("""{"anonId":"$anon","look":"b","rating":4,"comment":"calm"}""")

            val body = client.get("/api/experiments/looks/results").bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject

            assertEquals("memory", json["storage"]!!.jsonPrimitive.content)
            assertEquals("RUNNING", json["decision"]!!.jsonObject["status"]!!.jsonPrimitive.content)
            assertTrue("\"comment\":\"calm\"" in body)
            assertTrue(anon !in body, "no anonymous id leaves the server")
        }
}
