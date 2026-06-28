package com.jh.proj.coroutineviz.harness

import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarFile
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * D-09 build assertion: the dev-only `loadHarness` source set must NEVER appear in the
 * production artifact.
 *
 * The harness floods synthetic events straight at `EventBus.send`. If its classes ever
 * leaked into the shipped jar/shadowJar, that synthetic-event injection surface would exist
 * in production (threat T-10-15). This test fails the build the moment any
 * `com/jh/proj/coroutineviz/harness/` entry shows up in a built jar.
 *
 * Two layers of defense, both asserted here:
 *  1. **Classpath**: no harness class is reachable on `:test`'s runtime classpath. `:test`
 *     extends `main` (NOT `loadHarness`), so a harness class here would mean the harness
 *     leaked into `main` — exactly the leak we forbid.
 *  2. **Built jar**: if a `jar`/`shadowJar`/`-all.jar` output exists in `build/libs`, no entry
 *     path may contain `com/jh/proj/coroutineviz/harness/`.
 *
 * The harness's thin entrypoint (`LoadHarnessMain`) and its reusable driver
 * (`EgressLoadDriver`, which lives in `main`) are distinct: the DRIVER is allowed in the jar
 * (it is generic egress-flood plumbing in `main`); the ENTRYPOINT class
 * `LoadHarnessMain` (the only thing in `src/loadHarness/`) is the leak indicator and must be
 * absent. This test keys on the entrypoint specifically.
 */
class JarExclusionTest {
    @Test
    fun `LoadHarnessMain entrypoint is absent from the test runtime classpath`() {
        // The harness entrypoint lives ONLY in the loadHarness source set, which is NOT on
        // :test's classpath. If this class loads, the harness leaked into main.
        val leaked =
            runCatching {
                Class.forName("com.jh.proj.coroutineviz.harness.LoadHarnessMain")
            }.isSuccess
        assertTrue(
            !leaked,
            "LoadHarnessMain is resolvable from :test's classpath — the dev-only harness leaked " +
                "into the production `main` source set (D-09 violation).",
        )
    }

    @Test
    fun `EgressLoadDriver (the main-resident seam) IS reachable — the test seam is wired`() {
        // Sanity counter-assertion: the reusable driver DOES live in main (so :test and the
        // smoke test can call it), proving the test above is checking the entrypoint, not a
        // missing-package false positive.
        val driver =
            runCatching {
                Class.forName("com.jh.proj.coroutineviz.harness.EgressLoadDriver")
            }.getOrNull()
        assertNotNull(
            driver,
            "EgressLoadDriver must live in `main` so the :test smoke test can call it without " +
                "depending on the loadHarness source set.",
        )
    }

    @Test
    fun `no built jar contains a harness entrypoint entry`() {
        val libs = File("build/libs")
        if (!libs.isDirectory) {
            // No jar built in this run (e.g. a bare `:test` invocation) — the classpath
            // assertion above already proves the source-set boundary. Nothing to scan.
            return
        }
        val jars = libs.listFiles { f -> f.isFile && f.extension == "jar" }.orEmpty()
        val offenders = mutableListOf<String>()
        for (jar in jars) {
            JarFile(jar).use { jf ->
                val hit =
                    jf.entries()
                        .asSequence()
                        .map { it.name }
                        .filter { it.contains("com/jh/proj/coroutineviz/harness/LoadHarnessMain") }
                        .toList()
                if (hit.isNotEmpty()) offenders += "${jar.name}: $hit"
            }
        }
        if (offenders.isNotEmpty()) {
            fail(
                "Production jar(s) contain the dev-only harness entrypoint (D-09 violation):\n" +
                    offenders.joinToString("\n") { "  - $it" },
            )
        }
    }
}
