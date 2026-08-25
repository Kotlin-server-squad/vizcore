package com.jh.proj.coroutineviz.cli

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the real [runCli] engine over two frozen `/events` exports decoded via core's
 * `appJson`, proving the resolved A2 exit policy end-to-end:
 * - a clean Created→Started→Completed export → 0
 * - a bad Created+Started-never-terminated export → 1 (LifecycleValidator StartedHasTerminal Fail)
 * - a blank path → 2 (usage)
 */
class CliReplayTest {
    private fun resourcePath(name: String): String {
        val url =
            requireNotNull(javaClass.classLoader.getResource(name)) {
                "test resource not found on classpath: $name"
            }
        return java.io.File(url.toURI()).absolutePath
    }

    /** Runs [block] with stdout captured and returns the captured text. */
    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, "UTF-8"))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString("UTF-8")
    }

    @Test
    fun `bad export exits 1 and prints a finding`() {
        val badPath = resourcePath("events-bad.json")
        var exit = -1
        val out = captureStdout { exit = runCli(badPath) }
        assertEquals(1, exit, "a started-never-terminated coroutine must trip a Fail/blocking anti-pattern")
        assertTrue(
            out.contains("FAIL") || out.contains("ANTI-PATTERN"),
            "expected at least one finding line on stdout, got:\n$out",
        )
    }

    @Test
    fun `clean export exits 0`() {
        val cleanPath = resourcePath("events-clean.json")
        val exit = runCli(cleanPath)
        assertEquals(0, exit, "a Created->Started->Completed export with no ERROR/WARNING anti-pattern must pass")
    }

    @Test
    fun `blank path is a usage error and exits 2`() {
        assertEquals(2, runCli(""))
    }
}
