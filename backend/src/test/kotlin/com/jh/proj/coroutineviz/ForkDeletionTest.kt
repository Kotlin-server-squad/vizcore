package com.jh.proj.coroutineviz

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Static guard: asserts that the forked session classes do NOT exist under
 * backend/src/main/kotlin/com/jh/proj/coroutineviz/session/.
 *
 * This makes a silent re-introduction of the fork fail CI immediately (FND-01).
 * The fork was deleted as part of Plan 01-03 (big-bang de-fork). If any of the
 * deleted class files reappears, this test fails with a clear message listing
 * the re-introduced files.
 *
 * The guard checks for exact class file names that were in the fork. The session
 * package exists only in coroutine-viz-core after the de-fork — no .kt files
 * should exist under backend/src/main/.../session/.
 */
class ForkDeletionTest {
    companion object {
        /**
         * Exact file names of the 10 fork session classes that were deleted.
         * If any of these reappears under backend/src/main/.../session/, the fork
         * has been silently re-introduced and this test must fail.
         */
        private val DELETED_FORK_CLASS_FILES = listOf(
            "EventApplier.kt",
            "EventBus.kt",
            "EventContext.kt",
            "EventStore.kt",
            "FlowEventContext.kt",
            "ChannelEventContext.kt",
            "JobStatusMonitor.kt",
            "ProjectionService.kt",
            "SessionManager.kt",
            "VizSession.kt",
        )

        /**
         * The session package directory under backend/src/main that must be empty.
         * Resolved relative to the project root (backend/ working dir during tests).
         */
        private val SESSION_FORK_DIR = File("src/main/kotlin/com/jh/proj/coroutineviz/session")
    }

    @Test
    fun `no session fork classes remain under backend src main (FND-01 static guard)`() {
        // Collect any .kt files present in the fork directory
        val existingKtFiles = if (SESSION_FORK_DIR.exists() && SESSION_FORK_DIR.isDirectory) {
            SESSION_FORK_DIR.listFiles { f -> f.extension == "kt" }?.toList() ?: emptyList()
        } else {
            emptyList()
        }

        val reintroducedFiles = existingKtFiles.filter { file ->
            file.name in DELETED_FORK_CLASS_FILES
        }

        assertTrue(
            reintroducedFiles.isEmpty(),
            "Fork session classes have been silently re-introduced under backend/src/main/.../session/. " +
                "Remove these files — the session package is owned by coroutine-viz-core only (FND-01). " +
                "Re-introduced files: ${reintroducedFiles.map { it.name }}"
        )

        // Also assert the directory is either absent or contains no .kt files at all
        val allKtFiles = if (SESSION_FORK_DIR.exists() && SESSION_FORK_DIR.isDirectory) {
            SESSION_FORK_DIR.listFiles { f -> f.extension == "kt" }?.size ?: 0
        } else {
            0
        }

        assertEquals(
            0,
            allKtFiles,
            "backend/src/main/.../session/ must contain 0 .kt files after de-fork. " +
                "Found $allKtFiles .kt file(s). The session package belongs exclusively to coroutine-viz-core."
        )
    }
}
