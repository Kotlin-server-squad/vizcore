package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.DatabaseKey
import com.jh.proj.coroutineviz.dbRetentionPolicy
import com.jh.proj.coroutineviz.health.SummaryLimits
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.persistence.SummaryCheckpoints
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** `health.summaries.*` reaches every summary table: live sessions, the replay fold, database sessions and the trim. */
class SummaryLimitsWiringTest {
    private val small = SummaryLimits(fullSummaryTarget = 3, trackedCapacity = 3)

    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        IngestStates.clear()
        HealthCuts.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        SessionManager.configure(maxEventsPerSession = 10_000)
        SessionManager.clearAll()
        IngestStates.clear()
        HealthCuts.clear()
    }

    private val smallConfig =
        arrayOf(
            "health.summaries.fullSummaryTarget" to "3",
            "health.summaries.trackedCapacity" to "3",
        )

    private fun VizSession.fourCoroutines() = (1..4).forEach { send(HealthEvents.created(sessionId, "c$it", sec(it.toLong()))) }

    @Test
    fun `the module configures in-memory sessions with the limits`() =
        testApplication {
            environment { config = MapApplicationConfig(*smallConfig) }
            application { module() }
            startApplication()
            val session = SessionManager.createSession("wired")
            session.fourCoroutines()
            assertEquals(small, session.summaryLimits)
            assertEquals(1L, session.readSummaries { it.untrackedCount })
        }

    @Test
    fun `the module gives the database store and the trim the limits`() =
        testApplication {
            val name = "wiring_" + UUID.randomUUID().toString().replace("-", "")
            val url = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
            environment {
                config =
                    MapApplicationConfig(
                        *smallConfig,
                        "storage.type" to "database",
                        "storage.database.url" to url,
                        "storage.database.driver" to "org.h2.Driver",
                        "storage.database.username" to "sa",
                        "storage.database.password" to "",
                        "storage.retention.maxEventsPerSession" to "1",
                    )
            }
            lateinit var trim: com.jh.proj.coroutineviz.persistence.DbRetentionPolicy
            lateinit var db: org.jetbrains.exposed.v1.jdbc.Database
            application {
                module()
                db = attributes[DatabaseKey]
                trim = dbRetentionPolicy(db) // the policy exactly as the application builds it
            }
            startApplication()
            assertIs<ExposedSessionStore>(SessionManager.backingStore())
            val id = SessionManager.createSession("wired-db").sessionId
            val writer = SessionManager.getSession(id)!!
            (1..6).forEach { writer.ingest(HealthEvents.created(id, "c$it", sec(it.toLong()))) }
            writer.close()

            val read = SessionManager.getSession(id)!!
            assertEquals(3L, read.readSummaries { it.untrackedCount })
            read.close()

            trim.cleanup() // keeps the newest event, folds c1..c5 into the checkpoint
            val checkpoint = transaction(db) { SummaryCheckpoints.read(id) }!!
            assertEquals(2L, checkpoint.untrackedCount, "c4 and c5 were folded beyond capacity")
        }

    @Test
    fun `the replay fold uses the session's limits`() {
        SessionManager.configure(maxEventsPerSession = 5, summaryLimits = small)
        val session = runBlocking { SessionManager.createSession("replay") }
        session.fourCoroutines() // c4 at seq 4 is beyond capacity
        session.send(HealthEvents.started(session.sessionId, "c1", sec(5)))
        session.send(HealthEvents.started(session.sessionId, "c2", sec(6))) // the store keeps 5: seq 1 (c1 created) leaves it

        val replay = readView(session, IngestStates.snapshot(session.sessionId), ViewBinding.Replay(4), DEFAULT_LEAK_MS)
        assertEquals(1L, replay.input.summaries.untrackedCount)
        val summaries = replay.input.summaries
        val tracked = summaries.all.map { it.id }
        assertEquals(listOf("c1", "c2", "c3"), tracked)
    }
}
