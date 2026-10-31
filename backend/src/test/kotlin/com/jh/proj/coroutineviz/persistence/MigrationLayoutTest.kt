package com.jh.proj.coroutineviz.persistence

import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Guards the migration layout: portable migrations in `db/migration/common`, vendor-specific ones in
 * `db/migration/h2` and `db/migration/postgresql` (see [DatabaseFactory]).
 */
class MigrationLayoutTest {
    private fun resource(path: String): String? = javaClass.classLoader.getResource(path)?.readText()

    @Test
    fun `the H2 V1 is byte for byte the V1 existing databases applied, so they still validate`() {
        val h2 = assertNotNull(javaClass.classLoader.getResource("db/migration/h2/V1__core_schema.sql")).readBytes()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(h2).joinToString("") { "%02x".format(it) }

        assertEquals("7b03b7f7f3d182f7a508542b02f7ab3c35d0a38baa9ce30d78f6635a03732040", sha256)
    }

    @Test
    fun `V1 exists once per vendor and no longer in common`() {
        assertNull(resource("db/migration/common/V1__core_schema.sql"), "it would be a duplicate version 1 for each vendor")
        assertNotNull(resource("db/migration/postgresql/V1__core_schema.sql"))
    }

    @Test
    fun `the PostgreSQL V1 differs from the H2 one only in its text columns`() {
        fun statements(sql: String) =
            sql
                .lines()
                .map { it.substringBefore("--").trim() }
                .filter { it.isNotEmpty() }
        val h2 = statements(assertNotNull(resource("db/migration/h2/V1__core_schema.sql")))
        val postgres = statements(assertNotNull(resource("db/migration/postgresql/V1__core_schema.sql")))

        assertEquals(h2.map { it.replace(Regex("\\bclob\\b"), "text") }, postgres)
    }
}
