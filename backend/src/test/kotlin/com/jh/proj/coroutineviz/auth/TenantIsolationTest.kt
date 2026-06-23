package com.jh.proj.coroutineviz.auth

import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AUTH-04 tenant-isolation coverage against the DB-backed [ExposedSessionStore]
 * (H2). The tenant filter lives in the store (T-03-09); these tests exercise the
 * four D-03/D-04b truths directly through the tenant-scoped store API plus the
 * principal → [TenantContext] resolution that the route layer applies.
 */
class TenantIsolationTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var db: Database
    private lateinit var store: ExposedSessionStore

    private val aliceCtx = TenantContext.Scoped("alice")
    private val bobCtx = TenantContext.Scoped("bob")

    @BeforeEach
    fun setUp() {
        val name = "test_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource = h2DataSource("jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE")
        db = DatabaseFactory.init(dataSource as DataSource)
        store = ExposedSessionStore(db)
    }

    @AfterEach
    fun tearDown() {
        dataSource.close()
    }

    // -- D-03: principal → TenantContext resolution ----------------------------

    @Test
    fun `a JWT UserPrincipal resolves to its userId as the tenant`() {
        val ctx = TenantContext.resolve(UserPrincipal("alice", Role.RUNNER))
        assertEquals(TenantContext.Scoped("alice"), ctx)
    }

    @Test
    fun `an ApiKeyPrincipal resolves to its key name as the tenant`() {
        val ctx = TenantContext.resolve(ApiKeyPrincipal("frontend", Role.RUNNER))
        assertEquals(TenantContext.Scoped("frontend"), ctx)
    }

    @Test
    fun `an ADMIN principal resolves to Admin (filter bypass)`() {
        assertEquals(TenantContext.Admin, TenantContext.resolve(UserPrincipal("root", Role.ADMIN)))
        assertEquals(TenantContext.Admin, TenantContext.resolve(ApiKeyPrincipal("ops", Role.ADMIN)))
    }

    @Test
    fun `a null principal (auth off) resolves to Unscoped`() {
        assertEquals(TenantContext.Unscoped, TenantContext.resolve(null))
    }

    // -- AUTH-04: cross-tenant reads are denied --------------------------------

    @Test
    fun `tenant B cannot list or read tenant A's session`() {
        val aliceSession = runBlocking { store.createSession("a-work", aliceCtx) }

        // Bob lists: must NOT see alice's session.
        val bobList = store.listSessions(bobCtx)
        assertTrue(bobList.none { it.sessionId == aliceSession.sessionId })

        // Bob gets alice's id by id: cross-tenant → not found (null).
        assertNull(store.getSession(aliceSession.sessionId, bobCtx))

        // Alice still sees her own.
        assertNotNull(store.getSession(aliceSession.sessionId, aliceCtx))
        assertTrue(store.listSessions(aliceCtx).any { it.sessionId == aliceSession.sessionId })
    }

    @Test
    fun `tenant B cannot delete tenant A's session`() {
        val aliceSession = runBlocking { store.createSession("a-work", aliceCtx) }

        // Cross-tenant delete is a no-op.
        assertFalse(store.deleteSession(aliceSession.sessionId, bobCtx))
        assertNotNull(store.getSession(aliceSession.sessionId, aliceCtx))

        // Owner can delete.
        assertTrue(store.deleteSession(aliceSession.sessionId, aliceCtx))
        assertNull(store.getSession(aliceSession.sessionId, aliceCtx))
    }

    // -- D-03: ADMIN bypass ----------------------------------------------------

    @Test
    fun `an ADMIN sees ALL tenants' sessions, bypassing the filter`() {
        val aliceSession = runBlocking { store.createSession("a-work", aliceCtx) }
        val bobSession = runBlocking { store.createSession("b-work", bobCtx) }

        val adminList = store.listSessions(TenantContext.Admin)
        val ids = adminList.map { it.sessionId }.toSet()
        assertTrue(aliceSession.sessionId in ids)
        assertTrue(bobSession.sessionId in ids)

        assertNotNull(store.getSession(aliceSession.sessionId, TenantContext.Admin))
        assertNotNull(store.getSession(bobSession.sessionId, TenantContext.Admin))
    }

    // -- D-03: api-key-name fallback isolation ---------------------------------

    @Test
    fun `two api keys are isolated from each other (tenant = key name)`() {
        val keyA = TenantContext.resolve(ApiKeyPrincipal("team-a", Role.RUNNER))
        val keyB = TenantContext.resolve(ApiKeyPrincipal("team-b", Role.RUNNER))

        val sessionA = runBlocking { store.createSession("ka", keyA) }

        assertTrue(store.listSessions(keyB).none { it.sessionId == sessionA.sessionId })
        assertNull(store.getSession(sessionA.sessionId, keyB))
        assertNotNull(store.getSession(sessionA.sessionId, keyA))
    }

    // -- D-04b: auth-off global visibility -------------------------------------

    @Test
    fun `auth-off (Unscoped) lists ALL sessions globally`() {
        // Two tenant-owned sessions plus one created unscoped.
        val aliceSession = runBlocking { store.createSession("a-work", aliceCtx) }
        val bobSession = runBlocking { store.createSession("b-work", bobCtx) }
        val anonSession = runBlocking { store.createSession("anon", TenantContext.Unscoped) }

        val global = store.listSessions(TenantContext.Unscoped).map { it.sessionId }.toSet()
        assertTrue(aliceSession.sessionId in global)
        assertTrue(bobSession.sessionId in global)
        assertTrue(anonSession.sessionId in global)

        // Unscoped can read any of them by id.
        assertNotNull(store.getSession(aliceSession.sessionId, TenantContext.Unscoped))
        assertNotNull(store.getSession(anonSession.sessionId, TenantContext.Unscoped))
    }

    @Test
    fun `an unscoped-created (global) session is NOT visible to a scoped tenant`() {
        // Sanity: a session with no owner (tenant_id = null) is only global, not
        // attributed to any tenant — so a scoped tenant must not see it.
        val anonSession = runBlocking { store.createSession("anon", TenantContext.Unscoped) }
        assertTrue(store.listSessions(aliceCtx).none { it.sessionId == anonSession.sessionId })
        assertNull(store.getSession(anonSession.sessionId, aliceCtx))
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
